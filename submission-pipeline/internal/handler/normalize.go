package handler

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"regexp"
	"strings"

	"github.com/jackc/pgx/v5"

	"wikipg/internal/normalize"
	"wikipg/internal/queue"
)

// NormalizePayload names the article revision to check.
type NormalizePayload struct {
	RevisionID string `json:"revision_id"`
}

// maxFindings caps what one result stores. A revision pasted from a source
// typed on an Arabic keyboard can have a finding in every word; past a few
// hundred, more of them tell a reviewer nothing new.
const maxFindings = 500

var validUUID = regexp.MustCompile(`^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$`)

// Normalize reports the text problems in one article revision: Arabic letter
// forms in a Persian article, stray invisible characters, mixed digits and the
// like. It never changes the revision; every finding is a suggestion for the
// reviewer, who may know that a spelling is a deliberate quotation.
//
// It reads core's tables, as search does, so the payload only has to name the
// revision. The text checked is the title, the summary and the body's prose,
// taken from the body by search_body_text: exactly what search indexes.
type Normalize struct{}

func (h *Normalize) Handle(ctx context.Context, log *slog.Logger, tx pgx.Tx, job queue.Job) (queue.Result, error) {
	var payload NormalizePayload
	if err := json.Unmarshal(job.Payload, &payload); err != nil || !validUUID.MatchString(payload.RevisionID) {
		return reject("malformed_payload", nil), nil
	}
	log = log.With("revision_id", payload.RevisionID)

	var language, text string
	err := tx.QueryRow(ctx, `
		SELECT t.language,
		       concat_ws(E'\n', r.title, nullif(r.summary, ''), nullif(search_body_text(r.body), ''))
		  FROM article_revisions r
		  JOIN article_translations t ON t.id = r.translation_id
		 WHERE r.id = $1`, payload.RevisionID).Scan(&language, &text)
	if errors.Is(err, pgx.ErrNoRows) {
		return reject("revision_missing", nil), nil
	}
	if err != nil {
		// Including search_body_text not being installed yet: retried.
		return queue.Result{}, fmt.Errorf("reading revision %s: %w", payload.RevisionID, err)
	}

	loc, ok := localeOf(language)
	if !ok {
		return reject("unsupported_locale", map[string]string{"language": language}), nil
	}

	findings := normalize.Report(text, loc, nil)
	res := &queue.NormalizeResult{
		RevisionID: payload.RevisionID,
		Locale:     string(loc),
		Count:      len(findings),
		Findings:   findings,
	}
	if len(findings) > maxFindings {
		res.Findings = findings[:maxFindings]
		res.FindingsTruncated = len(findings) - maxFindings
	}
	if res.Findings == nil {
		res.Findings = []normalize.Finding{}
	}

	log.Info("revision checked", "locale", loc, "findings", len(findings))
	return queue.Result{Outcome: queue.OutcomeOK, Normalize: res}, nil
}

// localeOf maps a translation's BCP-47 language tag to a rule set. A region
// does not change the rules, so fa-IR is fa.
func localeOf(language string) (normalize.Locale, bool) {
	base, _, _ := strings.Cut(strings.ToLower(language), "-")
	switch normalize.Locale(base) {
	case normalize.LocaleFA, normalize.LocaleAR, normalize.LocaleEN:
		return normalize.Locale(base), true
	}
	return "", false
}
