package handler

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"time"

	"github.com/jackc/pgx/v5"

	"wikipg/internal/links"
	"wikipg/internal/queue"
)

// LinksPayload names the article revision whose links to check.
type LinksPayload struct {
	RevisionID string `json:"revision_id"`
}

// maxLinks caps how many links one job checks. A well-sourced article has a
// few dozen; past this many, the job would run out of time before finishing,
// and a page of a thousand links is a spam signal for the reviewer anyway.
const maxLinks = 100

// linksMargin is kept back from the job's deadline, so a check that runs to the
// end still leaves time to record its result.
const linksMargin = 15 * time.Second

// LinkChecker is what the handler needs from links.Checker.
type LinkChecker interface {
	Check(ctx context.Context, links []string) []links.Link
}

// Links checks the external links in one article revision and reports the
// dead ones, with an archived copy where one exists. Like Normalize it reads
// core's tables and never changes the revision.
type Links struct {
	Checker LinkChecker
}

func (h *Links) Handle(ctx context.Context, log *slog.Logger, tx pgx.Tx, job queue.Job) (queue.Result, error) {
	var payload LinksPayload
	if err := json.Unmarshal(job.Payload, &payload); err != nil || !validUUID.MatchString(payload.RevisionID) {
		return reject("malformed_payload", nil), nil
	}
	log = log.With("revision_id", payload.RevisionID)

	var body []byte
	err := tx.QueryRow(ctx, `SELECT body FROM article_revisions WHERE id = $1`, payload.RevisionID).Scan(&body)
	if errors.Is(err, pgx.ErrNoRows) {
		return reject("revision_missing", nil), nil
	}
	if err != nil {
		return queue.Result{}, fmt.Errorf("reading revision %s: %w", payload.RevisionID, err)
	}

	found, err := links.Extract(body)
	if err != nil {
		// Core validated the body as JSON on the way in; Postgres stores jsonb,
		// so this cannot happen short of a bug, and retrying will not fix it.
		return reject("malformed_body", nil), nil
	}

	res := &queue.LinksResult{RevisionID: payload.RevisionID, Count: len(found)}
	if len(found) > maxLinks {
		res.LinksTruncated = len(found) - maxLinks
		found = found[:maxLinks]
	}

	checkCtx, cancel := ctx, context.CancelFunc(func() {})
	if deadline, ok := ctx.Deadline(); ok {
		checkCtx, cancel = context.WithDeadline(ctx, deadline.Add(-linksMargin))
	}
	res.Links = h.Checker.Check(checkCtx, found)
	cancel()
	if res.Links == nil {
		res.Links = []links.Link{}
	}

	statuses := map[links.Status]int{}
	for _, l := range res.Links {
		statuses[l.Status]++
	}
	res.Broken = statuses[links.StatusBroken] + statuses[links.StatusUnreachable]

	log.Info("links checked", "links", res.Count, "broken", res.Broken, "unchecked", statuses[links.StatusUnchecked])
	return queue.Result{Outcome: queue.OutcomeOK, Links: res}, nil
}
