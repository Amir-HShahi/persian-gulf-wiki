package queue

import (
	"context"
	"errors"
	"log/slog"
	"time"
)

// cleanupBatch bounds one delete. Passes repeat until one comes back short.
const cleanupBatch = 1000

// Cleaner deletes finished jobs once they are old enough. Without it the jobs
// table only ever grows: every image, spreadsheet and text check leaves a row.
//
// The windows must outlast everyone who reads a result. Core reads an image's
// result within seconds, but a moderator may open a revision's text checks
// days later, which is why the defaults are generous and the configuration
// refuses anything under a day.
type Cleaner struct {
	Store    *Store
	Log      *slog.Logger
	Interval time.Duration

	// KeepDone and KeepFailed are how long after finishing a job is kept.
	// Zero keeps it forever.
	KeepDone   time.Duration
	KeepFailed time.Duration

	// OnDelete, if set, is told how many jobs of a status each pass deleted.
	OnDelete func(status Status, n int64)
}

// Run cleans once at start and then every Interval, until ctx is cancelled.
// Nothing it hits stops the worker: a failed pass is logged and tried again on
// the next interval.
func (c *Cleaner) Run(ctx context.Context) {
	if c.KeepDone == 0 && c.KeepFailed == 0 {
		c.Log.Info("finished jobs are kept forever: JOBS_KEEP_DONE and JOBS_KEEP_FAILED are both 0")
		return
	}

	ticker := time.NewTicker(c.Interval)
	defer ticker.Stop()
	for {
		c.Clean(ctx)
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
		}
	}
}

// Clean runs one pass over both finished statuses.
func (c *Cleaner) Clean(ctx context.Context) {
	for _, rule := range []struct {
		status Status
		keep   time.Duration
	}{
		{StatusDone, c.KeepDone},
		{StatusFailed, c.KeepFailed},
	} {
		if rule.keep == 0 {
			continue
		}
		n, err := c.deleteAll(ctx, rule.status, rule.keep)
		if n > 0 {
			c.Log.Info("deleted old jobs", "status", string(rule.status), "jobs", n, "older_than", rule.keep.String())
			if c.OnDelete != nil {
				c.OnDelete(rule.status, n)
			}
		}
		switch {
		case err == nil, ctx.Err() != nil:
		case errors.Is(err, ErrNotInstalled):
			return // the runner already says so; nothing to clean yet
		default:
			c.Log.Error("deleting old jobs", "status", string(rule.status), "err", err)
		}
	}
}

// deleteAll deletes in batches until a batch comes back short, and returns the
// total, including what was deleted before any error.
func (c *Cleaner) deleteAll(ctx context.Context, status Status, keep time.Duration) (int64, error) {
	var total int64
	for ctx.Err() == nil {
		n, err := c.Store.DeleteFinished(ctx, status, keep, cleanupBatch)
		total += n
		if err != nil {
			return total, err
		}
		if n < cleanupBatch {
			break
		}
	}
	return total, nil
}
