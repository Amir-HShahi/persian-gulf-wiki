package queue

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"math/rand/v2"
	"runtime/debug"
	"sync"
	"time"

	"github.com/jackc/pgx/v5"
)

// Handler processes one job. Implementations log domain events only — the
// dispatcher owns received/completed/failed and timing.
//
// The transaction is the handler's to write domain rows into. It is committed
// together with the job's completion, so a handler's work and the record that
// it ran can never disagree.
type Handler interface {
	Handle(ctx context.Context, log *slog.Logger, tx pgx.Tx, job Job) (Result, error)
}

// Runner owns the job lifecycle: claim, guard, complete, retry, dead-letter.
type Runner struct {
	store    *Store
	handlers map[JobType]Handler
	log      *slog.Logger
	cfg      RunnerConfig
	identity string
	onPoll   func()
	onFinish func(Finish)

	// notInstalled remembers that the jobs table was missing on the last check, so the
	// warning is said once and not on every poll. Only drain touches it, and drain runs
	// on Run's single goroutine.
	notInstalled bool
}

// RunnerConfig carries the timing knobs, mirroring config.Worker.
type RunnerConfig struct {
	Concurrency       int
	JobTimeout        time.Duration
	PollInterval      time.Duration
	HeartbeatInterval time.Duration
	LockDuration      time.Duration
	MaxAttempts       int
	RetryBaseDelay    time.Duration
}

// NewRunner builds a runner. identity is recorded as locked_by, so a row in
// the database says which process is holding it. onPoll is called after every
// queue check and drives the health heartbeat.
func NewRunner(store *Store, log *slog.Logger, cfg RunnerConfig, identity string, onPoll func()) *Runner {
	return &Runner{
		store:    store,
		handlers: make(map[JobType]Handler),
		log:      log,
		cfg:      cfg,
		identity: identity,
		onPoll:   onPoll,
	}
}

// Finish is how one attempt at a job ended, for metrics.
type Finish struct {
	Type JobType

	// Outcome is "ok" or "rejected" for a completed job, "retry" for a failed
	// attempt that will run again, and "failed" for a job given up on.
	Outcome string

	// Duration is the whole attempt, including recording how it ended.
	Duration time.Duration
}

// OnFinish sets a function told about every attempt this runner finishes. Not
// safe for concurrent use: call it during startup, before Run.
func (r *Runner) OnFinish(fn func(Finish)) {
	r.onFinish = fn
}

func (r *Runner) finished(t JobType, outcome string, start time.Time) {
	if r.onFinish != nil {
		r.onFinish(Finish{Type: t, Outcome: outcome, Duration: time.Since(start)})
	}
}

// Register binds a handler to a job type. Not safe for concurrent use — call
// it during startup, before Run.
func (r *Runner) Register(t JobType, h Handler) {
	r.handlers[t] = h
}

// Run consumes jobs until ctx is cancelled, then waits for in-flight work.
func (r *Runner) Run(ctx context.Context) error {
	wake := make(chan struct{}, 1)
	go r.listen(ctx, wake)

	ticker := time.NewTicker(r.cfg.PollInterval)
	defer ticker.Stop()

	sem := make(chan struct{}, r.cfg.Concurrency)
	var wg sync.WaitGroup

	for {
		r.onPoll()
		r.drain(ctx, sem, &wg)

		select {
		case <-ctx.Done():
			// Stop claiming, but let running jobs finish rather than
			// abandoning them mid-transaction.
			wg.Wait()
			return ctx.Err()
		case <-ticker.C:
		case <-wake:
		}
	}
}

// drain claims and starts jobs until the queue is empty or every worker slot
// is busy.
func (r *Runner) drain(ctx context.Context, sem chan struct{}, wg *sync.WaitGroup) {
	if ids, err := r.store.ParkExhausted(ctx, r.cfg.MaxAttempts); err != nil {
		r.storeError(err, "parking exhausted jobs")
	} else {
		r.tableFound()
		if len(ids) > 0 {
			r.log.Error("jobs parked: their worker died on the last allowed attempt", "job_ids", ids)
		}
	}

	for ctx.Err() == nil {
		select {
		case sem <- struct{}{}:
		default:
			return // all slots busy; the next tick will try again
		}

		job, ok, err := r.store.Claim(ctx, r.cfg.LockDuration, r.identity, r.cfg.MaxAttempts)
		if err != nil {
			<-sem
			r.storeError(err, "claiming job")
			return
		}
		if !ok {
			<-sem
			return // queue empty
		}

		wg.Add(1)
		go func() {
			defer wg.Done()
			defer func() { <-sem }()
			r.process(ctx, job)
		}()
	}
}

// storeError logs a failure to reach the jobs table. A table that does not exist yet is
// what a worker deployed ahead of its migration sees, so that is one warning rather than
// an error on every poll, and the worker waits for the table to appear.
func (r *Runner) storeError(err error, what string) {
	if errors.Is(err, ErrNotInstalled) {
		if !r.notInstalled {
			r.log.Warn("the jobs table is not installed; no jobs will run until it is installed")
			r.notInstalled = true
		}
		return
	}
	r.log.Error(what, "err", err)
}

// tableFound notes that the jobs table is reachable again after being missing.
func (r *Runner) tableFound() {
	if r.notInstalled {
		r.log.Info("the jobs table was found; resuming")
		r.notInstalled = false
	}
}

// listen turns NOTIFY into a wake-up signal, reconnecting on failure. Every
// notification sent while this is disconnected is lost, which is exactly why
// Run also polls on a ticker.
func (r *Runner) listen(ctx context.Context, wake chan<- struct{}) {
	for ctx.Err() == nil {
		err := r.store.Listen(ctx, func() {
			select {
			case wake <- struct{}{}:
			default: // a wake-up is already pending; one is enough
			}
		})
		if ctx.Err() != nil {
			return
		}
		r.log.Warn("notification listener dropped, reconnecting", "err", err)

		select {
		case <-ctx.Done():
			return
		case <-time.After(r.cfg.PollInterval):
		}
	}
}

// process runs one claimed job and records its fate.
func (r *Runner) process(ctx context.Context, job Job) {
	// A claimed job runs to its end even if shutdown begins meanwhile: the work,
	// its heartbeat, and the write that records how it finished all use a context
	// that shutdown cannot cancel. Run stops claiming new jobs and then waits for
	// these, which is what a deploy needs. Tied to the shutdown context instead,
	// the job would be aborted mid-run and then fail to record that too, leaving
	// it locked until the lock expired. The job's own timeout still bounds it.
	ctx = context.WithoutCancel(ctx)

	jobLog := r.log.With(job.LogAttrs()...)
	start := time.Now()

	h, ok := r.handlers[job.Type]
	if !ok {
		// core enqueued a type this build does not know — most likely it was
		// deployed ahead of the worker. Retrying can never help.
		jobLog.Error("unknown job type, dead-lettering")
		r.deadLetter(ctx, jobLog, job, fmt.Sprintf("unknown job type %q", job.Type))
		r.finished(job.Type, "failed", start)
		return
	}

	jobCtx, cancel := context.WithTimeout(ctx, r.cfg.JobTimeout)
	defer cancel()

	stopHeartbeat := r.startHeartbeat(jobCtx, jobLog, job.ID)
	defer stopHeartbeat()

	outcome, err := r.runOnce(jobCtx, jobLog, h, job)
	if err != nil {
		outcome = r.recordFailure(ctx, jobLog, job, err)
	}
	r.finished(job.Type, outcome, start)
}

// recordFailure either schedules a retry or dead-letters a job whose attempts
// are exhausted, and returns which: "retry" or "failed". Note the parent ctx is
// used rather than the job's: the job's context may already be cancelled, and
// the failure still has to be written.
func (r *Runner) recordFailure(ctx context.Context, log *slog.Logger, job Job, cause error) string {
	if job.Attempts >= r.maxAttempts(job) {
		log.Error("attempts exhausted, dead-lettering", "err", cause)
		r.deadLetter(ctx, log, job, cause.Error())
		return "failed"
	}

	delay := r.backoff(job.Attempts)
	log.Warn("scheduling retry", "err", cause, "retry_in", delay)
	if err := r.store.Retry(ctx, job.ID, delay, cause.Error()); err != nil {
		log.Error("scheduling retry", "err", err)
	}
	return "retry"
}

// runOnce executes the handler and commits its work together with the job's
// completion, and returns the result's outcome. A returned error means the job
// should be retried or dead-lettered.
func (r *Runner) runOnce(ctx context.Context, log *slog.Logger, h Handler, job Job) (string, error) {
	tx, err := r.store.Begin(ctx)
	if err != nil {
		return "", fmt.Errorf("beginning transaction: %w", err)
	}
	// Rollback is a no-op once the transaction has committed.
	defer func() { _ = tx.Rollback(ctx) }()

	res, err := dispatch(ctx, log, h, tx, job)
	if err != nil {
		return "", err
	}

	if err := r.store.Complete(ctx, tx, job.ID, res); err != nil {
		return "", err
	}
	if err := tx.Commit(ctx); err != nil {
		return "", fmt.Errorf("committing job %d: %w", job.ID, err)
	}
	return string(res.Outcome), nil
}

// startHeartbeat keeps this worker's lock alive while a job runs, and returns
// a function that stops it.
func (r *Runner) startHeartbeat(ctx context.Context, log *slog.Logger, id int64) func() {
	ctx, cancel := context.WithCancel(ctx)
	done := make(chan struct{})

	go func() {
		defer close(done)
		ticker := time.NewTicker(r.cfg.HeartbeatInterval)
		defer ticker.Stop()

		for {
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
				// Deliberately not the job's context: a heartbeat must still
				// be writable while the job is being cancelled.
				hbCtx, hbCancel := context.WithTimeout(context.WithoutCancel(ctx), 5*time.Second)
				err := r.store.Heartbeat(hbCtx, id, r.identity, r.cfg.LockDuration)
				hbCancel()
				if err != nil {
					log.Error("heartbeat", "err", err)
				}
			}
		}
	}()

	return func() {
		cancel()
		<-done
	}
}

func (r *Runner) deadLetter(ctx context.Context, log *slog.Logger, job Job, reason string) {
	if err := r.store.DeadLetter(ctx, job.ID, reason); err != nil {
		log.Error("dead-lettering job", "err", err)
	}
}

func (r *Runner) maxAttempts(job Job) int {
	if job.MaxAttempts > 0 {
		return job.MaxAttempts
	}
	return r.cfg.MaxAttempts
}

// maxBackoff caps exponential growth so a long-broken dependency does not push
// retries days into the future.
const maxBackoff = 30 * time.Minute

// backoff grows exponentially with the attempt count and adds jitter. Without
// jitter, every job that failed during one outage retries at the same instant
// and recreates the load spike that caused it.
func (r *Runner) backoff(attempt int) time.Duration {
	d := r.cfg.RetryBaseDelay
	for i := 1; i < attempt && d < maxBackoff; i++ {
		d *= 2
	}
	if d > maxBackoff {
		d = maxBackoff
	}
	return time.Duration(float64(d) * (0.8 + rand.Float64()*0.4))
}

// dispatch runs one job with correlation logging, timing, and panic recovery.
func dispatch(ctx context.Context, log *slog.Logger, h Handler, tx pgx.Tx, job Job) (res Result, err error) {
	log.Info("job received")
	start := time.Now()

	defer func() {
		elapsed := time.Since(start).Milliseconds()
		if p := recover(); p != nil {
			// A decoder panicking on malformed user input would otherwise take
			// down the whole process, including every concurrent job.
			err = fmt.Errorf("handler panicked: %v", p)
			log.Error("handler panicked",
				"panic", p,
				"stack", string(debug.Stack()),
				"duration_ms", elapsed)
			return
		}
		if err != nil {
			log.Error("job failed", "err", err, "duration_ms", elapsed)
			return
		}
		log.Info("job completed", "outcome", res.Outcome, "duration_ms", elapsed)
	}()

	res, err = h.Handle(ctx, log, tx, job)
	if err != nil {
		return res, err
	}

	// Fields the handler should not have to fill in itself.
	res.JobID = job.ID
	res.Type = job.Type
	res.Attempt = job.Attempts
	res.DurationMS = time.Since(start).Milliseconds()
	res.FinishedAt = time.Now().UTC()
	if res.Version == 0 {
		res.Version = 1
	}
	return res, nil
}
