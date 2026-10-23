package search

import (
	"context"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"os"
	"slices"
	"strings"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
)

// These tests run against a real Postgres, because the behaviour under test is
// SQL: folding, ranking and snippets. Point SEARCH_TEST_DSN at a scratch
// database; every table the tests touch is dropped and recreated.
//
//	SEARCH_TEST_DSN=postgres://core:test@localhost:55432/core?sslmode=disable go test ./internal/search/
var testDB *pgxpool.Pool

// coreArticleTables mirrors the tables search reads, as core's migrations
// leave them: subjects (V15, deleted_at from V29), the article tables (V16,
// with articles.entity_type dropped by V20), and a subject's names: labels
// (V22), subject_aliases (V24) and subject_redirects (V29). Only the columns
// search reads, or that an insert needs, are here.
const coreArticleTables = `
CREATE TABLE subjects (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    kind       varchar(30) NOT NULL,
    deleted_at timestamptz
);
CREATE TABLE articles (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    subject_id         uuid REFERENCES subjects (id),
    canonical_language varchar(20) NOT NULL,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE article_translations (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    article_id          uuid NOT NULL REFERENCES articles (id) ON DELETE CASCADE,
    language            varchar(20) NOT NULL,
    slug                varchar(200) NOT NULL UNIQUE,
    current_revision_id uuid,
    translation_state   varchar(20) NOT NULL DEFAULT 'INDEPENDENT',
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE labels (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    subject_id uuid REFERENCES subjects (id) ON DELETE CASCADE,
    language   varchar(2) NOT NULL,
    value      varchar(200) NOT NULL
);
CREATE TABLE subject_aliases (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    subject_id uuid NOT NULL REFERENCES subjects (id) ON DELETE CASCADE,
    language   varchar(2) NOT NULL,
    value      varchar(200) NOT NULL
);
CREATE TABLE subject_redirects (
    source_id uuid PRIMARY KEY REFERENCES subjects (id) ON DELETE CASCADE,
    target_id uuid NOT NULL REFERENCES subjects (id) ON DELETE CASCADE
);
CREATE TABLE article_revisions (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    translation_id  uuid NOT NULL REFERENCES article_translations (id) ON DELETE CASCADE,
    revision_number int NOT NULL,
    title           text NOT NULL,
    body            jsonb NOT NULL,
    summary         text,
    status          varchar(25) NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now()
);`

func TestMain(m *testing.M) {
	dsn := os.Getenv("SEARCH_TEST_DSN")
	if dsn != "" {
		ctx := context.Background()
		pool, err := pgxpool.New(ctx, dsn)
		if err != nil {
			fmt.Fprintln(os.Stderr, "connecting to SEARCH_TEST_DSN:", err)
			os.Exit(1)
		}
		// Start from nothing, so the schema in this branch is what gets tested
		// rather than whatever an earlier run left behind.
		for _, stmt := range []string{
			`DROP TABLE IF EXISTS search_documents, search_terms, article_revisions, article_translations, articles, subject_redirects, subject_aliases, labels, subjects CASCADE`,
			coreArticleTables,
			Schema,
		} {
			if _, err := pool.Exec(ctx, stmt); err != nil {
				fmt.Fprintln(os.Stderr, "preparing test database:", err)
				os.Exit(1)
			}
		}
		testDB = pool
	}

	code := m.Run()
	if testDB != nil {
		testDB.Close()
	}
	os.Exit(code)
}

// db returns the test database, emptied, or skips the test when none is set.
func db(t *testing.T) *pgxpool.Pool {
	t.Helper()
	if testDB == nil {
		t.Skip("SEARCH_TEST_DSN not set")
	}
	exec(t, `TRUNCATE subjects, labels, subject_aliases, subject_redirects, articles, article_translations, article_revisions, search_documents CASCADE`)
	return testDB
}

func exec(t *testing.T, sql string, args ...any) {
	t.Helper()
	if _, err := testDB.Exec(context.Background(), sql, args...); err != nil {
		t.Fatalf("%s: %v", sql, err)
	}
}

func scalar[T any](t *testing.T, sql string, args ...any) T {
	t.Helper()
	var v T
	if err := testDB.QueryRow(context.Background(), sql, args...).Scan(&v); err != nil {
		t.Fatalf("%s: %v", sql, err)
	}
	return v
}

// publish creates an article with one approved, current translation, the way
// core does on approval, and returns the translation id. A kind gives the
// article a new subject of that kind; an empty kind leaves it without one.
func publish(t *testing.T, language, slug, kind, title, summary, body string) string {
	t.Helper()
	var subjectID any
	if kind != "" {
		subjectID = scalar[string](t, `INSERT INTO subjects (kind) VALUES ($1) RETURNING id::text`, kind)
	}
	articleID := scalar[string](t,
		`INSERT INTO articles (subject_id, canonical_language) VALUES ($1, $2) RETURNING id::text`,
		subjectID, language)
	translationID := scalar[string](t,
		`INSERT INTO article_translations (article_id, language, slug) VALUES ($1, $2, $3) RETURNING id::text`,
		articleID, language, slug)
	revisionID := addRevision(t, translationID, "APPROVED", title, summary, body)
	setCurrent(t, translationID, revisionID)
	return translationID
}

func addRevision(t *testing.T, translationID, status, title, summary, body string) string {
	t.Helper()
	var summaryArg any
	if summary != "" {
		summaryArg = summary
	}
	return scalar[string](t, `
		INSERT INTO article_revisions (translation_id, revision_number, title, body, summary, status)
		VALUES ($1, (SELECT count(*) + 1 FROM article_revisions WHERE translation_id = $1), $2, $3::jsonb, $4, $5)
		RETURNING id::text`, translationID, title, body, summaryArg, status)
}

// setCurrent points a translation at a revision. An empty revision id
// unpublishes it.
func setCurrent(t *testing.T, translationID, revisionID string) {
	t.Helper()
	var rev any
	if revisionID != "" {
		rev = revisionID
	}
	exec(t, `UPDATE article_translations SET current_revision_id = $2 WHERE id = $1`, translationID, rev)
}

func text(s string) string {
	return fmt.Sprintf(`{"type":"doc","children":[{"type":"paragraph","text":%q}]}`, s)
}

func syncAll(t *testing.T) (indexed, removed int) {
	t.Helper()
	indexed, removed, err := Reconcile(context.Background(), testDB, 1000)
	if err != nil {
		t.Fatal(err)
	}
	return indexed, removed
}

func find(t *testing.T, q Query) Page {
	t.Helper()
	p, err := Search(context.Background(), testDB, q)
	if err != nil {
		t.Fatal(err)
	}
	return p
}

func slugsOf(p Page) []string {
	out := make([]string, 0, len(p.Hits))
	for _, h := range p.Hits {
		out = append(out, h.Slug)
	}
	return out
}

func TestFoldUnifiesWhatReadersTypeDifferently(t *testing.T) {
	db(t)
	cases := []struct{ a, b string }{
		{"ماهي", "ماهی"},                 // Arabic yeh
		{"كتاب", "کتاب"},                 // Arabic kaf
		{"کِتاب", "کتاب"},                // harakat
		{"کتاب\u200cها", "کتابها"},       // ZWNJ
		{"کتـــاب", "کتاب"},              // tatweel
		{"خانۀ", "خانه"},                 // heh with yeh above
		{"أسد إيران آب", "اسد ایران اب"}, // alef forms
		{"ﻻ", "لا"},                      // presentation form
		{"۱۴۰۲", "1402"},                 // Persian digits
		{"١٤٠٢", "1402"},                 // Arabic-Indic digits
		{"Strait  of\tHORMUZ", "strait of hormuz"},
	}
	for _, c := range cases {
		if ok := scalar[bool](t, `SELECT search_fold($1) = search_fold($2)`, c.a, c.b); !ok {
			t.Errorf("search_fold(%q) != search_fold(%q)", c.a, c.b)
		}
	}
}

func TestBodyTextKeepsProseAndSkipsStructure(t *testing.T) {
	db(t)
	body := `{"type":"doc","children":[
		{"type":"heading","text":"one"},
		{"type":"paragraph","children":[{"text":"two"},
			{"type":"image","src":"https://example.org/photo.jpg","alt":"four","caption":"three"}]},
		{"type":"link","href":"https://example.org/x","text":"five"}]}`

	got := scalar[string](t, `SELECT search_body_text($1::jsonb)`, body)
	if want := "one\ntwo\nthree\nfour\nfive"; got != want {
		t.Errorf("body text = %q, want %q", got, want)
	}

	// No fixed schema is assumed: a bare string and a flat object both work.
	if got := scalar[string](t, `SELECT search_body_text('"plain"'::jsonb)`); got != "plain" {
		t.Errorf("string body = %q", got)
	}
	if got := scalar[string](t, `SELECT search_body_text('{"text":"flat"}'::jsonb)`); got != "flat" {
		t.Errorf("flat body = %q", got)
	}
}

func TestSearchFindsPersianAndArabicSpellingVariants(t *testing.T) {
	db(t)
	// Written with Arabic yeh and kaf, a hamza, a ZWNJ and Persian digits.
	publish(t, "fa", "kish", "ISLAND", "جزيرهٔ كيش", "",
		text("کیش در سال ۱۳۵۷ یکی از جزیره\u200cهای خلیج فارس بود."))
	syncAll(t)

	for _, q := range []string{"جزیره کیش", "کیش", "كيش", "1357", "جزیرههای"} {
		if got := slugsOf(find(t, Query{Text: q})); !slices.Equal(got, []string{"kish"}) {
			t.Errorf("query %q found %v, want [kish]", q, got)
		}
	}
}

func TestSearchMatchesPrefixesWhileTyping(t *testing.T) {
	db(t)
	publish(t, "fa", "hormoz", "STRAIT", "تنگه هرمز", "", text("آبراهی میان خلیج فارس و دریای عمان"))
	syncAll(t)

	for _, q := range []string{"هرم", "خلیج فا", "تنگ"} {
		if got := slugsOf(find(t, Query{Text: q})); !slices.Equal(got, []string{"hormoz"}) {
			t.Errorf("query %q found %v, want [hormoz]", q, got)
		}
	}
}

func TestTitleMatchOutranksBodyMatch(t *testing.T) {
	db(t)
	publish(t, "fa", "in-body", "", "زیستگاه\u200cهای دریایی", "", text("در این زیستگاه مرجان فراوان است"))
	publish(t, "fa", "in-title", "SPECIES", "مرجان", "", text("جانوری دریایی"))
	syncAll(t)

	got := slugsOf(find(t, Query{Text: "مرجان"}))
	if !slices.Equal(got, []string{"in-title", "in-body"}) {
		t.Errorf("order = %v, want the title match first", got)
	}
}

func TestEnglishSearchMatchesWordForms(t *testing.T) {
	db(t)
	publish(t, "en", "kish-island", "ISLAND", "Kish Island", "", text("Kish is one of the islands of the Persian Gulf."))
	syncAll(t)

	for _, q := range []string{"island", "islands", "ISLANDS", "gulf"} {
		if got := slugsOf(find(t, Query{Text: q})); !slices.Equal(got, []string{"kish-island"}) {
			t.Errorf("query %q found %v, want [kish-island]", q, got)
		}
	}
}

func TestTyposStillFindTheTitle(t *testing.T) {
	db(t)
	publish(t, "en", "hormuz", "STRAIT", "Strait of Hormuz", "", text("A strait between two gulfs."))
	publish(t, "en", "kish-island", "ISLAND", "Kish Island", "", text("An island."))
	syncAll(t)

	if got := slugsOf(find(t, Query{Text: "Hormuzz"})); !slices.Equal(got, []string{"hormuz"}) {
		t.Errorf("misspelt query found %v, want [hormuz]", got)
	}

	// A correctly spelt query must not be padded with near misses.
	if got := slugsOf(find(t, Query{Text: "island"})); !slices.Equal(got, []string{"kish-island"}) {
		t.Errorf("exact query found %v, want only [kish-island]", got)
	}
}

func TestOnlyTheApprovedRevisionIsSearchable(t *testing.T) {
	db(t)
	tr := publish(t, "fa", "versions", "", "نسخه اول", "", text("متن قدیمی"))
	draft := addRevision(t, tr, "DRAFT", "نسخه دوم", "", text("متن تازه"))
	syncAll(t)

	if got := find(t, Query{Text: "تازه"}); got.Total != 0 {
		t.Errorf("a draft is searchable: %v", slugsOf(got))
	}
	if got := find(t, Query{Text: "قدیمی"}); got.Total != 1 {
		t.Errorf("the approved revision is not searchable")
	}

	// Approval moves the current revision; the old text must go with it.
	setCurrent(t, tr, draft)
	if indexed, _ := syncAll(t); indexed != 1 {
		t.Errorf("indexed = %d after approval, want 1", indexed)
	}
	if got := find(t, Query{Text: "تازه"}); got.Total != 1 {
		t.Errorf("the newly approved revision is not searchable")
	}
	if got := find(t, Query{Text: "قدیمی"}); got.Total != 0 {
		t.Errorf("the replaced revision is still searchable")
	}
}

func TestUnpublishedAndDeletedArticlesLeaveTheIndex(t *testing.T) {
	db(t)
	hidden := publish(t, "fa", "hidden", "", "پنهان", "", text("متن"))
	gone := publish(t, "fa", "gone", "", "حذف", "", text("متن"))
	syncAll(t)

	setCurrent(t, hidden, "")
	exec(t, `DELETE FROM article_translations WHERE id = $1`, gone)

	if _, removed := syncAll(t); removed != 2 {
		t.Errorf("removed = %d, want 2", removed)
	}
	if got := find(t, Query{Text: "متن"}); got.Total != 0 {
		t.Errorf("still searchable: %v", slugsOf(got))
	}
}

func TestFiltersByLanguageAndSubjectKind(t *testing.T) {
	db(t)
	publish(t, "fa", "kish-fa", "ISLAND", "کیش", "", text("Kish جزیره"))
	publish(t, "en", "kish-en", "ISLAND", "Kish", "", text("Kish island"))
	publish(t, "en", "kish-port", "PORT", "Kish Port", "", text("Kish harbour"))
	publish(t, "en", "kish-essay", "", "Kish Essay", "", text("Kish in history"))
	syncAll(t)

	if got := slugsOf(find(t, Query{Text: "kish", Language: "fa"})); !slices.Equal(got, []string{"kish-fa"}) {
		t.Errorf("language filter: %v", got)
	}
	// A kind filter drops the article with no subject, as core's list does.
	got := slugsOf(find(t, Query{Text: "kish", Language: "en", SubjectKind: "PORT"}))
	if !slices.Equal(got, []string{"kish-port"}) {
		t.Errorf("kind filter: %v", got)
	}

	hits := find(t, Query{Text: "kish essay"}).Hits
	if len(hits) != 1 || hits[0].SubjectKind != nil {
		t.Errorf("a subject-less article should be found with no kind: %+v", hits)
	}
}

func TestArticlesOfADeletedSubjectLeaveTheIndexUntilItIsRestored(t *testing.T) {
	db(t)
	publish(t, "fa", "qeshm", "ISLAND", "قشم", "", text("جزیره"))
	syncAll(t)
	subject := scalar[string](t, `SELECT subject_id::text FROM articles`)

	exec(t, `UPDATE subjects SET deleted_at = now() WHERE id = $1`, subject)
	if _, removed := syncAll(t); removed != 1 {
		t.Errorf("removed = %d after the subject was deleted, want 1", removed)
	}
	if got := find(t, Query{Text: "قشم"}); got.Total != 0 {
		t.Errorf("an article of a deleted subject is searchable")
	}

	exec(t, `UPDATE subjects SET deleted_at = NULL WHERE id = $1`, subject)
	if indexed, _ := syncAll(t); indexed != 1 {
		t.Errorf("indexed = %d after the subject was restored, want 1", indexed)
	}
	if got := find(t, Query{Text: "قشم"}); got.Total != 1 {
		t.Errorf("a restored subject's article is not searchable")
	}
}

func TestAnIndexFromAnOlderSchemaIsRebuilt(t *testing.T) {
	db(t)
	publish(t, "fa", "old", "ISLAND", "قدیمی", "", text("متن"))
	syncAll(t)

	// An index without the current version on it, as every earlier version of
	// this file left it. Applying the schema drops it, and a reconcile fills it
	// again from core's tables.
	exec(t, `COMMENT ON TABLE search_documents IS NULL`)
	exec(t, Schema)
	if got := scalar[int](t, `SELECT count(*) FROM search_documents`); got != 0 {
		t.Fatalf("the old index was kept: %d rows", got)
	}

	if indexed, _ := syncAll(t); indexed != 1 {
		t.Errorf("indexed = %d after the rebuild, want 1", indexed)
	}
	if got := find(t, Query{Text: "قدیمی", SubjectKind: "ISLAND"}); got.Total != 1 {
		t.Errorf("the rebuilt index does not find the article")
	}

	// Applying the same schema again, as every worker start does, keeps it.
	exec(t, Schema)
	if got := scalar[int](t, `SELECT count(*) FROM search_documents`); got != 1 {
		t.Errorf("a current index was dropped on re-apply: %d rows", got)
	}
}

func TestSnippetShowsTheOriginalTextHighlightedAndEscaped(t *testing.T) {
	db(t)
	publish(t, "fa", "strait", "STRAIT", "تنگه", "",
		text("آبراهی میان خليج فارس و <b>دریای</b> عمان"))
	syncAll(t)

	hits := find(t, Query{Text: "خلیج"}).Hits
	if len(hits) != 1 {
		t.Fatalf("got %d hits, want 1", len(hits))
	}
	s := hits[0].Snippet

	// The word is marked as written (Arabic yeh), though the query used a
	// Persian one, and the surrounding text keeps its alef madda.
	for _, want := range []string{"<mark>خليج</mark>", "آبراهی", "&lt;b&gt;"} {
		if !strings.Contains(s, want) {
			t.Errorf("snippet %q lacks %q", s, want)
		}
	}
	if strings.Contains(s, "<b>") {
		t.Errorf("snippet %q passes HTML through unescaped", s)
	}
}

func TestPagingReportsTheTotal(t *testing.T) {
	db(t)
	for i := range 25 {
		publish(t, "fa", fmt.Sprintf("sea-%02d", i), "", fmt.Sprintf("دریا %d", i), "", text("دریا"))
	}
	syncAll(t)

	p := find(t, Query{Text: "دریا", Limit: 10, Offset: 20})
	if len(p.Hits) != 5 || p.Total != 25 {
		t.Errorf("got %d hits of %d, want 5 of 25", len(p.Hits), p.Total)
	}
}

func TestAnEmptyQueryFindsNothing(t *testing.T) {
	db(t)
	publish(t, "fa", "any", "", "هر چیزی", "", text("متن"))
	syncAll(t)

	for _, q := range []string{"", "   ", "\u200c"} {
		if got := find(t, Query{Text: q}); got.Total != 0 {
			t.Errorf("query %q found %d", q, got.Total)
		}
	}
}

func TestReconcileCatchesUpInBatchesAndThenDoesNothing(t *testing.T) {
	db(t)
	for i := range 7 {
		publish(t, "fa", fmt.Sprintf("a-%d", i), "", "عنوان", "", text("متن"))
	}

	var got []int
	for range 4 {
		indexed, _, err := Reconcile(context.Background(), testDB, 3)
		if err != nil {
			t.Fatal(err)
		}
		got = append(got, indexed)
	}
	if !slices.Equal(got, []int{3, 3, 1, 0}) {
		t.Errorf("indexed per pass = %v, want [3 3 1 0]", got)
	}
}

func TestReconcileReportsAnUninstalledSchema(t *testing.T) {
	db(t)
	ctx := context.Background()
	tx, err := testDB.Begin(ctx)
	if err != nil {
		t.Fatal(err)
	}
	defer tx.Rollback(ctx)

	if _, err := tx.Exec(ctx, `DROP FUNCTION search_reconcile(integer)`); err != nil {
		t.Fatal(err)
	}
	if _, _, err := Reconcile(ctx, tx, 10); !errors.Is(err, ErrNotInstalled) {
		t.Errorf("err = %v, want ErrNotInstalled", err)
	}
}

func TestReconcilerBackfillsThenStopsOnCancel(t *testing.T) {
	db(t)
	publish(t, "fa", "first", "", "یکم", "", text("متن"))
	publish(t, "fa", "second", "", "دوم", "", text("متن"))

	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() {
		defer close(done)
		(&Reconciler{
			DB:       testDB,
			Log:      slog.New(slog.NewTextHandler(io.Discard, nil)),
			Interval: 20 * time.Millisecond,
			Batch:    1, // forces the catch-up loop to run more than once
		}).Run(ctx)
	}()

	deadline := time.Now().Add(5 * time.Second)
	for scalar[int](t, `SELECT count(*) FROM search_documents`) < 2 {
		if time.Now().After(deadline) {
			t.Fatal("the reconciler did not backfill the index")
		}
		time.Sleep(10 * time.Millisecond)
	}

	cancel()
	select {
	case <-done:
	case <-time.After(5 * time.Second):
		t.Fatal("Run did not return after cancel")
	}
}

func TestTestDatabaseSplitsPersianWords(t *testing.T) {
	db(t)
	ok, err := splitsPersianWords(context.Background(), testDB)
	if err != nil {
		t.Fatal(err)
	}
	if !ok {
		t.Fatal("this database cannot split Persian words; every Persian test would be meaningless")
	}
}

// subjectOf returns the subject of the article a translation belongs to.
func subjectOf(t *testing.T, translationID string) string {
	t.Helper()
	return scalar[string](t, `
		SELECT a.subject_id::text FROM articles a
		  JOIN article_translations t ON t.article_id = a.id WHERE t.id = $1`, translationID)
}

func alias(t *testing.T, subjectID, language, value string) {
	t.Helper()
	exec(t, `INSERT INTO subject_aliases (subject_id, language, value) VALUES ($1, $2, $3)`,
		subjectID, language, value)
}

func TestAnotherNameFindsTheArticleAndSaysWhichName(t *testing.T) {
	db(t)
	kish := publish(t, "fa", "kish", "ISLAND", "کیش", "", text("جزیره‌ای در خلیج فارس"))
	subject := subjectOf(t, kish)
	exec(t, `INSERT INTO labels (subject_id, language, value) VALUES ($1, 'en', 'Kish Island')`, subject)
	alias(t, subject, "en", "Qays")
	syncAll(t)

	for _, c := range []struct{ query, matched string }{
		{"Qays", "Qays"},
		{"kish island", "Kish Island"},
		{"Qayss", "Qays"}, // a typo in the other name still lands
	} {
		hits := find(t, Query{Text: c.query}).Hits
		if len(hits) != 1 || hits[0].Slug != "kish" {
			t.Errorf("%q found %v, want [kish]", c.query, slugsOf(Page{Hits: hits}))
			continue
		}
		if hits[0].MatchedName == nil || *hits[0].MatchedName != c.matched {
			t.Errorf("%q: matched_name = %v, want %q", c.query, hits[0].MatchedName, c.matched)
		}
	}

	// Found by its own title: nothing to explain.
	hits := find(t, Query{Text: "کیش"}).Hits
	if len(hits) != 1 || hits[0].MatchedName != nil {
		t.Errorf("a title match carries matched_name: %+v", hits)
	}
}

func TestANewNameIsIndexedWithoutANewRevision(t *testing.T) {
	db(t)
	kish := publish(t, "fa", "kish", "ISLAND", "کیش", "", text("جزیره"))
	syncAll(t)
	subject := subjectOf(t, kish)

	alias(t, subject, "en", "Qays")
	if indexed, _ := syncAll(t); indexed != 1 {
		t.Errorf("indexed = %d after an alias was added, want 1", indexed)
	}
	if got := find(t, Query{Text: "Qays"}); got.Total != 1 {
		t.Errorf("a new alias does not find the article")
	}

	// Core replaces aliases as a whole on approval: a removed one stops matching.
	exec(t, `DELETE FROM subject_aliases WHERE subject_id = $1`, subject)
	if indexed, _ := syncAll(t); indexed != 1 {
		t.Errorf("indexed = %d after the alias was removed, want 1", indexed)
	}
	if got := find(t, Query{Text: "Qays"}); got.Total != 0 {
		t.Errorf("a removed alias still finds the article")
	}
}

func TestAMergedSubjectsNamesLeadToTheSubjectItWasMergedInto(t *testing.T) {
	db(t)
	kish := publish(t, "en", "kish-island", "ISLAND", "Kish Island", "", text("An island."))
	survivor := subjectOf(t, kish)

	// A duplicate subject, merged into Kish: soft-deleted, with a redirect.
	merged := scalar[string](t, `INSERT INTO subjects (kind, deleted_at) VALUES ('ISLAND', now()) RETURNING id::text`)
	alias(t, merged, "en", "Ghais")
	exec(t, `INSERT INTO subject_redirects (source_id, target_id) VALUES ($1, $2)`, merged, survivor)
	syncAll(t)

	hits := find(t, Query{Text: "Ghais"}).Hits
	if len(hits) != 1 || hits[0].Slug != "kish-island" {
		t.Fatalf("the merged subject's alias found %v, want [kish-island]", slugsOf(Page{Hits: hits}))
	}
	if hits[0].MatchedName == nil || *hits[0].MatchedName != "Ghais" {
		t.Errorf("matched_name = %v, want Ghais", hits[0].MatchedName)
	}
}

func suggest(t *testing.T, prefix, language string) []Suggestion {
	t.Helper()
	got, err := Suggest(context.Background(), testDB, prefix, language, 0)
	if err != nil {
		t.Fatal(err)
	}
	return got
}

func titlesOf(s []Suggestion) []string {
	out := make([]string, 0, len(s))
	for _, x := range s {
		out = append(out, x.Title)
	}
	return out
}

func TestSuggestCompletesTitlesAndOtherNames(t *testing.T) {
	db(t)
	kish := publish(t, "en", "kish-island", "ISLAND", "Kish Island", "", text("An island."))
	publish(t, "en", "kharg-island", "ISLAND", "Kharg Island", "", text("An island."))
	publish(t, "en", "island-ecology", "", "Island ecology", "", text("Reefs."))
	publish(t, "fa", "hormoz", "STRAIT", "تنگه هرمز", "", text("آبراه"))
	alias(t, subjectOf(t, kish), "en", "Qays")
	syncAll(t)

	// A title that starts with the text comes first, then shorter titles.
	if got := titlesOf(suggest(t, "isl", "")); !slices.Equal(got, []string{"Island ecology", "Kish Island", "Kharg Island"}) {
		t.Errorf("isl suggested %v", got)
	}
	if got := titlesOf(suggest(t, "khar", "")); !slices.Equal(got, []string{"Kharg Island"}) {
		t.Errorf("khar suggested %v", got)
	}
	// Persian while typing.
	if got := titlesOf(suggest(t, "هر", "fa")); !slices.Equal(got, []string{"تنگه هرمز"}) {
		t.Errorf("هر suggested %v", got)
	}
	if got := suggest(t, "هر", "en"); len(got) != 0 {
		t.Errorf("the language filter let %v through", titlesOf(got))
	}

	// Only an other name starts with the text: the suggestion says which.
	got := suggest(t, "qa", "")
	if len(got) != 1 || got[0].Title != "Kish Island" || got[0].MatchedName == nil || *got[0].MatchedName != "Qays" {
		t.Errorf("qa suggested %+v, want Kish Island by Qays", got)
	}

	if got := suggest(t, "  ", ""); len(got) != 0 {
		t.Errorf("an empty prefix suggested %v", titlesOf(got))
	}
}
