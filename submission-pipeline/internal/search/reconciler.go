package search

import (
	"context"
	"errors"
	"log/slog"
	"time"
)

// Reconciler keeps the index in step with core by asking Postgres, on a timer,
// to refresh whatever changed since it last looked.
//
// Polling instead of reacting to events is deliberate. Nothing has to be added
// to core's write path, so search can never slow down or fail an article save,
// and a missed signal cannot leave the index wrong for good: the next pass
// simply finds the difference. The cost is that a newly approved article
// becomes searchable within one interval rather than instantly.
type Reconciler struct {
	DB       DB
	Log      *slog.Logger
	Interval time.Duration
	Batch    int
}

// Run syncs once straight away, which is what backfills a fresh index, and then
// every Interval until ctx is cancelled. It never returns an error: search is
// a convenience next to the job loop, and nothing it hits is allowed to stop
// the worker.
func (r *Reconciler) Run(ctx context.Context) {
	r.checkLocale(ctx)

	ticker := time.NewTicker(r.Interval)
	defer ticker.Stop()

	notInstalled := false
	for {
		err := r.sync(ctx)
		switch {
		case errors.Is(err, ErrNotInstalled):
			// Logged once, not every tick: until someone applies the schema the
			// answer will not change, and repeating it would bury real errors.
			if !notInstalled {
				r.Log.Warn("search index is not installed; sync paused until schema.sql is applied")
				notInstalled = true
			}
		case err != nil && ctx.Err() == nil:
			r.Log.Error("search sync failed; retrying next interval", "err", err)
		case err == nil && notInstalled:
			r.Log.Info("search index found; sync resumed")
			notInstalled = false
		}

		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
		}
	}
}

// sync keeps reconciling until a pass comes back with spare capacity, so a large
// backlog is cleared in one go instead of one batch per interval.
func (r *Reconciler) sync(ctx context.Context) error {
	for {
		indexed, removed, err := Reconcile(ctx, r.DB, r.Batch)
		if err != nil {
			return err
		}
		if indexed > 0 || removed > 0 {
			r.Log.Info("search index updated", "indexed", indexed, "removed", removed)
		}
		if indexed+removed < r.Batch || ctx.Err() != nil {
			return nil
		}
	}
}

func (r *Reconciler) checkLocale(ctx context.Context) {
	ok, err := splitsPersianWords(ctx, r.DB)
	if err != nil {
		return // reported by the first sync, which hits the same database
	}
	if !ok {
		r.Log.Error("this database's locale does not treat Persian letters as letters; " +
			"Persian and Arabic searches will find nothing until it uses a UTF-8 locale")
	}
}
