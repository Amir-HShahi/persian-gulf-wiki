package handler

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"os"
	"path/filepath"
	"strconv"

	"github.com/jackc/pgx/v5"

	"wikipg/internal/queue"
	"wikipg/internal/storage"
	"wikipg/internal/tabular"
)

// TabularPayload is the job payload for a submitted CSV or XLSX file.
type TabularPayload struct {
	SubmissionID string `json:"submission_id"`
	ObjectKey    string `json:"object_key"`
}

// Tabular reports the structure of a submitted spreadsheet. A file with
// structural problems is not rejected: the report goes to a reviewer, who
// decides. Only a file that is not a spreadsheet at all, or cannot be read,
// is refused.
type Tabular struct {
	Store     *storage.Client
	RawBucket string
}

func (h *Tabular) Handle(ctx context.Context, log *slog.Logger, _ pgx.Tx, job queue.Job) (queue.Result, error) {
	var payload TabularPayload
	if err := json.Unmarshal(job.Payload, &payload); err != nil {
		return reject("malformed_payload", nil), nil
	}
	if !validSubmissionID.MatchString(payload.SubmissionID) || payload.ObjectKey == "" {
		return reject("malformed_payload", nil), nil
	}
	log = log.With("submission_id", payload.SubmissionID)

	dir, err := os.MkdirTemp("", "tabjob-")
	if err != nil {
		return queue.Result{}, fmt.Errorf("creating work dir: %w", err)
	}
	defer os.RemoveAll(dir)

	// A fixed name rather than one taken from the object key: nothing else is
	// written here, and the key never has to be trusted as a path.
	src := filepath.Join(dir, "source")
	if _, err := h.Store.Download(ctx, h.RawBucket, payload.ObjectKey, src); err != nil {
		var tooLarge *storage.TooLargeError
		switch {
		case errors.As(err, &tooLarge):
			return reject("file_too_large", map[string]string{
				"size": strconv.FormatInt(tooLarge.Size, 10),
				"max":  strconv.FormatInt(tooLarge.Limit, 10),
			}), nil
		case errors.Is(err, storage.ErrNotFound):
			return reject("object_missing", nil), nil
		}
		return queue.Result{}, fmt.Errorf("downloading %s: %w", payload.ObjectKey, err)
	}

	report, rejection, err := inspectTabular(src)
	if err != nil {
		return queue.Result{}, err
	}
	if rejection != nil {
		return queue.Result{Outcome: queue.OutcomeRejected, Reason: rejection}, nil
	}

	log.Info("spreadsheet inspected", "format", report.Format, "rows", report.Rows,
		"columns", report.Columns, "problems", len(report.Problems))
	return queue.Result{Outcome: queue.OutcomeOK, Tabular: &report}, nil
}

// inspectTabular decides the container from the file's bytes, never its name,
// and inspects it. A file that is neither CSV nor XLSX, or that the reader
// cannot get through, is a rejection: it is the same file on a retry. err is
// only for the local file itself failing to open.
func inspectTabular(path string) (tabular.Report, *queue.Reason, error) {
	f, err := os.Open(path)
	if err != nil {
		return tabular.Report{}, nil, fmt.Errorf("opening download: %w", err)
	}
	defer f.Close()

	head := make([]byte, 512)
	n, err := io.ReadFull(f, head)
	if err != nil && !errors.Is(err, io.EOF) && !errors.Is(err, io.ErrUnexpectedEOF) {
		return tabular.Report{}, nil, fmt.Errorf("reading download: %w", err)
	}
	head = head[:n]

	var report tabular.Report
	switch tabular.Sniff(head) {
	case tabular.FormatCSV:
		// The header bytes are already read, so they are put back in front of
		// the rest rather than reopening the file.
		report, err = tabular.InspectCSV(io.MultiReader(bytes.NewReader(head), f))
	case tabular.FormatXLSX:
		report, err = tabular.InspectXLSX(path)
	default:
		return tabular.Report{}, &queue.Reason{Code: "unsupported_format"}, nil
	}
	if err != nil {
		return tabular.Report{}, &queue.Reason{Code: "corrupt_file"}, nil
	}
	return report, nil, nil
}
