package queue

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"os"
	"slices"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// These tests run the real Store and Runner against a real Postgres, because
// what they check — row locking, transactions, NOTIFY, lock expiry — is
// behaviour of the database, and a fake would only test the fake. Point
// QUEUE_TEST_DSN at a scratch database; the jobs table is dropped and
// recreated.
//
//	QUEUE_TEST_DSN=postgres://core:test@localhost:55432/core?sslmode=disable go test ./internal/queue/
var testPool *pgxpool.Pool

func TestMain(m *testing.M) {
	if dsn := os.Getenv("QUEUE_TEST_DSN"); dsn != "" {
		ctx := context.Background()
		pool, err := pgxpool.New(ctx, dsn)
		if err != nil {
			fmt.Fprintln(os.Stderr, "connecting to QUEUE_TEST_DSN:", err)
			os.Exit(1)
		}
		// Start from nothing so the schema in this branch is what is tested.
		for _, stmt := range []string{
			`DROP TABLE IF EXISTS jobs, queue_probe CASCADE`,
			Schema,
			`CREATE TABLE queue_probe (id serial PRIMARY KEY, note text NOT NULL)`,
		} {
			if _, err := pool.Exec(ctx, stmt); err != nil {
				fmt.Fprintln(os.Stderr, "preparing test database:", err)
				os.Exit(1)
			}
		}
		testPool = pool
	}

	code := m.Run()
	if testPool != nil {
		testPool.Close()
	}
	os.Exit(code)
}

func pool(t *testing.T) *pgxpool.Pool {
	t.Helper()
	if testPool == nil {
		t.Skip("QUEUE_TEST_DSN not set")
	}
	exec(t, `TRUNCATE jobs, queue_probe RESTART IDENTITY`)
	return testPool
}

func exec(t *testing.T, sql string, args ...any) {
	t.Helper()
	if _, err := testPool.Exec(context.Background(), sql, args...); err != nil {
		t.Fatalf("%s: %v", sql, err)
	}
}

func scalar[T any](t *testing.T, sql string, args ...any) T {
	t.Helper()
	var v T
	if err := testPool.QueryRow(context.Background(), sql, args...).Scan(&v); err != nil {
		t.Fatalf("%s: %v", sql, err)
	}
	return v
}

// enqueue adds a pending, immediately due job. maxAttempts 0 leaves the job's
// own ceiling unset, so the worker's default applies.
func enqueue(t *testing.T, jobType string, maxAttempts int) int64 {
	t.Helper()
	var ceiling any
	if maxAttempts > 0 {
		ceiling = maxAttempts
	}
	return scalar[int64](t,
		`INSERT INTO jobs (type, payload, max_attempts) VALUES ($1, '{}', $2) RETURNING id`,
		jobType, ceiling)
}

// enqueueAfter adds a pending job that is not due for the given number of
// seconds. The offset is applied in SQL so it is measured on the database's
// clock, which is the one the queue compares against.
func enqueueAfter(t *testing.T, jobType string, seconds int) int64 {
	t.Helper()
	return scalar[int64](t, `
		INSERT INTO jobs (type, payload, run_after)
		VALUES ($1, '{}', now() + make_interval(secs => $2)) RETURNING id`, jobType, seconds)
}

// abandon simulates a worker that died holding a job: the row stays running
// under a lock that lapsed a second ago.
func abandon(t *testing.T, jobType, by string, attempts, maxAttempts int) int64 {
	t.Helper()
	return scalar[int64](t, `
		INSERT INTO jobs (type, payload, status, attempts, max_attempts, locked_by, locked_until)
		VALUES ($1, '{}', 'running', $2, $3, $4, now() - interval '1 second') RETURNING id`,
		jobType, attempts, maxAttempts, by)
}

type jobRow struct {
	Status      string
	Attempts    int
	MaxAttempts *int
	LockedBy    *string
	LastError   *string
	Result      []byte
}

func getJob(t *testing.T, id int64) jobRow {
	t.Helper()
	var r jobRow
	err := testPool.QueryRow(context.Background(), `
		SELECT status, attempts, max_attempts, locked_by, last_error, result
		  FROM jobs WHERE id = $1`, id).
		Scan(&r.Status, &r.Attempts, &r.MaxAttempts, &r.LockedBy, &r.LastError, &r.Result)
	if err != nil {
		t.Fatalf("reading job %d: %v", id, err)
	}
	return r
}

func eventually(t *testing.T, within time.Duration, what string, cond func() bool) {
	t.Helper()
	deadline := time.Now().Add(within)
	for !cond() {
		if time.Now().After(deadline) {
			t.Fatalf("timed out after %s waiting for %s", within, what)
		}
		time.Sleep(10 * time.Millisecond)
	}
}

func waitForStatus(t *testing.T, id int64, want string) jobRow {
	t.Helper()
	var row jobRow
	eventually(t, 10*time.Second, fmt.Sprintf("job %d to be %s", id, want), func() bool {
		row = getJob(t, id)
		return row.Status == want
	})
	return row
}

// ---- Store ----

func TestClaimOnAnEmptyQueueReportsNothing(t *testing.T) {
	s := NewStore(pool(t))
	if _, ok, err := s.Claim(context.Background(), time.Minute, "w1", 3); err != nil || ok {
		t.Fatalf("Claim = ok %v, err %v; want nothing", ok, err)
	}
}

func TestClaimTakesTheOldestDueJobAndLocksIt(t *testing.T) {
	s := NewStore(pool(t))
	first := enqueue(t, "image", 0)
	second := enqueue(t, "image", 0)

	job, ok, err := s.Claim(context.Background(), time.Hour, "w1", 3)
	if err != nil || !ok {
		t.Fatalf("Claim = ok %v, err %v", ok, err)
	}
	if job.ID != first || job.Attempts != 1 || job.Type != JobTypeImage {
		t.Errorf("claimed %+v, want job %d on attempt 1", job, first)
	}
	if job.MaxAttempts != 0 {
		t.Errorf("MaxAttempts = %d, want 0 (unset) for a job with no ceiling of its own", job.MaxAttempts)
	}

	row := getJob(t, first)
	if row.Status != "running" || row.LockedBy == nil || *row.LockedBy != "w1" {
		t.Errorf("row = %+v, want running under w1", row)
	}
	if !scalar[bool](t, `SELECT locked_until > now() + interval '59 minutes' FROM jobs WHERE id = $1`, first) {
		t.Error("the lock does not last the requested hour")
	}

	next, ok, _ := s.Claim(context.Background(), time.Hour, "w1", 3)
	if !ok || next.ID != second {
		t.Errorf("second claim = %d (ok %v), want %d", next.ID, ok, second)
	}
}

func TestClaimSkipsAJobUntilItIsDue(t *testing.T) {
	s := NewStore(pool(t))
	id := enqueueAfter(t, "image", 3600)

	if _, ok, _ := s.Claim(context.Background(), time.Minute, "w1", 3); ok {
		t.Fatal("claimed a job that is not due")
	}

	exec(t, `UPDATE jobs SET run_after = now() - interval '1 second' WHERE id = $1`, id)
	if job, ok, _ := s.Claim(context.Background(), time.Minute, "w1", 3); !ok || job.ID != id {
		t.Fatal("did not claim the job once it was due")
	}
}

func TestConcurrentClaimersNeverShareAJob(t *testing.T) {
	s := NewStore(pool(t))
	const jobs, workers = 60, 8
	for range jobs {
		enqueue(t, "image", 0)
	}

	var mu sync.Mutex
	claimed := map[int64]int{}
	var wg sync.WaitGroup
	for w := range workers {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for {
				job, ok, err := s.Claim(context.Background(), time.Hour, fmt.Sprintf("w%d", w), 3)
				if err != nil {
					t.Error(err)
					return
				}
				if !ok {
					return
				}
				mu.Lock()
				claimed[job.ID]++
				mu.Unlock()
			}
		}()
	}
	wg.Wait()

	if len(claimed) != jobs {
		t.Errorf("%d distinct jobs claimed, want %d", len(claimed), jobs)
	}
	for id, n := range claimed {
		if n != 1 {
			t.Errorf("job %d was claimed %d times", id, n)
		}
	}
}

func TestAHeldJobIsNotClaimedUntilItsLockLapses(t *testing.T) {
	s := NewStore(pool(t))
	id := enqueue(t, "image", 0)
	if _, ok, _ := s.Claim(context.Background(), time.Hour, "w1", 3); !ok {
		t.Fatal("setup claim failed")
	}

	if _, ok, _ := s.Claim(context.Background(), time.Hour, "w2", 3); ok {
		t.Fatal("took a job whose lock is still good")
	}

	// w1 dies. Its lock runs out and the job is anyone's again.
	exec(t, `UPDATE jobs SET locked_until = now() - interval '1 second' WHERE id = $1`, id)
	job, ok, _ := s.Claim(context.Background(), time.Hour, "w2", 3)
	if !ok || job.ID != id || job.Attempts != 2 {
		t.Fatalf("takeover = %+v (ok %v), want job %d on attempt 2", job, ok, id)
	}
	if row := getJob(t, id); row.LockedBy == nil || *row.LockedBy != "w2" {
		t.Errorf("job is held by %v, want w2", row.LockedBy)
	}
}

func TestHeartbeatExtendsTheHoldersLockAndNobodyElses(t *testing.T) {
	s := NewStore(pool(t))
	id := enqueue(t, "image", 0)
	s.Claim(context.Background(), time.Second, "w1", 3)

	if err := s.Heartbeat(context.Background(), id, "w1", time.Hour); err != nil {
		t.Fatalf("holder's heartbeat: %v", err)
	}
	if !scalar[bool](t, `SELECT locked_until > now() + interval '59 minutes' FROM jobs WHERE id = $1`, id) {
		t.Error("the heartbeat did not extend the lock")
	}

	if err := s.Heartbeat(context.Background(), id, "w2", time.Hour); err == nil {
		t.Error("a worker that does not hold the job extended its lock")
	}

	// w1 stalls, w2 legitimately takes over, and w1's late heartbeat must not
	// steal the job back.
	exec(t, `UPDATE jobs SET locked_until = now() - interval '1 second' WHERE id = $1`, id)
	s.Claim(context.Background(), time.Hour, "w2", 3)
	if err := s.Heartbeat(context.Background(), id, "w1", time.Hour); err == nil {
		t.Error("a heartbeat from the previous holder was accepted")
	}
}

func TestCompleteRecordsTheResultAndReleasesTheLock(t *testing.T) {
	s := NewStore(pool(t))
	id := enqueue(t, "image", 0)
	s.Claim(context.Background(), time.Minute, "w1", 3)

	ctx := context.Background()
	tx, _ := s.Begin(ctx)
	if err := s.Complete(ctx, tx, id, Result{Outcome: OutcomeOK}); err != nil {
		t.Fatal(err)
	}
	if err := tx.Commit(ctx); err != nil {
		t.Fatal(err)
	}

	row := getJob(t, id)
	if row.Status != "done" || row.LockedBy != nil || row.LastError != nil {
		t.Errorf("row = %+v, want done with no lock or error", row)
	}
	var res Result
	if err := json.Unmarshal(row.Result, &res); err != nil || res.Outcome != OutcomeOK {
		t.Errorf("stored result = %s (%v)", row.Result, err)
	}
}

func TestCompleteIsAtomicWithTheCallersTransaction(t *testing.T) {
	s := NewStore(pool(t))
	id := enqueue(t, "image", 0)
	s.Claim(context.Background(), time.Minute, "w1", 3)

	ctx := context.Background()
	tx, _ := s.Begin(ctx)
	if err := s.Complete(ctx, tx, id, Result{Outcome: OutcomeOK}); err != nil {
		t.Fatal(err)
	}
	if err := tx.Rollback(ctx); err != nil {
		t.Fatal(err)
	}

	if row := getJob(t, id); row.Status != "running" || row.Result != nil {
		t.Errorf("row = %+v, want the job untouched after a rollback", row)
	}
}

func TestRetryReschedulesAndDeadLetterParks(t *testing.T) {
	s := NewStore(pool(t))
	ctx := context.Background()
	id := enqueue(t, "image", 0)
	s.Claim(ctx, time.Minute, "w1", 3)

	if err := s.Retry(ctx, id, time.Hour, "storage down"); err != nil {
		t.Fatal(err)
	}
	row := getJob(t, id)
	if row.Status != "pending" || row.LockedBy != nil || row.LastError == nil || *row.LastError != "storage down" {
		t.Errorf("after Retry: %+v", row)
	}
	if !scalar[bool](t, `SELECT run_after > now() + interval '59 minutes' FROM jobs WHERE id = $1`, id) {
		t.Error("the retry was not pushed an hour out")
	}
	if _, ok, _ := s.Claim(ctx, time.Minute, "w1", 3); ok {
		t.Error("claimed a job that is waiting out its retry delay")
	}

	exec(t, `UPDATE jobs SET run_after = now() - interval '1 second' WHERE id = $1`, id)
	s.Claim(ctx, time.Minute, "w1", 3)
	if err := s.DeadLetter(ctx, id, "out of attempts"); err != nil {
		t.Fatal(err)
	}
	if row := getJob(t, id); row.Status != "failed" || row.LockedBy != nil {
		t.Errorf("after DeadLetter: %+v", row)
	}
	if _, ok, _ := s.Claim(ctx, time.Minute, "w1", 3); ok {
		t.Error("claimed a dead-lettered job")
	}
}

func TestTheSchemaRefusesNonsense(t *testing.T) {
	pool(t)
	bad := map[string]string{
		"unknown status":               `INSERT INTO jobs (type, payload, status) VALUES ('x', '{}', 'bogus')`,
		"running with no owner":        `INSERT INTO jobs (type, payload, status) VALUES ('x', '{}', 'running')`,
		"pending but locked":           `INSERT INTO jobs (type, payload, locked_by, locked_until) VALUES ('x', '{}', 'w1', now())`,
		"a ceiling of zero":            `INSERT INTO jobs (type, payload, max_attempts) VALUES ('x', '{}', 0)`,
		"negative attempts":            `INSERT INTO jobs (type, payload, attempts) VALUES ('x', '{}', -1)`,
		"no payload":                   `INSERT INTO jobs (type) VALUES ('x')`,
		"owner but no expiry":          `INSERT INTO jobs (type, payload, status, locked_by) VALUES ('x', '{}', 'running', 'w1')`,
		"done job that is still owned": `INSERT INTO jobs (type, payload, status, locked_by, locked_until) VALUES ('x', '{}', 'done', 'w1', now())`,
	}
	for name, sql := range bad {
		if _, err := testPool.Exec(context.Background(), sql); err == nil {
			t.Errorf("the schema accepted %s", name)
		}
	}
}

func TestEnqueueingWakesAListener(t *testing.T) {
	s := NewStore(pool(t))
	ctx, cancel := context.WithCancel(context.Background())
	var woken atomic.Int32
	done := make(chan struct{})
	go func() {
		defer close(done)
		_ = s.Listen(ctx, func() { woken.Add(1) })
	}()
	defer func() { cancel(); <-done }()

	// Listening starts asynchronously, so keep enqueueing until the first
	// notification lands; each insert fires one once the listener is up.
	eventually(t, 5*time.Second, "a notification", func() bool {
		enqueue(t, "image", 0)
		time.Sleep(20 * time.Millisecond)
		return woken.Load() > 0
	})
}

// ---- Runner ----

type handlerFunc func(ctx context.Context, log *slog.Logger, tx pgx.Tx, job Job) (Result, error)

func (f handlerFunc) Handle(ctx context.Context, log *slog.Logger, tx pgx.Tx, job Job) (Result, error) {
	return f(ctx, log, tx, job)
}

func ok() Result { return Result{Outcome: OutcomeOK} }

// fast is a runner config with every delay shrunk so a retry takes
// milliseconds, not minutes.
func fast() RunnerConfig {
	return RunnerConfig{
		Concurrency:       4,
		JobTimeout:        5 * time.Second,
		PollInterval:      20 * time.Millisecond,
		HeartbeatInterval: 50 * time.Millisecond,
		LockDuration:      2 * time.Second,
		MaxAttempts:       3,
		RetryBaseDelay:    5 * time.Millisecond,
	}
}

// start runs a runner until the test ends and returns a function that stops it
// early and reports what Run returned.
func start(t *testing.T, name string, cfg RunnerConfig, handlers map[JobType]Handler) (stop func() error) {
	t.Helper()
	r := NewRunner(NewStore(testPool), slog.New(slog.NewTextHandler(io.Discard, nil)), cfg, name, func() {})
	for jt, h := range handlers {
		r.Register(jt, h)
	}

	ctx, cancel := context.WithCancel(context.Background())
	result := make(chan error, 1)
	go func() { result <- r.Run(ctx) }()

	var once sync.Once
	var err error
	stop = func() error {
		once.Do(func() {
			cancel()
			select {
			case err = <-result:
			case <-time.After(10 * time.Second):
				err = errors.New("Run did not return within 10s of cancel")
			}
		})
		return err
	}
	t.Cleanup(func() { _ = stop() })
	return stop
}

func TestRunnerCompletesAJobAndStampsTheResult(t *testing.T) {
	pool(t)
	id := enqueue(t, "image", 0)
	start(t, "w1", fast(), map[JobType]Handler{
		JobTypeImage: handlerFunc(func(context.Context, *slog.Logger, pgx.Tx, Job) (Result, error) {
			return Result{Outcome: OutcomeOK, Image: &ImageResult{Width: 10, Height: 5}}, nil
		}),
	})

	row := waitForStatus(t, id, "done")
	var res Result
	if err := json.Unmarshal(row.Result, &res); err != nil {
		t.Fatal(err)
	}

	// The handler filled in only the outcome; the runner owns the rest.
	if res.Version != 1 || res.JobID != id || res.Type != JobTypeImage || res.Attempt != 1 ||
		res.FinishedAt.IsZero() || res.Outcome != OutcomeOK {
		t.Errorf("stamped result = %+v", res)
	}
	if res.Image == nil || res.Image.Width != 10 {
		t.Errorf("the handler's payload was lost: %+v", res.Image)
	}
}

func TestARejectionIsDoneAndNeverRetried(t *testing.T) {
	pool(t)
	id := enqueue(t, "image", 0)
	var calls atomic.Int32
	start(t, "w1", fast(), map[JobType]Handler{
		JobTypeImage: handlerFunc(func(context.Context, *slog.Logger, pgx.Tx, Job) (Result, error) {
			calls.Add(1)
			return Result{Outcome: OutcomeRejected, Reason: &Reason{Code: "image_unreadable"}}, nil
		}),
	})

	row := waitForStatus(t, id, "done")
	time.Sleep(150 * time.Millisecond) // long enough for a wrongful retry to show
	if calls.Load() != 1 {
		t.Errorf("handler ran %d times, want 1", calls.Load())
	}
	var res Result
	_ = json.Unmarshal(row.Result, &res)
	if res.Outcome != OutcomeRejected || res.Reason == nil || res.Reason.Code != "image_unreadable" {
		t.Errorf("result = %+v", res)
	}
}

func TestAFailingJobIsRetriedThenParkedAsFailed(t *testing.T) {
	pool(t)
	id := enqueue(t, "image", 4) // the job's own ceiling beats the worker's 3
	var calls atomic.Int32
	start(t, "w1", fast(), map[JobType]Handler{
		JobTypeImage: handlerFunc(func(context.Context, *slog.Logger, pgx.Tx, Job) (Result, error) {
			calls.Add(1)
			return Result{}, errors.New("storage unreachable")
		}),
	})

	row := waitForStatus(t, id, "failed")
	if calls.Load() != 4 || row.Attempts != 4 {
		t.Errorf("ran %d times (attempts %d), want 4 of 4", calls.Load(), row.Attempts)
	}
	if row.LastError == nil || !strings.Contains(*row.LastError, "storage unreachable") {
		t.Errorf("last_error = %v", row.LastError)
	}
}

func TestAJobWithoutItsOwnCeilingUsesTheWorkersDefault(t *testing.T) {
	pool(t)
	id := enqueue(t, "image", 0) // fast() sets the worker default to 3
	var calls atomic.Int32
	start(t, "w1", fast(), map[JobType]Handler{
		JobTypeImage: handlerFunc(func(context.Context, *slog.Logger, pgx.Tx, Job) (Result, error) {
			calls.Add(1)
			return Result{}, errors.New("boom")
		}),
	})

	waitForStatus(t, id, "failed")
	if calls.Load() != 3 {
		t.Errorf("ran %d times, want the worker default of 3", calls.Load())
	}
}

func TestAPanickingHandlerDoesNotTakeTheWorkerDown(t *testing.T) {
	pool(t)
	bad := enqueue(t, "bomb", 1)
	good := enqueue(t, "image", 0)
	start(t, "w1", fast(), map[JobType]Handler{
		"bomb": handlerFunc(func(context.Context, *slog.Logger, pgx.Tx, Job) (Result, error) {
			panic("decoder blew up on a malformed file")
		}),
		JobTypeImage: handlerFunc(func(context.Context, *slog.Logger, pgx.Tx, Job) (Result, error) {
			return ok(), nil
		}),
	})

	row := waitForStatus(t, bad, "failed")
	if row.LastError == nil || !strings.Contains(*row.LastError, "panicked") {
		t.Errorf("last_error = %v", row.LastError)
	}
	waitForStatus(t, good, "done") // the worker survived and kept working
}

func TestAnUnknownJobTypeIsParkedWithoutRetries(t *testing.T) {
	pool(t)
	id := enqueue(t, "mystery", 0)
	start(t, "w1", fast(), map[JobType]Handler{
		JobTypeImage: handlerFunc(func(context.Context, *slog.Logger, pgx.Tx, Job) (Result, error) { return ok(), nil }),
	})

	row := waitForStatus(t, id, "failed")
	if row.Attempts != 1 || row.LastError == nil || !strings.Contains(*row.LastError, "unknown job type") {
		t.Errorf("row = %+v, want one attempt and an unknown-type error", row)
	}
}

func TestAJobAbandonedByADeadWorkerIsPickedUp(t *testing.T) {
	pool(t)
	id := abandon(t, "image", "dead-worker", 1, 5)
	start(t, "w1", fast(), map[JobType]Handler{
		JobTypeImage: handlerFunc(func(context.Context, *slog.Logger, pgx.Tx, Job) (Result, error) { return ok(), nil }),
	})

	row := waitForStatus(t, id, "done")
	if row.Attempts != 2 {
		t.Errorf("attempts = %d, want 2 (the dead worker's, then ours)", row.Attempts)
	}
}

// A job that kills its worker never reaches the failure path, because there is
// no worker left to record one. The only trace is that its lock lapsed with
// every attempt already spent, and that has to end it: otherwise one malformed
// upload that exhausts memory is run again, forever, by each worker in turn.
func TestAJobThatKeepsKillingItsWorkerIsEventuallyParked(t *testing.T) {
	pool(t)
	id := abandon(t, "image", "dead-worker", 3, 3) // died on its last allowed attempt
	var calls atomic.Int32
	start(t, "w1", fast(), map[JobType]Handler{
		JobTypeImage: handlerFunc(func(context.Context, *slog.Logger, pgx.Tx, Job) (Result, error) {
			calls.Add(1)
			return ok(), nil
		}),
	})

	row := waitForStatus(t, id, "failed")
	if calls.Load() != 0 {
		t.Errorf("the handler ran %d times for a job with no attempts left", calls.Load())
	}
	if row.LastError == nil || !strings.Contains(*row.LastError, "attempts") {
		t.Errorf("last_error = %v, want it to say the attempts ran out", row.LastError)
	}
}

func TestConcurrencyIsBounded(t *testing.T) {
	pool(t)
	var ids []int64
	for range 8 {
		ids = append(ids, enqueue(t, "image", 0))
	}

	cfg := fast()
	cfg.Concurrency = 2
	var running, peak atomic.Int32
	start(t, "w1", cfg, map[JobType]Handler{
		JobTypeImage: handlerFunc(func(context.Context, *slog.Logger, pgx.Tx, Job) (Result, error) {
			n := running.Add(1)
			for {
				p := peak.Load()
				if n <= p || peak.CompareAndSwap(p, n) {
					break
				}
			}
			time.Sleep(60 * time.Millisecond)
			running.Add(-1)
			return ok(), nil
		}),
	})

	for _, id := range ids {
		waitForStatus(t, id, "done")
	}
	if got := peak.Load(); got != 2 {
		t.Errorf("peak concurrency = %d, want exactly 2", got)
	}
}

func TestAJobPastItsTimeoutIsCancelledAndRetried(t *testing.T) {
	pool(t)
	id := enqueue(t, "image", 2)
	cfg := fast()
	cfg.JobTimeout = 80 * time.Millisecond
	var cancelled atomic.Int32
	start(t, "w1", cfg, map[JobType]Handler{
		JobTypeImage: handlerFunc(func(ctx context.Context, _ *slog.Logger, _ pgx.Tx, _ Job) (Result, error) {
			<-ctx.Done()
			cancelled.Add(1)
			return Result{}, ctx.Err()
		}),
	})

	row := waitForStatus(t, id, "failed")
	if cancelled.Load() != 2 {
		t.Errorf("handler was cancelled %d times, want 2", cancelled.Load())
	}
	if row.LastError == nil || !strings.Contains(*row.LastError, "deadline") {
		t.Errorf("last_error = %v", row.LastError)
	}
}

func TestAHandlersWritesCommitOnlyWithTheirJob(t *testing.T) {
	pool(t)
	good := enqueue(t, "good", 0)
	bad := enqueue(t, "bad", 1)

	write := func(note string, fail bool) Handler {
		return handlerFunc(func(ctx context.Context, _ *slog.Logger, tx pgx.Tx, _ Job) (Result, error) {
			if _, err := tx.Exec(ctx, `INSERT INTO queue_probe (note) VALUES ($1)`, note); err != nil {
				return Result{}, err
			}
			if fail {
				return Result{}, errors.New("failed after writing")
			}
			return ok(), nil
		})
	}
	start(t, "w1", fast(), map[JobType]Handler{"good": write("kept", false), "bad": write("discarded", true)})

	waitForStatus(t, good, "done")
	waitForStatus(t, bad, "failed")

	notes := []string{}
	rows, _ := testPool.Query(context.Background(), `SELECT note FROM queue_probe ORDER BY id`)
	for rows.Next() {
		var n string
		_ = rows.Scan(&n)
		notes = append(notes, n)
	}
	rows.Close()
	if !slices.Equal(notes, []string{"kept"}) {
		t.Errorf("rows written = %v, want only the completed job's", notes)
	}
}

func TestHeartbeatKeepsALongJobFromBeingTakenOver(t *testing.T) {
	pool(t)
	id := enqueue(t, "image", 0)

	cfg := fast()
	cfg.LockDuration = 300 * time.Millisecond
	cfg.HeartbeatInterval = 60 * time.Millisecond

	var calls atomic.Int32
	slow := map[JobType]Handler{
		JobTypeImage: handlerFunc(func(context.Context, *slog.Logger, pgx.Tx, Job) (Result, error) {
			calls.Add(1)
			time.Sleep(1200 * time.Millisecond) // four lock lifetimes
			return ok(), nil
		}),
	}
	start(t, "w1", cfg, slow)
	start(t, "w2", cfg, slow) // idle, polling every 20ms, ready to pounce on a lapsed lock

	row := waitForStatus(t, id, "done")
	if calls.Load() != 1 || row.Attempts != 1 {
		t.Errorf("ran %d times (attempts %d), want once: the heartbeat should have held the lock", calls.Load(), row.Attempts)
	}
}

func TestShutdownLetsARunningJobFinish(t *testing.T) {
	pool(t)
	id := enqueue(t, "image", 0)
	stop := start(t, "w1", fast(), map[JobType]Handler{
		JobTypeImage: handlerFunc(func(context.Context, *slog.Logger, pgx.Tx, Job) (Result, error) {
			time.Sleep(400 * time.Millisecond)
			return ok(), nil
		}),
	})

	waitForStatus(t, id, "running")
	if err := stop(); !errors.Is(err, context.Canceled) {
		t.Errorf("Run returned %v, want context.Canceled", err)
	}
	if row := getJob(t, id); row.Status != "done" {
		t.Errorf("status = %s after shutdown, want the in-flight job finished", row.Status)
	}
}

func TestEnqueueingWakesTheRunnerWithoutWaitingForThePoll(t *testing.T) {
	pool(t)
	cfg := fast()
	cfg.PollInterval = time.Hour // only a notification can get a job picked up
	start(t, "w1", cfg, map[JobType]Handler{
		JobTypeImage: handlerFunc(func(context.Context, *slog.Logger, pgx.Tx, Job) (Result, error) { return ok(), nil }),
	})

	// The listener comes up asynchronously, so keep enqueueing until one lands.
	eventually(t, 10*time.Second, "a job to be picked up by notification alone", func() bool {
		enqueue(t, "image", 0)
		time.Sleep(30 * time.Millisecond)
		return scalar[int](t, `SELECT count(*) FROM jobs WHERE status = 'done'`) > 0
	})
}
