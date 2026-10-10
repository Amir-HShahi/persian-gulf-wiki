// Package search keeps the article search index in step with what the site
// publishes, and runs queries against it.
//
// The work itself happens in Postgres: schema.sql holds the folding rules, the
// index table and the functions that refresh and query it. Keeping all of it in
// the database means there is exactly one implementation of how text is
// folded, used both when an article is indexed and when a reader searches, so
// the two can never disagree. This package schedules that work and gives Go
// callers a typed way in.
package search

import (
	"context"
	_ "embed"
	"errors"
	"fmt"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
)

// Schema is the SQL that creates the index. It belongs in core's Flyway
// migrations; it is embedded here so tests and the search CLI can apply it to a
// scratch database.
//
//go:embed schema.sql
var Schema string

// DB is what both a connection pool and a transaction provide.
type DB interface {
	Query(ctx context.Context, sql string, args ...any) (pgx.Rows, error)
	QueryRow(ctx context.Context, sql string, args ...any) pgx.Row
}

// ErrNotInstalled means schema.sql has not been applied to this database.
var ErrNotInstalled = errors.New("search: schema is not installed in this database")

// Reconcile refreshes up to batch index rows that are missing, stale or no
// longer published, and reports how many it indexed and removed. A result
// smaller than batch means the index has caught up.
func Reconcile(ctx context.Context, db DB, batch int) (indexed, removed int, err error) {
	err = db.QueryRow(ctx, `SELECT indexed, removed FROM search_reconcile($1)`, batch).
		Scan(&indexed, &removed)
	if err != nil {
		return 0, 0, wrap("reconciling", err)
	}
	return indexed, removed, nil
}

// Query is one search. Language and EntityType are optional filters; empty
// means all.
type Query struct {
	Text       string
	Language   string
	EntityType string
	Limit      int
	Offset     int
}

// DefaultLimit is the page size when Query.Limit is not set. The database caps
// any page at 100.
const DefaultLimit = 20

// Hit is one matching translation. Snippet is HTML, already escaped, with the
// matching words wrapped in <mark>. Every other field is plain text.
type Hit struct {
	TranslationID string  `json:"translation_id"`
	ArticleID     string  `json:"article_id"`
	Language      string  `json:"language"`
	EntityType    string  `json:"entity_type"`
	Slug          string  `json:"slug"`
	Title         string  `json:"title"`
	Snippet       string  `json:"snippet"`
	Score         float32 `json:"score"`
}

// Page is one page of hits and the total across all pages.
type Page struct {
	Hits  []Hit `json:"hits"`
	Total int64 `json:"total"`
}

func Search(ctx context.Context, db DB, q Query) (Page, error) {
	limit := q.Limit
	if limit <= 0 {
		limit = DefaultLimit
	}

	rows, err := db.Query(ctx, `
		SELECT translation_id::text, article_id::text, language, entity_type, slug,
		       title, snippet, score, total
		  FROM search_articles($1, $2, $3, $4, $5)`,
		q.Text, nullIfEmpty(q.Language), nullIfEmpty(q.EntityType), limit, max(q.Offset, 0))
	if err != nil {
		return Page{}, wrap("searching", err)
	}
	defer rows.Close()

	page := Page{Hits: []Hit{}}
	for rows.Next() {
		var h Hit
		if err := rows.Scan(&h.TranslationID, &h.ArticleID, &h.Language, &h.EntityType,
			&h.Slug, &h.Title, &h.Snippet, &h.Score, &page.Total); err != nil {
			return Page{}, wrap("reading results", err)
		}
		page.Hits = append(page.Hits, h)
	}
	if err := rows.Err(); err != nil {
		return Page{}, wrap("reading results", err)
	}
	return page, nil
}

// splitsPersianWords reports whether this database's locale lets the text
// search parser see Persian letters as letters. Under the C locale it does not,
// every Persian word is discarded as punctuation, and search silently finds
// nothing in Persian or Arabic.
func splitsPersianWords(ctx context.Context, db DB) (bool, error) {
	var ok bool
	err := db.QueryRow(ctx, `SELECT to_tsvector('simple', 'کتاب') <> ''::tsvector`).Scan(&ok)
	return ok, err
}

func nullIfEmpty(s string) any {
	if s == "" {
		return nil
	}
	return s
}

// wrap turns "no such function" and "no such table" into ErrNotInstalled, so a
// caller can tell a database that was never set up for search from one that
// failed.
func wrap(action string, err error) error {
	var pgErr *pgconn.PgError
	if errors.As(err, &pgErr) {
		switch pgErr.Code {
		case "42883", "42P01": // undefined_function, undefined_table
			return ErrNotInstalled
		}
	}
	return fmt.Errorf("search: %s: %w", action, err)
}
