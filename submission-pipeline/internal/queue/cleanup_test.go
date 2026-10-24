package queue

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"slices"
	"sync"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"
)

// finishedJob adds a job in the given status that finished the given number of
// hours ago, on the database's clock.
func finishedJob(t *testing.T, status string, hoursAgo int) int64 {
	t.Helper()
	return scalar[int64](t, `
		INSERT INTO jobs (type, payload, status, updated_at)
		VALUES ('image', '{}', $1, now() - make_interval(hours => $2)) RETURNING id`, status, hoursAgo)
}

func remaining(t *testing.T) []int64 {
	t.Helper()
	rows, err := testPool.Query(context.Background(), `SELECT id FROM jobs ORDER BY id`)
	if err != nil {
		t.Fatal(err)
	}
	ids, err := pgx.CollectRows(rows, pgx.RowTo[int64])
	if err != nil {
		t.Fatal(err)
	}
	return ids
}

func TestCleanDeletesOnlyFinishedJobsPastTheirWindow(t *testing.T) {
	pool(t)
	oldDone := finishedJob(t, "done", 48)
	newDone := finishedJob(t, "done", 1)
	oldFailed := finishedJob(t, "failed", 48)
	midFailed := finishedJob(t, "failed", 30) // past the done window, inside the failed one
	pending := enqueue(t, "image", 0)
	exec(t, `UPDATE jobs SET updated_at = now() - interval '1000 hours' WHERE id = $1`, pending)
	running := abandon(t, "image", "w1", 1, 5)
	exec(t, `UPDATE jobs SET updated_at = now() - interval '1000 hours' WHERE id = $1`, running)

	deleted := map[Status]int64{}
	(&Cleaner{
		Store:      NewStore(testPool),
		Log:        slog.New(slog.NewTextHandler(io.Discard, nil)),
		KeepDone:   24 * time.Hour,
		KeepFailed: 36 * time.Hour,
		OnDelete:   func(s Status, n int64) { deleted[s] += n },
	}).Clean(context.Background())

	want := []int64{newDone, midFailed, pending, running}
	if got := remaining(t); !slices.Equal(got, want) {
		t.Errorf("left %v, want %v (deleted old done %d and old failed %d only)", got, want, oldDone, oldFailed)
	}
	if deleted[StatusDone] != 1 || deleted[StatusFailed] != 1 {
		t.Errorf("reported deletions %v", deleted)
	}
}

func TestAZeroWindowKeepsThatStatusForever(t *testing.T) {
	pool(t)
	done := finishedJob(t, "done", 10000)
	finishedJob(t, "failed", 48)

	(&Cleaner{
		Store:      NewStore(testPool),
		Log:        slog.New(slog.NewTextHandler(io.Discard, nil)),
		KeepFailed: 24 * time.Hour,
	}).Clean(context.Background())

	if got := remaining(t); !slices.Equal(got, []int64{done}) {
		t.Errorf("left %v, want only the done job %d", got, done)
	}
}

func TestCleanKeepsGoingPastOneBatch(t *testing.T) {
	pool(t)
	exec(t, `
		INSERT INTO jobs (type, payload, status, updated_at)
		SELECT 'image', '{}', 'done', now() - interval '48 hours'
		FROM generate_series(1, $1)`, cleanupBatch*2+5)

	(&Cleaner{
		Store:    NewStore(testPool),
		Log:      slog.New(slog.NewTextHandler(io.Discard, nil)),
		KeepDone: 24 * time.Hour,
	}).Clean(context.Background())

	if n := scalar[int64](t, `SELECT count(*) FROM jobs`); n != 0 {
		t.Errorf("%d jobs left", n)
	}
}

func TestDeleteFinishedRefusesUnfinishedStatuses(t *testing.T) {
	pool(t)
	for _, s := range []Status{StatusPending, StatusRunning, "anything"} {
		if _, err := NewStore(testPool).DeleteFinished(context.Background(), s, time.Hour, 10); err == nil {
			t.Errorf("deleting %q jobs was allowed", s)
		}
	}
}

func TestStatsCountsByTypeAndStatusAndTheOldestDueWait(t *testing.T) {
	pool(t)
	enqueue(t, "image", 0)
	enqueue(t, "image", 0)
	enqueueAfter(t, "tabular", 3600) // not due: counted, but not waiting
	finishedJob(t, "done", 1)
	exec(t, `UPDATE jobs SET run_after = now() - interval '90 seconds' WHERE id = 1`)

	st, err := NewStore(testPool).Stats(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	want := []Count{
		{JobTypeImage, StatusDone, 1},
		{JobTypeImage, StatusPending, 2},
		{JobTypeTabular, StatusPending, 1},
	}
	if !slices.Equal(st.Counts, want) {
		t.Errorf("counts %v, want %v", st.Counts, want)
	}
	if st.OldestDue < 89*time.Second || st.OldestDue > 120*time.Second {
		t.Errorf("oldest due wait %s, want about 90s", st.OldestDue)
	}
}

func TestStatsOnAnEmptyQueueWaitsZero(t *testing.T) {
	pool(t)
	st, err := NewStore(testPool).Stats(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if len(st.Counts) != 0 || st.OldestDue != 0 {
		t.Errorf("empty queue stats %+v", st)
	}
}

func TestStatsAndCleanSayWhenTheTableIsMissing(t *testing.T) {
	pool(t)
	exec(t, `ALTER TABLE jobs RENAME TO jobs_hidden`)
	t.Cleanup(func() { exec(t, `ALTER TABLE jobs_hidden RENAME TO jobs`) })

	s := NewStore(testPool)
	if _, err := s.Stats(context.Background()); !errors.Is(err, ErrNotInstalled) {
		t.Errorf("stats: %v", err)
	}
	if _, err := s.DeleteFinished(context.Background(), StatusDone, time.Hour, 10); !errors.Is(err, ErrNotInstalled) {
		t.Errorf("delete: %v", err)
	}
}

func TestTheRunnerReportsHowEachAttemptEnded(t *testing.T) {
	pool(t)
	okID := enqueue(t, "image", 0)
	rejectedID := enqueue(t, "panorama", 0)
	failingID := enqueue(t, "tabular", 2)
	unknownID := enqueue(t, "mystery", 0)

	var (
		mu       sync.Mutex
		outcomes = map[JobType][]string{}
	)
	r := NewRunner(NewStore(testPool), slog.New(slog.NewTextHandler(io.Discard, nil)), fast(), "w1", func() {})
	r.OnFinish(func(f Finish) {
		mu.Lock()
		defer mu.Unlock()
		outcomes[f.Type] = append(outcomes[f.Type], f.Outcome)
		if f.Duration <= 0 {
			t.Errorf("%s: duration %s", f.Type, f.Duration)
		}
	})
	r.Register(JobTypeImage, handlerFunc(func(context.Context, *slog.Logger, pgx.Tx, Job) (Result, error) {
		return ok(), nil
	}))
	r.Register(JobTypePanorama, handlerFunc(func(context.Context, *slog.Logger, pgx.Tx, Job) (Result, error) {
		return Result{Outcome: OutcomeRejected}, nil
	}))
	r.Register(JobTypeTabular, handlerFunc(func(context.Context, *slog.Logger, pgx.Tx, Job) (Result, error) {
		return Result{}, errors.New("storage down")
	}))

	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { done <- r.Run(ctx) }()
	stop := sync.OnceValue(func() error { cancel(); return <-done })
	t.Cleanup(func() { _ = stop() })

	waitForStatus(t, okID, "done")
	waitForStatus(t, rejectedID, "done")
	waitForStatus(t, failingID, "failed")
	waitForStatus(t, unknownID, "failed")
	if err := stop(); err != nil && !errors.Is(err, context.Canceled) {
		t.Fatal(err)
	}

	mu.Lock()
	defer mu.Unlock()
	want := map[JobType][]string{
		JobTypeImage:    {"ok"},
		JobTypePanorama: {"rejected"},
		JobTypeTabular:  {"retry", "failed"},
		"mystery":       {"failed"},
	}
	for jt, w := range want {
		if !slices.Equal(outcomes[jt], w) {
			t.Errorf("%s: outcomes %v, want %v", jt, outcomes[jt], w)
		}
	}
}
