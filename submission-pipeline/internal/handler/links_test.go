package handler

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"slices"
	"strings"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"wikipg/internal/links"
	"wikipg/internal/queue"
)

// fakeChecker answers from a table instead of the network, and remembers what
// it was asked and how long it was given.
type fakeChecker struct {
	answers  map[string]links.Status
	asked    []string
	deadline time.Time
}

func (f *fakeChecker) Check(ctx context.Context, urls []string) []links.Link {
	f.asked = urls
	f.deadline, _ = ctx.Deadline()
	out := make([]links.Link, len(urls))
	for i, u := range urls {
		out[i] = links.Link{URL: u, Status: f.answers[u]}
	}
	return out
}

func runLinks(t *testing.T, ctx context.Context, pool *pgxpool.Pool, checker LinkChecker, payload string) queue.Result {
	t.Helper()
	tx, err := pool.Begin(ctx)
	if err != nil {
		t.Fatal(err)
	}
	defer tx.Rollback(context.Background())

	res, err := (&Links{Checker: checker}).Handle(ctx, slog.New(slog.NewTextHandler(io.Discard, nil)), tx,
		queue.Job{Type: queue.JobTypeLinks, Payload: json.RawMessage(payload)})
	if err != nil {
		t.Fatal(err)
	}
	return res
}

func TestLinksChecksEveryExternalLinkInARevision(t *testing.T) {
	pool := normalizePool(t)
	id := revision(t, pool, "fa", "کیش", `{"type":"doc","content":[{"type":"paragraph","content":[
		{"type":"text","text":"منبع","marks":[{"type":"link","attrs":{"href":"https://ok.example/a"}}]},
		{"type":"text","text":"دیگر","marks":[{"type":"link","attrs":{"href":"https://gone.example/b"}}]},
		{"type":"text","text":"داخلی","marks":[{"type":"link","attrs":{"href":"/articles/hormuz"}}]},
		{"type":"text","text":"کند","marks":[{"type":"link","attrs":{"href":"https://slow.example/"}}]}]}]}`)

	checker := &fakeChecker{answers: map[string]links.Status{
		"https://ok.example/a":   links.StatusOK,
		"https://gone.example/b": links.StatusBroken,
		"https://slow.example/":  links.StatusUnreachable,
	}}
	ctx, cancel := context.WithTimeout(context.Background(), time.Minute)
	defer cancel()
	res := runLinks(t, ctx, pool, checker, `{"revision_id":"`+id+`"}`)

	if res.Outcome != queue.OutcomeOK || res.Links == nil {
		t.Fatalf("result = %+v", res)
	}
	l := res.Links
	if l.RevisionID != id || l.Count != 3 || l.Broken != 2 || len(l.Links) != 3 || l.LinksTruncated != 0 {
		t.Errorf("links result = %+v", l)
	}
	if want := []string{"https://ok.example/a", "https://gone.example/b", "https://slow.example/"}; !slices.Equal(checker.asked, want) {
		t.Errorf("checked %q, want %q", checker.asked, want)
	}
	// Time is kept back for recording the result.
	jobDeadline, _ := ctx.Deadline()
	if gap := jobDeadline.Sub(checker.deadline); gap != linksMargin {
		t.Errorf("checker deadline is %s before the job's, want %s", gap, linksMargin)
	}
}

func TestARevisionWithoutLinksHasAnEmptyList(t *testing.T) {
	pool := normalizePool(t)
	id := revision(t, pool, "en", "Kish", `{"type":"doc","content":[{"type":"paragraph","text":"no sources"}]}`)

	res := runLinks(t, context.Background(), pool, &fakeChecker{}, `{"revision_id":"`+id+`"}`)
	encoded, _ := json.Marshal(res.Links)
	if !strings.Contains(string(encoded), `"links":[]`) || res.Links.Count != 0 {
		t.Errorf("result = %s", encoded)
	}
}

func TestLinksPastTheCapAreCountedButNotChecked(t *testing.T) {
	pool := normalizePool(t)
	var marks []string
	for i := range maxLinks + 7 {
		marks = append(marks, fmt.Sprintf(`{"type":"link","attrs":{"href":"https://site.example/%d"}}`, i))
	}
	id := revision(t, pool, "en", "Spam", `{"type":"doc","content":[{"type":"text","text":"x","marks":[`+strings.Join(marks, ",")+`]}]}`)

	checker := &fakeChecker{}
	res := runLinks(t, context.Background(), pool, checker, `{"revision_id":"`+id+`"}`)
	if res.Links.Count != maxLinks+7 || res.Links.LinksTruncated != 7 || len(checker.asked) != maxLinks {
		t.Errorf("count %d, truncated %d, checked %d", res.Links.Count, res.Links.LinksTruncated, len(checker.asked))
	}
}

func TestLinksRejectsWhatItCannotCheck(t *testing.T) {
	pool := normalizePool(t)
	for payload, code := range map[string]string{
		`{"revision_id":"not-a-uuid"}`: "malformed_payload",
		`{}`:                           "malformed_payload",
		`{"revision_id":"6b0f3c6e-6c35-4a8e-9a43-2a4cfa8f4f8e"}`: "revision_missing",
	} {
		res := runLinks(t, context.Background(), pool, &fakeChecker{}, payload)
		if res.Outcome != queue.OutcomeRejected || res.Reason == nil || res.Reason.Code != code {
			t.Errorf("%s: result = %+v", payload, res)
		}
	}
}
