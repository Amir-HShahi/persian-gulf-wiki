// Package queue defines the Postgres-backed job contract shared with the
// Spring side, and runs the worker loop that consumes it.
//
// The worker applies the jobs table itself (schema.sql, through
// internal/install); core only inserts rows and reads results.
package queue

import (
	"encoding/json"
	"time"

	"wikipg/internal/normalize"
	"wikipg/internal/tabular"
)

// JobType selects which handler processes a job.
type JobType string

const (
	JobTypeImage     JobType = "image"
	JobTypePanorama  JobType = "panorama"
	JobTypeTabular   JobType = "tabular"
	JobTypeNormalize JobType = "normalize"
	JobTypeGeo       JobType = "geo"
)

// Status is the lifecycle state of a job row, and is distinct from the
// Outcome of the work itself. A submission that is rejected produces a done
// row carrying an OutcomeRejected result — the pipeline ran correctly and the
// answer was no. Only the worker malfunctioning produces StatusFailed.
type Status string

const (
	StatusPending Status = "pending"
	StatusRunning Status = "running"
	StatusDone    Status = "done"
	StatusFailed  Status = "failed"
)

// Outcome is the result of the work. The split between OutcomeRejected and
// OutcomeFailed is what drives retry behaviour: rejected is terminal, failed
// is retryable.
type Outcome string

const (
	// OutcomeOK — the job did its work successfully.
	OutcomeOK Outcome = "ok"

	// OutcomeRejected — the pipeline worked correctly and the answer is no:
	// the submitted content is unacceptable. Never retried; the contributor
	// should be shown why.
	OutcomeRejected Outcome = "rejected"

	// OutcomeFailed — the worker itself malfunctioned (storage unreachable,
	// disk full, timeout). Retryable; the contributor sees nothing.
	OutcomeFailed Outcome = "failed"
)

// Job is one row of the jobs table, as claimed by this worker.
type Job struct {
	ID          int64
	Type        JobType
	Payload     json.RawMessage
	Attempts    int
	MaxAttempts int
	RunAfter    time.Time
	CreatedAt   time.Time
}

// LogAttrs returns this job's correlation fields for slog.Logger.With, so
// every line written about the job carries them. Handlers add submission_id
// once they have parsed their own payload — the queue deliberately does not
// parse it twice just to log one field.
func (j Job) LogAttrs() []any {
	return []any{
		"job_id", j.ID,
		"type", string(j.Type),
		"attempt", j.Attempts,
	}
}

// Result is what the worker records for one job. At most one of the typed
// payloads is set, matching Type. It is stored as JSONB on the job row, which
// is where core reads it from.
type Result struct {
	Version    int       `json:"version"`
	JobID      int64     `json:"job_id"`
	Type       JobType   `json:"type"`
	Outcome    Outcome   `json:"outcome"`
	Attempt    int       `json:"attempt"`
	DurationMS int64     `json:"duration_ms"`
	FinishedAt time.Time `json:"finished_at"`

	// Reason is set when Outcome is OutcomeRejected.
	Reason *Reason `json:"reason,omitempty"`

	// Error is set when Outcome is OutcomeFailed. Operator-facing only — it may
	// contain internal detail and must not be shown to contributors.
	Error string `json:"error,omitempty"`

	Tabular   *tabular.Report  `json:"tabular,omitempty"`
	Normalize *NormalizeResult `json:"normalize,omitempty"`
	Image     *ImageResult     `json:"image,omitempty"`
	Geo       *GeoResult       `json:"geo,omitempty"`
}

// Reason explains a rejection in a form the frontend can localise. Code is a
// stable identifier; Detail carries the values that vary per occurrence, for
// interpolation into a translated message.
type Reason struct {
	Code   string            `json:"code"`
	Detail map[string]string `json:"detail,omitempty"`
}

// NormalizeResult is what a normalize job found in one revision. Count is
// every finding; Findings holds the first of them, and FindingsTruncated says
// how many were left out to keep the stored result small.
type NormalizeResult struct {
	RevisionID        string              `json:"revision_id"`
	Locale            string              `json:"locale"`
	Count             int                 `json:"count"`
	Findings          []normalize.Finding `json:"findings"`
	FindingsTruncated int                 `json:"findings_truncated,omitempty"`
}

type ImageResult struct {
	Width    int       `json:"width"`
	Height   int       `json:"height"`
	Format   string    `json:"format"`
	Variants []Variant `json:"variants"`

	// SHA256 is the hash of the uploaded bytes, as standard padded base64.
	// Computed by the worker, so unlike the declared one it can be trusted.
	SHA256 string `json:"sha256"`

	// Placeholder is a tiny preview of the image as a data:image/webp URI, at
	// most 16 KiB, to show while a variant loads. Empty when none was made.
	Placeholder string `json:"placeholder,omitempty"`

	// Original is the untouched upload, saved beside the variants so it outlives
	// whatever the raw bucket's own lifecycle does to the staging copy.
	//
	// It is not a variant and must not be served as one: it is byte for byte what
	// the camera wrote, including any location data, and may be a format a
	// browser cannot show.
	Original *Original `json:"original,omitempty"`
}

// Original is where the saved copy of an upload lives.
type Original struct {
	Key         string `json:"key"`
	Bytes       int64  `json:"bytes"`
	ContentType string `json:"content_type"`
}

type Variant struct {
	Label  string `json:"label"`
	Format string `json:"format"`
	Key    string `json:"key"`
	Width  int    `json:"width"`
	Height int    `json:"height"`
	Bytes  int64  `json:"bytes"`
}

type GeoResult struct {
	FeatureCount int    `json:"feature_count"`
	SourceCRS    string `json:"source_crs"`
	OutputKey    string `json:"output_key"`
}
