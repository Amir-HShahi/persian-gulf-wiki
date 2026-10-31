package links

import (
	"context"
	"errors"
	"fmt"
	"net"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"slices"
	"sync/atomic"
	"syscall"
	"testing"
)

func TestExtractFindsEveryExternalHrefInOrder(t *testing.T) {
	body := `{"type":"doc","content":[
		{"type":"paragraph","content":[
			{"type":"text","text":"see","marks":[{"type":"link","attrs":{"href":"https://b.example/x"}}]},
			{"type":"text","text":"href","marks":[{"type":"link","attrs":{"href":" http://a.example/ "}}]}
		]},
		{"type":"figure","href":"https://b.example/x#part-2","items":[[{"href":"HTTPS://c.example"}]]},
		{"type":"text","text":"https://not-a-link.example"},
		{"href":"/articles/hormuz"}, {"href":"mailto:a@b.example"}, {"href":"https://"}, {"href":7}
	]}`
	got, err := Extract([]byte(body))
	if err != nil {
		t.Fatal(err)
	}
	want := []string{"https://b.example/x", "http://a.example/", "HTTPS://c.example"}
	if !slices.Equal(got, want) {
		t.Errorf("got %q, want %q", got, want)
	}
}

func TestExtractRejectsBrokenJSON(t *testing.T) {
	if _, err := Extract([]byte(`{"href":`)); err == nil {
		t.Error("broken JSON accepted")
	}
}

func TestOnlyPublicAddressesAreDialled(t *testing.T) {
	for addr, public := range map[string]bool{
		"8.8.8.8":          true,
		"2606:4700::1111":  true,
		"127.0.0.1":        false,
		"10.1.2.3":         false,
		"172.16.0.1":       false,
		"192.168.1.1":      false,
		"169.254.169.254":  false, // cloud metadata
		"100.64.0.1":       false,
		"0.0.0.0":          false,
		"::1":              false,
		"fd00::1":          false,
		"fe80::1":          false,
		"::ffff:127.0.0.1": false,
		"64:ff9b::7f00:1":  false,
		"2002:7f00:1::":    false,
		"224.0.0.1":        false,
		"255.255.255.255":  false,
	} {
		if got := isPublic(netip.MustParseAddr(addr)); got != public {
			t.Errorf("isPublic(%s) = %v, want %v", addr, got, public)
		}
	}

	if err := publicOnly("tcp", "8.8.8.8:22", nil); !errors.Is(err, errPortNotAllowed) {
		t.Errorf("port 22: %v", err)
	}
	if err := publicOnly("tcp", "[2606:4700::1111]:443", nil); err != nil {
		t.Errorf("public https: %v", err)
	}
}

// A link to the worker's own network is refused before any request is made.
func TestTheRealGuardRefusesALocalServer(t *testing.T) {
	var hits atomic.Int32
	srv := httptest.NewServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) { hits.Add(1) }))
	defer srv.Close()

	got := NewChecker("").Check(context.Background(), []string{srv.URL + "/"})
	if got[0].Status != StatusBlocked {
		t.Errorf("got %+v", got[0])
	}
	if hits.Load() != 0 {
		t.Error("the local server was reached")
	}
}

func allowAll(string, string, syscall.RawConn) error { return nil }

// site answers like the pages a citation can point at.
func site(t *testing.T) *httptest.Server {
	t.Helper()
	mux := http.NewServeMux()
	mux.HandleFunc("/ok", func(http.ResponseWriter, *http.Request) {})
	mux.HandleFunc("/gone", func(w http.ResponseWriter, _ *http.Request) { w.WriteHeader(http.StatusNotFound) })
	mux.HandleFunc("/forbidden", func(w http.ResponseWriter, _ *http.Request) { w.WriteHeader(http.StatusForbidden) })
	mux.HandleFunc("/down", func(w http.ResponseWriter, _ *http.Request) { w.WriteHeader(http.StatusBadGateway) })
	mux.HandleFunc("/moved", func(w http.ResponseWriter, r *http.Request) {
		http.Redirect(w, r, "/ok", http.StatusMovedPermanently)
	})
	mux.HandleFunc("/temporary", func(w http.ResponseWriter, r *http.Request) {
		http.Redirect(w, r, "/ok", http.StatusFound)
	})
	mux.HandleFunc("/moved-then-temporary", func(w http.ResponseWriter, r *http.Request) {
		http.Redirect(w, r, "/temporary", http.StatusPermanentRedirect)
	})
	mux.HandleFunc("/loop", func(w http.ResponseWriter, r *http.Request) {
		http.Redirect(w, r, "/loop", http.StatusMovedPermanently)
	})
	mux.HandleFunc("/to-ftp", func(w http.ResponseWriter, r *http.Request) {
		http.Redirect(w, r, "ftp://files.example/", http.StatusMovedPermanently)
	})
	mux.HandleFunc("/no-head", func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodHead {
			w.WriteHeader(http.StatusMethodNotAllowed)
		}
	})
	mux.HandleFunc("/user-agent", func(w http.ResponseWriter, r *http.Request) {
		if r.UserAgent() != userAgent {
			w.WriteHeader(http.StatusTeapot)
		}
	})
	srv := httptest.NewServer(mux)
	t.Cleanup(srv.Close)
	return srv
}

func TestCheckSaysWhatEachLinkLeadsTo(t *testing.T) {
	srv := site(t)
	c := newChecker("", allowAll)

	cases := []struct {
		path   string
		status Status
		code   int
		final  string
		err    string
	}{
		{"/ok", StatusOK, 200, "", ""},
		{"/ok#section", StatusOK, 200, "", ""},
		{"/gone", StatusBroken, 404, "", ""},
		{"/forbidden", StatusRestricted, 403, "", ""},
		{"/down", StatusUnreachable, 502, "", ""},
		{"/moved", StatusRedirected, 200, "/ok", ""},
		{"/temporary", StatusOK, 200, "", ""},
		{"/moved-then-temporary", StatusOK, 200, "", ""},
		{"/loop", StatusBroken, 301, "", "too_many_redirects"},
		{"/to-ftp", StatusBroken, 301, "", "request_failed"},
		{"/no-head", StatusOK, 200, "", ""},
		{"/user-agent", StatusOK, 200, "", ""},
	}
	urls := make([]string, len(cases))
	for i, tc := range cases {
		urls[i] = srv.URL + tc.path
	}
	got := c.Check(context.Background(), urls)

	for i, tc := range cases {
		want := Link{URL: urls[i], Status: tc.status, HTTPStatus: tc.code, Error: tc.err}
		if tc.final != "" {
			want.FinalURL = srv.URL + tc.final
		}
		if got[i] != want {
			t.Errorf("%s:\n got %+v\nwant %+v", tc.path, got[i], want)
		}
	}
}

func TestBrokenLinksGetTheirArchivedCopy(t *testing.T) {
	srv := site(t)
	var asked []string
	archived := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		asked = append(asked, r.URL.Query().Get("url"))
		fmt.Fprintf(w, `{"archived_snapshots":{"closest":{"available":true,"status":"200",
			"url":"http://web.archive.org/web/2019/%s"}}}`, r.URL.Query().Get("url"))
	}))
	defer archived.Close()

	c := newChecker(archived.URL, allowAll)
	got := c.Check(context.Background(), []string{srv.URL + "/ok", srv.URL + "/gone#refs"})

	if got[0].ArchiveURL != "" {
		t.Errorf("a working link got an archive copy: %+v", got[0])
	}
	if want := "https://web.archive.org/web/2019/" + srv.URL + "/gone"; got[1].ArchiveURL != want {
		t.Errorf("archive url %q, want %q", got[1].ArchiveURL, want)
	}
	if !slices.Equal(asked, []string{srv.URL + "/gone"}) {
		t.Errorf("archive asked about %q", asked)
	}
}

func TestAnArchivedErrorPageIsNoCopy(t *testing.T) {
	srv := site(t)
	archived := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		fmt.Fprint(w, `{"archived_snapshots":{"closest":{"available":true,"status":"404","url":"http://web.archive.org/x"}}}`)
	}))
	defer archived.Close()

	got := newChecker(archived.URL, allowAll).Check(context.Background(), []string{srv.URL + "/gone"})
	if got[0].ArchiveURL != "" {
		t.Errorf("got %+v", got[0])
	}
}

func TestLinksLeftWhenTimeRunsOutAreUnchecked(t *testing.T) {
	srv := site(t)
	ctx, cancel := context.WithCancel(context.Background())
	cancel()

	got := newChecker("", allowAll).Check(ctx, []string{srv.URL + "/ok", srv.URL + "/gone"})
	for _, l := range got {
		if l.Status != StatusUnchecked || l.Error != "time_limit" {
			t.Errorf("got %+v", l)
		}
	}
}

func TestErrorsAreNamed(t *testing.T) {
	for _, tc := range []struct {
		err    error
		status Status
		name   string
	}{
		{&net.DNSError{Err: "no such host", Name: "gone.example", IsNotFound: true}, StatusBroken, "dns_not_found"},
		{&net.DNSError{Err: "server misbehaving", Name: "x.example", IsTemporary: true}, StatusUnreachable, "request_failed"},
		{fmt.Errorf("dial: %w", errPrivateAddress), StatusBlocked, "private_address"},
		{context.DeadlineExceeded, StatusUnreachable, "timeout"},
	} {
		if s, name := classifyError(tc.err); s != tc.status || name != tc.name {
			t.Errorf("%v: got %s %s, want %s %s", tc.err, s, name, tc.status, tc.name)
		}
	}
}
