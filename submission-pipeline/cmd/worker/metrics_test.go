package main

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"wikipg/internal/queue"
)

func scrape(t *testing.T, m *Metrics, stats func(context.Context) (queue.Stats, error)) string {
	t.Helper()
	rec := httptest.NewRecorder()
	m.Handle(slog.New(slog.NewTextHandler(io.Discard, nil)), stats)(rec, httptest.NewRequest("GET", "/metrics", nil))
	if ct := rec.Header().Get("Content-Type"); !strings.HasPrefix(ct, "text/plain; version=0.0.4") {
		t.Errorf("content type %q", ct)
	}
	return rec.Body.String()
}

func wantLines(t *testing.T, body string, lines ...string) {
	t.Helper()
	for _, l := range lines {
		if !strings.Contains(body, "\n"+l+"\n") && !strings.HasPrefix(body, l+"\n") {
			t.Errorf("missing line %q in:\n%s", l, body)
		}
	}
}

func TestMetricsShowTheQueueAndWhatThisWorkerDid(t *testing.T) {
	m := NewMetrics()
	m.JobFinished(queue.Finish{Type: queue.JobTypeImage, Outcome: "ok", Duration: 800 * time.Millisecond})
	m.JobFinished(queue.Finish{Type: queue.JobTypeImage, Outcome: "ok", Duration: 2500 * time.Millisecond}) // on a bound: in that bucket
	m.JobFinished(queue.Finish{Type: queue.JobTypeImage, Outcome: "retry", Duration: 400 * time.Second})    // past every bound
	m.JobsDeleted(queue.StatusDone, 1200)

	body := scrape(t, m, func(context.Context) (queue.Stats, error) {
		return queue.Stats{
			Counts:    []queue.Count{{Type: "image", Status: "pending", Jobs: 3}},
			OldestDue: 90 * time.Second,
		}, nil
	})

	wantLines(t, body,
		"wikipg_queue_up 1",
		`wikipg_jobs{type="image",status="pending"} 3`,
		"wikipg_jobs_oldest_due_seconds 90",
		`wikipg_job_attempts_total{type="image",outcome="ok"} 2`,
		`wikipg_job_attempts_total{type="image",outcome="retry"} 1`,
		`wikipg_job_duration_seconds_bucket{type="image",le="0.5"} 0`,
		`wikipg_job_duration_seconds_bucket{type="image",le="1"} 1`,
		`wikipg_job_duration_seconds_bucket{type="image",le="2.5"} 2`,
		`wikipg_job_duration_seconds_bucket{type="image",le="300"} 2`,
		`wikipg_job_duration_seconds_bucket{type="image",le="+Inf"} 3`,
		`wikipg_job_duration_seconds_sum{type="image"} 403.3`,
		`wikipg_job_duration_seconds_count{type="image"} 3`,
		`wikipg_jobs_deleted_total{status="done"} 1200`,
		`wikipg_jobs_deleted_total{status="failed"} 0`,
		"# TYPE wikipg_job_duration_seconds histogram",
	)
}

// An unreadable queue must not look like an empty one.
func TestMetricsLeaveTheQueueOutWhenItCannotBeRead(t *testing.T) {
	body := scrape(t, NewMetrics(), func(context.Context) (queue.Stats, error) {
		return queue.Stats{}, errors.New("connection refused")
	})
	wantLines(t, body, "wikipg_queue_up 0")
	if strings.Contains(body, "wikipg_jobs{") || strings.Contains(body, "oldest_due") {
		t.Errorf("queue numbers shown without a queue:\n%s", body)
	}
}

// Job types are free text from core; one with a quote must not break the format.
func TestMetricsEscapeLabelValues(t *testing.T) {
	m := NewMetrics()
	m.JobFinished(queue.Finish{Type: "a\"b\\c\nd", Outcome: "failed", Duration: time.Second})
	body := scrape(t, m, func(context.Context) (queue.Stats, error) { return queue.Stats{}, nil })
	wantLines(t, body, `wikipg_job_attempts_total{type="a\"b\\c\nd",outcome="failed"} 1`)
}
