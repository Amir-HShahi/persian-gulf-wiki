// Package links finds the external links in an article body and checks
// whether each one still works, as Wikipedia's link bots do for citations.
//
// It never changes the article. A link that looks dead may be a site that is
// down for an hour, or one that refuses robots, so every result is for a
// reviewer to judge, with an archived copy to offer when one exists.
package links

import (
	"bytes"
	"encoding/json"
	"errors"
	"io"
	"net/url"
	"strings"
)

// Status is what checking one link found.
type Status string

const (
	// StatusOK: the page answered with a success.
	StatusOK Status = "ok"

	// StatusRedirected: the page works but has moved for good, a 301 or 308,
	// so FinalURL is the better link.
	StatusRedirected Status = "redirected"

	// StatusBroken: the page is gone, a 404 or 410, or its domain no longer
	// exists. This is the status that needs a reviewer.
	StatusBroken Status = "broken"

	// StatusUnreachable: no answer, or a server error. Often temporary.
	StatusUnreachable Status = "unreachable"

	// StatusRestricted: the site answered but refused us, a 401, 403 or 429.
	// Many sites refuse robots, so this says nothing about whether a reader
	// would see the page.
	StatusRestricted Status = "restricted"

	// StatusBlocked: the link points at a private address or an unusual port,
	// and was not requested. A public article has no reason to link there.
	StatusBlocked Status = "blocked"

	// StatusUnchecked: the job ran out of time before reaching it.
	StatusUnchecked Status = "unchecked"
)

// Link is one external link and what checking it found.
type Link struct {
	// URL is the link as written in the article.
	URL    string `json:"url"`
	Status Status `json:"status"`

	// HTTPStatus is the last status code the site answered with, when it did.
	HTTPStatus int `json:"http_status,omitempty"`

	// FinalURL is where a permanent redirect leads, for StatusRedirected.
	FinalURL string `json:"final_url,omitempty"`

	// Error says why a link that got no usable answer failed: dns_not_found,
	// timeout, connection_refused, tls, too_many_redirects, private_address,
	// port_not_allowed, time_limit or request_failed. An open list.
	Error string `json:"error,omitempty"`

	// ArchiveURL is the Wayback Machine's closest copy of a broken or
	// unreachable page, when it has one.
	ArchiveURL string `json:"archive_url,omitempty"`
}

// Extract returns the distinct http and https links in an article body, in the
// order they first appear.
//
// Core keeps bodies as free-form editor JSON with no fixed schema, so this
// reads the value of every "href" key at any depth, as search_body_text reads
// prose. Relative links, mailto: and the like are left out: they are not
// pages on another site. Two links that differ only by #fragment are one
// page, and are checked once.
func Extract(body []byte) ([]string, error) {
	dec := json.NewDecoder(bytes.NewReader(body))

	// One frame per open object or array. An object alternates key, value; the
	// frame remembers which comes next and the key the coming value is for.
	type frame struct {
		object    bool
		expectKey bool
		key       string
	}
	var (
		stack []frame
		seen  = map[string]bool{}
		out   []string
	)
	valueDone := func() {
		if n := len(stack); n > 0 && stack[n-1].object {
			stack[n-1].expectKey = true
		}
	}

	for {
		tok, err := dec.Token()
		if errors.Is(err, io.EOF) {
			if len(stack) > 0 {
				return nil, io.ErrUnexpectedEOF
			}
			return out, nil
		}
		if err != nil {
			return nil, err
		}

		switch v := tok.(type) {
		case json.Delim:
			switch v {
			case '{':
				stack = append(stack, frame{object: true, expectKey: true})
			case '[':
				stack = append(stack, frame{})
			default:
				stack = stack[:len(stack)-1]
				valueDone()
			}
		case string:
			n := len(stack)
			if n > 0 && stack[n-1].object && stack[n-1].expectKey {
				stack[n-1].key, stack[n-1].expectKey = v, false
				continue
			}
			if n > 0 && stack[n-1].object && stack[n-1].key == "href" {
				if link, ok := external(v); ok && !seen[pageOf(link)] {
					seen[pageOf(link)] = true
					out = append(out, link)
				}
			}
			valueDone()
		default:
			valueDone()
		}
	}
}

// external reports whether href is an absolute http or https link to another
// site, and returns it trimmed.
func external(href string) (string, bool) {
	href = strings.TrimSpace(href)
	u, err := url.Parse(href)
	if err != nil || u.Host == "" {
		return "", false
	}
	switch strings.ToLower(u.Scheme) {
	case "http", "https":
		return href, true
	}
	return "", false
}

// pageOf is the link without its #fragment, which the browser never sends.
func pageOf(link string) string {
	page, _, _ := strings.Cut(link, "#")
	return page
}
