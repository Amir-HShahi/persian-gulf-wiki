package handler

import (
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"os"
	"testing"

	"github.com/jackc/pgx/v5/pgxpool"

	"wikipg/internal/normalize"
	"wikipg/internal/queue"
	"wikipg/internal/search"
)

func TestLocaleOfIgnoresTheRegion(t *testing.T) {
	cases := map[string]normalize.Locale{"fa": "fa", "fa-IR": "fa", "AR": "ar", "en-GB": "en"}
	for tag, want := range cases {
		if got, ok := localeOf(tag); !ok || got != want {
			t.Errorf("localeOf(%q) = %q, %v; want %q", tag, got, ok, want)
		}
	}
	if _, ok := localeOf("de"); ok {
		t.Error("de accepted; there are no rules for it")
	}
}

// normalizeDatabase is a database of its own on QUEUE_TEST_DSN's server, for
// the same reason as the install tests: the queue and search tests use the
// DSN's database at the same time, and this one installs the search schema.
const normalizeDatabase = "normalize_test"

// The part of core's schema a normalize job and search_body_text read, as
// core's migrations leave it.
const normalizeCoreTables = `
CREATE TABLE subjects (id uuid PRIMARY KEY DEFAULT gen_random_uuid(), kind varchar(30) NOT NULL,
    deleted_at timestamptz);
CREATE TABLE articles (id uuid PRIMARY KEY DEFAULT gen_random_uuid(), subject_id uuid,
    canonical_language varchar(20) NOT NULL);
CREATE TABLE article_translations (id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    article_id uuid NOT NULL, language varchar(20) NOT NULL, slug varchar(200) NOT NULL,
    current_revision_id uuid);
CREATE TABLE article_revisions (id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    translation_id uuid NOT NULL, title text NOT NULL, body jsonb NOT NULL, summary text);
CREATE TABLE labels (id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    subject_id uuid REFERENCES subjects (id) ON DELETE CASCADE,
    language varchar(2) NOT NULL, value varchar(200) NOT NULL);
CREATE TABLE subject_aliases (id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    subject_id uuid NOT NULL REFERENCES subjects (id) ON DELETE CASCADE,
    language varchar(2) NOT NULL, value varchar(200) NOT NULL);
CREATE TABLE subject_redirects (source_id uuid PRIMARY KEY REFERENCES subjects (id) ON DELETE CASCADE,
    target_id uuid NOT NULL REFERENCES subjects (id) ON DELETE CASCADE);`

func normalizePool(t *testing.T) *pgxpool.Pool {
	t.Helper()
	dsn := os.Getenv("QUEUE_TEST_DSN")
	if dsn == "" {
		t.Skip("QUEUE_TEST_DSN not set")
	}
	ctx := context.Background()

	admin, err := pgxpool.New(ctx, dsn)
	if err != nil {
		t.Fatal(err)
	}
	defer admin.Close()
	for _, stmt := range []string{
		`DROP DATABASE IF EXISTS ` + normalizeDatabase + ` WITH (FORCE)`,
		`CREATE DATABASE ` + normalizeDatabase,
	} {
		if _, err := admin.Exec(ctx, stmt); err != nil {
			t.Fatalf("%s: %v", stmt, err)
		}
	}

	cfg, err := pgxpool.ParseConfig(dsn)
	if err != nil {
		t.Fatal(err)
	}
	cfg.ConnConfig.Database = normalizeDatabase
	pool, err := pgxpool.NewWithConfig(ctx, cfg)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(pool.Close)

	for _, stmt := range []string{normalizeCoreTables, search.Schema} {
		if _, err := pool.Exec(ctx, stmt); err != nil {
			t.Fatalf("preparing database: %v", err)
		}
	}
	return pool
}

// revision inserts one revision of a translation in language and returns its id.
func revision(t *testing.T, pool *pgxpool.Pool, language, title, body string) string {
	t.Helper()
	var id string
	err := pool.QueryRow(context.Background(), `
		WITH a AS (INSERT INTO articles (canonical_language) VALUES ($1) RETURNING id),
		     t AS (INSERT INTO article_translations (article_id, language, slug)
		           SELECT id, $1, gen_random_uuid()::text FROM a RETURNING id)
		INSERT INTO article_revisions (translation_id, title, body)
		SELECT id, $2, $3::jsonb FROM t RETURNING id::text`, language, title, body).Scan(&id)
	if err != nil {
		t.Fatal(err)
	}
	return id
}

func runNormalize(t *testing.T, pool *pgxpool.Pool, payload string) queue.Result {
	t.Helper()
	ctx := context.Background()
	tx, err := pool.Begin(ctx)
	if err != nil {
		t.Fatal(err)
	}
	defer tx.Rollback(ctx)

	res, err := (&Normalize{}).Handle(ctx, slog.New(slog.NewTextHandler(io.Discard, nil)), tx,
		queue.Job{Type: queue.JobTypeNormalize, Payload: json.RawMessage(payload)})
	if err != nil {
		t.Fatal(err)
	}
	return res
}

func TestNormalizeReportsArabicLettersInAPersianRevision(t *testing.T) {
	pool := normalizePool(t)
	// Arabic kaf and yeh in the body, typed as an Arabic keyboard would.
	id := revision(t, pool, "fa", "کیش",
		`{"type":"doc","children":[{"type":"paragraph","text":"جزيره كيش"}]}`)

	res := runNormalize(t, pool, `{"revision_id":"`+id+`"}`)
	if res.Outcome != queue.OutcomeOK || res.Normalize == nil {
		t.Fatalf("result = %+v", res)
	}
	n := res.Normalize
	if n.Locale != "fa" || n.RevisionID != id || n.Count == 0 || len(n.Findings) != n.Count {
		t.Fatalf("normalize result = %+v", n)
	}
	found := map[string]bool{}
	for _, f := range n.Findings {
		found[f.Found] = true
	}
	if !found["ك"] || !found["ي"] {
		t.Errorf("findings %+v miss the Arabic kaf or yeh", n.Findings)
	}
	// The title is clean, so every finding is on the body's line, not line 1.
	for _, f := range n.Findings {
		if f.Line == 1 {
			t.Errorf("finding on the clean title line: %+v", f)
		}
	}
}

func TestNormalizeTreatsTheSameLettersAsCorrectInArabic(t *testing.T) {
	pool := normalizePool(t)
	id := revision(t, pool, "ar", "جزيرة", `{"text":"جزيرة كيش"}`)

	res := runNormalize(t, pool, `{"revision_id":"`+id+`"}`)
	if res.Normalize == nil || res.Normalize.Count != 0 {
		t.Errorf("an Arabic revision got findings: %+v", res.Normalize)
	}
}

func TestNormalizeRejectsWhatItCannotCheck(t *testing.T) {
	pool := normalizePool(t)
	german := revision(t, pool, "de", "Insel", `{"text":"Kish"}`)

	cases := []struct {
		name, payload, want string
	}{
		{"not json", `nope`, "malformed_payload"},
		{"not a uuid", `{"revision_id":"42"}`, "malformed_payload"},
		{"no such revision", `{"revision_id":"00000000-0000-4000-8000-000000000000"}`, "revision_missing"},
		{"no rules for the language", `{"revision_id":"` + german + `"}`, "unsupported_locale"},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			res := runNormalize(t, pool, c.payload)
			if res.Outcome != queue.OutcomeRejected || res.Reason == nil || res.Reason.Code != c.want {
				t.Errorf("result = %+v, want rejected %q", res, c.want)
			}
		})
	}
}
