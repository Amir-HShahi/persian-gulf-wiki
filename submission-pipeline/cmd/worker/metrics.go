package main

import (
	"context"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"slices"
	"strconv"
	"strings"
	"sync"
	"time"

	"wikipg/internal/queue"
)

// Metrics serves the worker's numbers in Prometheus' text format, written by
// hand: a dozen lines of output do not justify a client library and its
// dependencies.
//
// Two kinds of number are served. The queue's rows are counted from the
// database on each request, so they are the same whichever worker answers.
// Attempts, their durations and deletions are counted by this process since it
// started, which is how Prometheus expects counters to behave.
type Metrics struct {
	mu       sync.Mutex
	attempts map[attemptKey]uint64
	duration map[queue.JobType]*histogram
	deleted  map[queue.Status]uint64
}

type attemptKey struct {
	Type    queue.JobType
	Outcome string
}

// durationBuckets are the histogram's upper bounds in seconds, up to the
// default five-minute job timeout.
var durationBuckets = []float64{0.1, 0.5, 1, 2.5, 5, 10, 30, 60, 120, 300}

type histogram struct {
	counts []uint64 // one per bucket, not cumulative
	sum    float64
	count  uint64
}

func NewMetrics() *Metrics {
	return &Metrics{
		attempts: map[attemptKey]uint64{},
		duration: map[queue.JobType]*histogram{},
		deleted:  map[queue.Status]uint64{},
	}
}

func (m *Metrics) JobFinished(f queue.Finish) {
	m.mu.Lock()
	defer m.mu.Unlock()

	m.attempts[attemptKey{f.Type, f.Outcome}]++

	h := m.duration[f.Type]
	if h == nil {
		h = &histogram{counts: make([]uint64, len(durationBuckets))}
		m.duration[f.Type] = h
	}
	s := f.Duration.Seconds()
	if i, _ := slices.BinarySearch(durationBuckets, s); i < len(durationBuckets) {
		h.counts[i]++
	}
	h.sum += s
	h.count++
}

func (m *Metrics) JobsDeleted(status queue.Status, n int64) {
	m.mu.Lock()
	m.deleted[status] += uint64(n)
	m.mu.Unlock()
}

// Handle serves the metrics. stats reads the queue; when it fails,
// wikipg_queue_up is 0 and the queue's rows are left out rather than shown as
// zero, which would look like an empty queue.
func (m *Metrics) Handle(log *slog.Logger, stats func(context.Context) (queue.Stats, error)) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
		defer cancel()
		st, err := stats(ctx)
		if err != nil {
			log.Warn("reading queue stats for metrics", "err", err)
		}

		w.Header().Set("Content-Type", "text/plain; version=0.0.4; charset=utf-8")
		var b strings.Builder
		m.write(&b, st, err == nil)
		_, _ = io.WriteString(w, b.String())
	}
}

func (m *Metrics) write(b *strings.Builder, st queue.Stats, up bool) {
	header(b, "wikipg_queue_up", "gauge", "1 if the jobs table could be read for this scrape.")
	fmt.Fprintf(b, "wikipg_queue_up %d\n", boolInt(up))

	if up {
		header(b, "wikipg_jobs", "gauge", "Rows in the jobs table, by type and status.")
		for _, c := range st.Counts {
			fmt.Fprintf(b, "wikipg_jobs{type=%s,status=%s} %d\n", label(string(c.Type)), label(string(c.Status)), c.Jobs)
		}
		header(b, "wikipg_jobs_oldest_due_seconds", "gauge", "How long the longest-waiting due job has waited to be claimed.")
		fmt.Fprintf(b, "wikipg_jobs_oldest_due_seconds %s\n", number(st.OldestDue.Seconds()))
	}

	m.mu.Lock()
	defer m.mu.Unlock()

	header(b, "wikipg_job_attempts_total", "counter", "Job attempts finished by this process: ok, rejected, retry or failed.")
	keys := mapKeys(m.attempts, func(a, b attemptKey) int {
		return strings.Compare(string(a.Type)+"\x00"+a.Outcome, string(b.Type)+"\x00"+b.Outcome)
	})
	for _, k := range keys {
		fmt.Fprintf(b, "wikipg_job_attempts_total{type=%s,outcome=%s} %d\n", label(string(k.Type)), label(k.Outcome), m.attempts[k])
	}

	header(b, "wikipg_job_duration_seconds", "histogram", "How long each job attempt took, including recording its outcome.")
	for _, t := range mapKeys(m.duration, func(a, b queue.JobType) int { return strings.Compare(string(a), string(b)) }) {
		h := m.duration[t]
		var cumulative uint64
		for i, le := range durationBuckets {
			cumulative += h.counts[i]
			fmt.Fprintf(b, "wikipg_job_duration_seconds_bucket{type=%s,le=%s} %d\n", label(string(t)), label(number(le)), cumulative)
		}
		fmt.Fprintf(b, "wikipg_job_duration_seconds_bucket{type=%s,le=\"+Inf\"} %d\n", label(string(t)), h.count)
		fmt.Fprintf(b, "wikipg_job_duration_seconds_sum{type=%s} %s\n", label(string(t)), number(h.sum))
		fmt.Fprintf(b, "wikipg_job_duration_seconds_count{type=%s} %d\n", label(string(t)), h.count)
	}

	header(b, "wikipg_jobs_deleted_total", "counter", "Finished jobs deleted by this process's clean-up, by status.")
	for _, s := range []queue.Status{queue.StatusDone, queue.StatusFailed} {
		fmt.Fprintf(b, "wikipg_jobs_deleted_total{status=%s} %d\n", label(string(s)), m.deleted[s])
	}
}

func header(b *strings.Builder, name, kind, help string) {
	fmt.Fprintf(b, "# HELP %s %s\n# TYPE %s %s\n", name, help, name, kind)
}

// label quotes a label value. Job types are free text written by core, so the
// three characters the format reserves are escaped.
func label(v string) string {
	return `"` + strings.NewReplacer(`\`, `\\`, `"`, `\"`, "\n", `\n`).Replace(v) + `"`
}

func number(f float64) string { return strconv.FormatFloat(f, 'g', -1, 64) }

func boolInt(v bool) int {
	if v {
		return 1
	}
	return 0
}

// mapKeys returns a map's keys in a stable order, so the output does not
// reshuffle between scrapes.
func mapKeys[K comparable, V any](m map[K]V, cmp func(a, b K) int) []K {
	keys := make([]K, 0, len(m))
	for k := range m {
		keys = append(keys, k)
	}
	slices.SortFunc(keys, cmp)
	return keys
}
