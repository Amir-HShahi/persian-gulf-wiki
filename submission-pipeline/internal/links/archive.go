package links

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"
)

const archiveTimeout = 10 * time.Second

// archive finds the Wayback Machine's closest copy of a page. It goes through
// the same guarded client as the links, so a misconfigured endpoint cannot
// reach a private address either.
type archive struct {
	endpoint string
	client   *http.Client
}

// closest returns the archived copy's URL, or "" when there is none or the
// archive could not be asked. A missing copy is not an error: the reviewer
// still sees that the link is broken.
func (a *archive) closest(parent context.Context, link string) string {
	ctx, cancel := context.WithTimeout(parent, archiveTimeout)
	defer cancel()

	q := url.Values{"url": {pageOf(link)}}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, a.endpoint+"?"+q.Encode(), nil)
	if err != nil {
		return ""
	}
	req.Header.Set("User-Agent", userAgent)
	resp, err := a.client.Do(req)
	if err != nil {
		return ""
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return ""
	}

	var body struct {
		ArchivedSnapshots struct {
			Closest struct {
				Available bool   `json:"available"`
				URL       string `json:"url"`
				Status    string `json:"status"`
			} `json:"closest"`
		} `json:"archived_snapshots"`
	}
	if err := json.NewDecoder(io.LimitReader(resp.Body, 64<<10)).Decode(&body); err != nil {
		return ""
	}
	snap := body.ArchivedSnapshots.Closest
	// A snapshot of the error page itself is no help.
	if !snap.Available || !strings.HasPrefix(snap.Status, "2") {
		return ""
	}
	// The API answers with http:// links to a site that serves https.
	if rest, ok := strings.CutPrefix(snap.URL, "http://web.archive.org/"); ok {
		return "https://web.archive.org/" + rest
	}
	return snap.URL
}
