package handler

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"os"
	"path"
	"path/filepath"
	"regexp"
	"strconv"

	"github.com/jackc/pgx/v5"

	"wikipg/internal/imagepipe"
	"wikipg/internal/queue"
	"wikipg/internal/storage"
)

// ImagePayload is the job payload core enqueues for an image submission.
//
// DeclaredSHA256 is the hash the uploader declared, as standard padded base64.
// When it is set, the file is refused unless its bytes hash to it. When it is
// empty the file is not checked, but its hash is still reported.
type ImagePayload struct {
	SubmissionID   string `json:"submission_id"`
	ObjectKey      string `json:"object_key"`
	DeclaredSHA256 string `json:"declared_sha256,omitempty"`
}

// validSubmissionID is what a submission id may look like. It becomes a prefix
// in the derived bucket, so anything that could climb out of it — a slash, a
// dot-dot — has to be refused rather than escaped.
var validSubmissionID = regexp.MustCompile(`^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$`)

// Where things go in the derived bucket. Variants go under core's
// pending/{mediaId}/ prefix: core accepts no variant key outside it, moves the
// prefix to public/ when a moderator approves the item, and deletes it when the
// item is rejected or removed. The original goes under a prefix of its own, so
// that clean-up never takes it with the variants.
const (
	variantPrefix  = "pending"
	originalPrefix = "originals"
)

func variantKey(submissionID, file string) string {
	return path.Join(variantPrefix, submissionID, file)
}

func originalKey(submissionID, ext string) string {
	return path.Join(originalPrefix, submissionID, "original."+ext)
}

// derivedContentType and derivedFormat are what every variant is stored as and
// reported as. imagepipe only ever writes WebP, so this is a fact about that
// package rather than a choice made here; if it learns another output format
// these have to follow the file. Core refuses a variant without a format.
const (
	derivedContentType = "image/webp"
	derivedFormat      = "webp"
)

type Image struct {
	Store         *storage.Client
	RawBucket     string
	DerivedBucket string
	Options       imagepipe.Options
}

func (h *Image) Handle(ctx context.Context, log *slog.Logger, _ pgx.Tx, job queue.Job) (queue.Result, error) {
	var payload ImagePayload
	if err := json.Unmarshal(job.Payload, &payload); err != nil {
		// Malformed payloads never become valid on a retry, so this is
		// reported as a rejection rather than an error.
		return reject("malformed_payload", nil), nil
	}
	if !validSubmissionID.MatchString(payload.SubmissionID) || payload.ObjectKey == "" {
		return reject("malformed_payload", nil), nil
	}
	if payload.DeclaredSHA256 != "" && !validDeclaredSHA256(payload.DeclaredSHA256) {
		return reject("malformed_payload", nil), nil
	}

	log = log.With("submission_id", payload.SubmissionID)

	dir, err := os.MkdirTemp("", "imgjob-")
	if err != nil {
		return queue.Result{}, fmt.Errorf("creating work dir: %w", err)
	}
	defer os.RemoveAll(dir)

	// Variants go in their own directory. The source is named after the object
	// key, which the uploader influences, and a key such as "w400.webp" must not
	// be able to collide with — or be overwritten by — an output file.
	outDir := filepath.Join(dir, "out")
	if err := os.Mkdir(outDir, 0o755); err != nil {
		return queue.Result{}, fmt.Errorf("creating output dir: %w", err)
	}

	// filepath.Base strips any directory part: the object key arrives in the
	// job payload, and a key like "../../etc/passwd" must not escape the
	// temp dir.
	src := filepath.Join(dir, filepath.Base(payload.ObjectKey))

	size, err := h.Store.Download(ctx, h.RawBucket, payload.ObjectKey, src)
	if err != nil {
		var tooLarge *storage.TooLargeError
		switch {
		case errors.As(err, &tooLarge):
			return reject("file_too_large", map[string]string{
				"size":  strconv.FormatInt(tooLarge.Size, 10),
				"limit": strconv.FormatInt(tooLarge.Limit, 10),
			}), nil
		case errors.Is(err, storage.ErrNotFound):
			return reject("object_missing", nil), nil
		}
		// Anything else is infrastructure: retry it.
		return queue.Result{}, fmt.Errorf("downloading %s: %w", payload.ObjectKey, err)
	}

	log.Debug("source downloaded", "bytes", size)

	// Checked before anything decodes the file: one that is not what the
	// uploader sent never reaches libvips. The hash is reported either way,
	// because core stores it and finds duplicate uploads by it.
	sum, err := sha256Base64(src)
	if err != nil {
		return queue.Result{}, fmt.Errorf("hashing %s: %w", payload.ObjectKey, err)
	}
	if payload.DeclaredSHA256 != "" && sum != payload.DeclaredSHA256 {
		return reject("checksum_mismatch", map[string]string{
			"declared": payload.DeclaredSHA256,
			"actual":   sum,
		}), nil
	}

	processed, err := imagepipe.Process(ctx, src, outDir, h.Options)
	if err != nil {
		// A Rejection is a verdict on the file — unreadable, too many pixels —
		// and is identical on a retry, so it ends the job. Everything else is
		// the worker's own trouble and is retried.
		var rejection *imagepipe.Rejection
		if errors.As(err, &rejection) {
			return reject(rejection.Code, rejection.Detail), nil
		}
		return queue.Result{}, fmt.Errorf("processing image: %w", err)
	}

	// The original is copied next to the variants byte for byte, so it survives
	// whatever the raw bucket's own lifecycle does to the upload. It is saved
	// before the variants: a failure here retries the whole job, rather than
	// leaving a submission with pictures and no original behind them.
	ext, contentType, err := originalOf(src, processed.Source.Format)
	if err != nil {
		return queue.Result{}, fmt.Errorf("reading original: %w", err)
	}
	origKey := originalKey(payload.SubmissionID, ext)
	originalBytes, err := h.Store.Upload(ctx, h.DerivedBucket, origKey, src, contentType)
	if err != nil {
		return queue.Result{}, fmt.Errorf("saving original %s: %w", origKey, err)
	}

	variants := make([]queue.Variant, 0, len(processed.Variants))
	for _, v := range processed.Variants {
		// The key is rebuilt from the submission id and the file name rather than
		// taken from the local path, so where the temp dir happens to be never
		// leaks into the bucket. Overwriting on a retry is deliberate: the same
		// input yields the same keys, which makes the upload idempotent.
		key := variantKey(payload.SubmissionID, filepath.Base(v.Path))

		uploaded, err := h.Store.Upload(ctx, h.DerivedBucket, key, v.Path, derivedContentType)
		if err != nil {
			return queue.Result{}, fmt.Errorf("uploading %s: %w", key, err)
		}

		variants = append(variants, queue.Variant{
			Label:  v.Label,
			Format: derivedFormat,
			Key:    key,
			Width:  v.Width,
			Height: v.Height,
			Bytes:  uploaded,
		})
	}

	log.Info("image processed", "variants", len(variants), "original", origKey,
		"width", processed.Source.Width, "height", processed.Source.Height)

	return queue.Result{
		Outcome: queue.OutcomeOK,
		Image: &queue.ImageResult{
			Width:       processed.Source.Width,
			Height:      processed.Source.Height,
			Format:      processed.Source.Format,
			SHA256:      sum,
			Placeholder: processed.Placeholder,
			Variants:    variants,
			Original: &queue.Original{
				Key:         origKey,
				Bytes:       originalBytes,
				ContentType: contentType,
			},
		},
	}, nil
}

// reject builds a terminal result: the pipeline worked, the content is not
// acceptable. Never retried.
func reject(code string, detail map[string]string) queue.Result {
	return queue.Result{
		Outcome: queue.OutcomeRejected,
		Reason:  &queue.Reason{Code: code, Detail: detail},
	}
}
