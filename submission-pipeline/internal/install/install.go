// Package install applies the worker's own schema (the jobs table, the search
// index and its functions) to core's database, so a fresh stack works without
// anyone running SQL by hand.
//
// It waits for core first. Flyway refuses to migrate a schema that already has
// tables in it but no flyway_schema_history, so a worker that created its jobs
// table in an empty database would stop core from ever starting. Nothing is
// applied until that history table exists.
//
// Every part is written to be run again, so applying it on every start is how
// a changed schema reaches a database that has an older one.
package install

import (
	"context"
	"errors"
	"log/slog"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
)

// DB is what a connection pool provides.
type DB interface {
	Begin(ctx context.Context) (pgx.Tx, error)
	QueryRow(ctx context.Context, sql string, args ...any) pgx.Row
}

// Part is one schema file. Name is only for logs.
type Part struct {
	Name string
	SQL  string
}

// Installer applies Parts once core has migrated, retrying each one every
// Interval until it goes in. A part that fails does not hold back the others:
// search needs core's article tables, the jobs table does not.
type Installer struct {
	DB       DB
	Log      *slog.Logger
	Interval time.Duration
	Parts    []Part

	// HistoryTable is the table whose existence means core has migrated.
	// Empty means flyway_schema_history.
	HistoryTable string
}

// Run returns once every part is installed, or when ctx is cancelled.
func (in *Installer) Run(ctx context.Context) {
	pending := append([]Part(nil), in.Parts...)
	warned := map[string]bool{}
	for {
		pending = in.pass(ctx, pending, warned)
		if len(pending) == 0 {
			return
		}
		select {
		case <-ctx.Done():
			return
		case <-time.After(in.Interval):
		}
	}
}

// pass tries every pending part once and returns those still pending. Each
// problem is logged the first time only, so a worker waiting an hour for core
// writes one line, not one a poll.
func (in *Installer) pass(ctx context.Context, pending []Part, warned map[string]bool) []Part {
	migrated, err := in.coreMigrated(ctx)
	switch {
	case err != nil:
		if !warned["core"] {
			in.Log.Warn("cannot tell whether core has migrated; retrying", "err", err)
			warned["core"] = true
		}
		return pending
	case !migrated:
		if !warned["core"] {
			in.Log.Info("waiting for core's migrations before installing the worker's schema",
				"table", in.historyTable())
			warned["core"] = true
		}
		return pending
	}

	var left []Part
	for _, p := range pending {
		if err := apply(ctx, in.DB, p.SQL); err != nil {
			if ctx.Err() != nil {
				return pending
			}
			if !warned[p.Name] {
				in.Log.Warn("cannot install schema yet; retrying", "schema", p.Name, "err", describe(err))
				warned[p.Name] = true
			}
			left = append(left, p)
			continue
		}
		in.Log.Info("schema installed", "schema", p.Name)
	}
	return left
}

func (in *Installer) coreMigrated(ctx context.Context) (bool, error) {
	var ok bool
	err := in.DB.QueryRow(ctx, `SELECT to_regclass($1) IS NOT NULL`, in.historyTable()).Scan(&ok)
	return ok, err
}

func (in *Installer) historyTable() string {
	if in.HistoryTable == "" {
		return "flyway_schema_history"
	}
	return in.HistoryTable
}

// apply runs one part in its own transaction, so a part that fails halfway
// leaves nothing behind. The advisory lock makes replicas starting together
// take turns instead of racing on the same CREATE statements.
func apply(ctx context.Context, db DB, sql string) error {
	tx, err := db.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)

	if _, err := tx.Exec(ctx, `SELECT pg_advisory_xact_lock(hashtext('wikipg_install'))`); err != nil {
		return err
	}
	// No arguments, so pgx sends this as a simple query, which may hold many
	// statements.
	if _, err := tx.Exec(ctx, sql); err != nil {
		return err
	}
	return tx.Commit(ctx)
}

// describe keeps the Postgres message and drops pgx's wrapping, which is what
// a person reading the log needs to see what core has not created yet.
func describe(err error) string {
	var pgErr *pgconn.PgError
	if errors.As(err, &pgErr) {
		return pgErr.Message
	}
	return err.Error()
}
