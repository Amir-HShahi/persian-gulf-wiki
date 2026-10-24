package install

import (
	"bytes"
	"context"
	"io"
	"log/slog"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"wikipg/internal/queue"
	"wikipg/internal/search"
)

// These tests use QUEUE_TEST_DSN's server, but a database of their own: the
// queue and search tests run at the same time in other packages against the
// DSN's database, and installing the search schema there would replace the
// functions they are testing.
const testDatabase = "install_test"

func testPool(t *testing.T) *pgxpool.Pool {
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
		`DROP DATABASE IF EXISTS ` + testDatabase + ` WITH (FORCE)`,
		`CREATE DATABASE ` + testDatabase,
	} {
		if _, err := admin.Exec(ctx, stmt); err != nil {
			t.Fatalf("%s: %v", stmt, err)
		}
	}

	cfg, err := pgxpool.ParseConfig(dsn)
	if err != nil {
		t.Fatal(err)
	}
	cfg.ConnConfig.Database = testDatabase
	pool, err := pgxpool.NewWithConfig(ctx, cfg)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(pool.Close)
	return pool
}

func exec(t *testing.T, pool *pgxpool.Pool, sql string) {
	t.Helper()
	if _, err := pool.Exec(context.Background(), sql); err != nil {
		t.Fatalf("%s: %v", sql, err)
	}
}

func exists(t *testing.T, pool *pgxpool.Pool, name string) bool {
	t.Helper()
	var ok bool
	if err := pool.QueryRow(context.Background(),
		`SELECT to_regclass($1) IS NOT NULL`, name).Scan(&ok); err != nil {
		t.Fatal(err)
	}
	return ok
}

// syncBuffer is a log sink the installer's goroutine writes while the test
// reads it.
type syncBuffer struct {
	mu  chan struct{}
	buf bytes.Buffer
}

func newSyncBuffer() *syncBuffer { return &syncBuffer{mu: make(chan struct{}, 1)} }

func (b *syncBuffer) Write(p []byte) (int, error) {
	b.mu <- struct{}{}
	defer func() { <-b.mu }()
	return b.buf.Write(p)
}

func (b *syncBuffer) String() string {
	b.mu <- struct{}{}
	defer func() { <-b.mu }()
	return b.buf.String()
}

func waitFor(t *testing.T, what string, cond func() bool) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for !cond() {
		if time.Now().After(deadline) {
			t.Fatalf("timed out waiting for %s", what)
		}
		time.Sleep(10 * time.Millisecond)
	}
}

func TestNothingIsInstalledBeforeCoreHasMigrated(t *testing.T) {
	pool := testPool(t)
	logs := newSyncBuffer()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	done := make(chan struct{})
	go func() {
		defer close(done)
		(&Installer{
			DB:       pool,
			Log:      slog.New(slog.NewTextHandler(logs, nil)),
			Interval: 20 * time.Millisecond,
			Parts: []Part{
				{Name: "jobs", SQL: queue.Schema},
				// Stands in for search: it needs a table core has not made yet.
				{Name: "needs-core", SQL: `CREATE VIEW core_probe AS SELECT * FROM core_table`},
			},
		}).Run(ctx)
	}()

	// Long enough for several passes. Creating anything now would leave a
	// non-empty schema with no Flyway history, which Flyway refuses to migrate.
	time.Sleep(150 * time.Millisecond)
	if exists(t, pool, "jobs") {
		t.Fatal("the jobs table was created before core had migrated")
	}

	// Flyway's first migration: the history table appears, core's tables do not
	// exist yet. The part that does not need them goes in on its own.
	exec(t, pool, `CREATE TABLE flyway_schema_history (installed_rank int PRIMARY KEY)`)
	waitFor(t, "the jobs table", func() bool { return exists(t, pool, "jobs") })
	if exists(t, pool, "core_probe") {
		t.Fatal("a part was installed before the table it needs existed")
	}

	// Core's later migration creates the table; the waiting part follows.
	exec(t, pool, `CREATE TABLE core_table (id int)`)
	waitFor(t, "the dependent part", func() bool { return exists(t, pool, "core_probe") })

	select {
	case <-done:
	case <-time.After(5 * time.Second):
		t.Fatal("Run did not return once everything was installed")
	}

	out := logs.String()
	for msg, want := range map[string]int{
		"waiting for core's migrations": 1,
		"cannot install schema yet":     1,
		"schema installed":              2,
	} {
		if got := strings.Count(out, msg); got != want {
			t.Errorf("%q logged %d times, want %d\n%s", msg, got, want, out)
		}
	}
}

// coreTables is the part of core's schema the search functions read, as
// core's migrations leave it after V29.
const coreTables = `
CREATE TABLE flyway_schema_history (installed_rank int PRIMARY KEY);
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

func TestTheRealSchemasInstallAndReinstall(t *testing.T) {
	pool := testPool(t)
	exec(t, pool, coreTables)

	in := &Installer{
		DB:       pool,
		Log:      slog.New(slog.NewTextHandler(io.Discard, nil)),
		Interval: time.Millisecond,
		Parts: []Part{
			{Name: "jobs", SQL: queue.Schema},
			{Name: "search", SQL: search.Schema},
		},
	}
	// Twice: every worker start applies them again over what is there.
	for range 2 {
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		in.Run(ctx)
		failed := ctx.Err() != nil
		cancel()
		if failed {
			t.Fatal("the schemas did not install")
		}
	}

	for _, name := range []string{"jobs", "search_documents"} {
		if !exists(t, pool, name) {
			t.Errorf("%s is missing", name)
		}
	}
	var n int
	if err := pool.QueryRow(context.Background(),
		`SELECT count(*) FROM search_articles('anything')`).Scan(&n); err != nil {
		t.Fatalf("search_articles: %v", err)
	}
}
