package links

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"errors"
	"fmt"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"strings"
	"sync"
	"syscall"
	"time"
)

const (
	// linkTimeout bounds one link, every redirect and the GET retry included.
	linkTimeout = 10 * time.Second

	// maxRedirects is how many hops one link may take, as browsers allow
	// about twenty but a citation rarely needs more than two or three.
	maxRedirects = 5

	// concurrency is how many links one job checks at once, and perHost how
	// many of those may be on one site, so an article citing forty pages of
	// one archive does not hammer it.
	concurrency = 8
	perHost     = 2

	userAgent = "wikipg-linkcheck/1 (checks the sources cited in Persian Gulf Wiki articles)"
)

var (
	errPrivateAddress = errors.New("private_address")
	errPortNotAllowed = errors.New("port_not_allowed")
)

// Checker checks links. It is safe for concurrent use, and one is shared by
// every job.
type Checker struct {
	client  *http.Client
	archive *archive
}

// NewChecker returns a Checker. archiveURL is the Wayback Machine's
// availability API, asked for a copy of each broken page; empty skips it.
func NewChecker(archiveURL string) *Checker {
	return newChecker(archiveURL, publicOnly)
}

// newChecker takes the dial guard as a parameter so tests can reach their own
// server on 127.0.0.1, which the real guard refuses.
func newChecker(archiveURL string, guard func(network, address string, c syscall.RawConn) error) *Checker {
	dialer := &net.Dialer{Timeout: 5 * time.Second, Control: guard}
	transport := &http.Transport{
		// No proxy from the environment: a proxy would make the request on our
		// behalf, and the guard would only see the proxy's address.
		Proxy:                 nil,
		DialContext:           dialer.DialContext,
		TLSHandshakeTimeout:   5 * time.Second,
		ResponseHeaderTimeout: linkTimeout,
		MaxIdleConnsPerHost:   perHost,
		IdleConnTimeout:       30 * time.Second,
	}
	c := &Checker{client: &http.Client{
		Transport: transport,
		// Redirects are followed by hand, so each hop is seen and counted.
		CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
	}}
	if archiveURL != "" {
		c.archive = &archive{endpoint: archiveURL, client: c.client}
	}
	return c
}

// Check checks every link and returns the results in the same order. Links it
// had no time for, because ctx ended, come back as StatusUnchecked.
func (c *Checker) Check(ctx context.Context, links []string) []Link {
	out := make([]Link, len(links))
	slots := make(chan struct{}, concurrency)
	var (
		mu    sync.Mutex
		hosts = map[string]chan struct{}{}
		wg    sync.WaitGroup
	)
	hostSlots := func(host string) chan struct{} {
		mu.Lock()
		defer mu.Unlock()
		if hosts[host] == nil {
			hosts[host] = make(chan struct{}, perHost)
		}
		return hosts[host]
	}

	for i, link := range links {
		out[i] = Link{URL: link, Status: StatusUnchecked, Error: "time_limit"}
		u, err := url.Parse(link)
		if err != nil {
			continue // Extract only returns links that parse
		}
		host := hostSlots(strings.ToLower(u.Hostname()))

		wg.Add(1)
		go func() {
			defer wg.Done()
			if !acquire(ctx, slots) {
				return
			}
			defer func() { <-slots }()
			if !acquire(ctx, host) {
				return
			}
			defer func() { <-host }()

			res := c.checkOne(ctx, link)
			if (res.Status == StatusBroken || res.Status == StatusUnreachable) && c.archive != nil {
				res.ArchiveURL = c.archive.closest(ctx, link)
			}
			out[i] = res
		}()
	}
	wg.Wait()
	return out
}

func acquire(ctx context.Context, slot chan struct{}) bool {
	select {
	case slot <- struct{}{}:
		return true
	case <-ctx.Done():
		return false
	}
}

// checkOne follows link to its last hop and says what it found there.
func (c *Checker) checkOne(parent context.Context, link string) Link {
	ctx, cancel := context.WithTimeout(parent, linkTimeout)
	defer cancel()

	res := Link{URL: link}
	current := pageOf(link)
	// A link counts as moved only when every hop on the way was permanent: a
	// temporary hop means the site may send readers elsewhere tomorrow.
	permanent := true

	for hop := 0; ; hop++ {
		code, location, err := c.request(ctx, current)
		if err != nil {
			if parent.Err() != nil {
				return Link{URL: link, Status: StatusUnchecked, Error: "time_limit"}
			}
			res.Status, res.Error = classifyError(err)
			return res
		}
		res.HTTPStatus = code

		if location == "" {
			res.Status = classifyCode(code)
			if res.Status == StatusOK && hop > 0 && permanent {
				res.Status, res.FinalURL = StatusRedirected, current
			}
			return res
		}

		if hop == maxRedirects {
			res.Status, res.Error = StatusBroken, "too_many_redirects"
			return res
		}
		next, err := url.Parse(current)
		if err == nil {
			next, err = next.Parse(location)
		}
		if err != nil || (next.Scheme != "http" && next.Scheme != "https") {
			res.Status, res.Error = StatusBroken, "request_failed"
			return res
		}
		permanent = permanent && (code == http.StatusMovedPermanently || code == http.StatusPermanentRedirect)
		next.Fragment = ""
		current = next.String()
	}
}

// request asks for one URL and returns the status code, and the Location of a
// redirect. HEAD comes first, as it costs the site nothing; a site that
// answers HEAD with an error gets a GET too, because many mishandle HEAD.
func (c *Checker) request(ctx context.Context, target string) (int, string, error) {
	code, location, err := c.send(ctx, http.MethodHead, target)
	if err != nil || code < 400 {
		return code, location, err
	}
	return c.send(ctx, http.MethodGet, target)
}

func (c *Checker) send(ctx context.Context, method, target string) (int, string, error) {
	req, err := http.NewRequestWithContext(ctx, method, target, nil)
	if err != nil {
		return 0, "", err
	}
	req.Header.Set("User-Agent", userAgent)
	req.Header.Set("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")

	resp, err := c.client.Do(req)
	if err != nil {
		return 0, "", err
	}
	// The body is never read: the status is the answer. Closing it unread
	// drops the connection, which is cheaper than downloading a page.
	resp.Body.Close()

	location := ""
	if resp.StatusCode >= 300 && resp.StatusCode < 400 {
		location = resp.Header.Get("Location")
	}
	return resp.StatusCode, location, nil
}

func classifyCode(code int) Status {
	switch {
	case code < 300:
		return StatusOK
	case code < 400:
		// A redirect with no Location leads nowhere.
		return StatusBroken
	case code == http.StatusUnauthorized, code == http.StatusForbidden, code == http.StatusTooManyRequests:
		return StatusRestricted
	case code < 500:
		return StatusBroken
	default:
		return StatusUnreachable
	}
}

func classifyError(err error) (Status, string) {
	var (
		dnsErr  *net.DNSError
		certErr *tls.CertificateVerificationError
		unknown x509.UnknownAuthorityError
	)
	switch {
	case errors.Is(err, errPrivateAddress):
		return StatusBlocked, errPrivateAddress.Error()
	case errors.Is(err, errPortNotAllowed):
		return StatusBlocked, errPortNotAllowed.Error()
	case errors.As(err, &dnsErr) && dnsErr.IsNotFound:
		// The domain is gone: as dead as a 404.
		return StatusBroken, "dns_not_found"
	case errors.Is(err, context.DeadlineExceeded), isTimeout(err):
		return StatusUnreachable, "timeout"
	case errors.Is(err, syscall.ECONNREFUSED):
		return StatusUnreachable, "connection_refused"
	case errors.As(err, &certErr), errors.As(err, &unknown):
		return StatusUnreachable, "tls"
	}
	return StatusUnreachable, "request_failed"
}

func isTimeout(err error) bool {
	var ne net.Error
	return errors.As(err, &ne) && ne.Timeout()
}

// publicOnly is the dialer's guard. It runs after DNS, on the address actually
// being dialled, so a public name that resolves to a private address, or a
// redirect to one, is refused as surely as a link to 127.0.0.1. Article links
// are typed by contributors, and without this a link could make the worker
// request the database, object storage or a cloud metadata service.
func publicOnly(_, address string, _ syscall.RawConn) error {
	host, port, err := net.SplitHostPort(address)
	if err != nil {
		return err
	}
	if port != "80" && port != "443" {
		return errPortNotAllowed
	}
	ip, err := netip.ParseAddr(host)
	if err != nil {
		return fmt.Errorf("%w: %s", errPrivateAddress, host)
	}
	if !isPublic(ip) {
		return errPrivateAddress
	}
	return nil
}

// nonPublic are the ranges that IsPrivate and its siblings leave out but that
// still never belong to a public web server.
var nonPublic = []netip.Prefix{
	netip.MustParsePrefix("0.0.0.0/8"),     // "this network"
	netip.MustParsePrefix("100.64.0.0/10"), // carrier-grade NAT, and some clouds' internal networks
	netip.MustParsePrefix("192.0.0.0/24"),  // protocol assignments
	netip.MustParsePrefix("198.18.0.0/15"), // benchmarking
	netip.MustParsePrefix("240.0.0.0/4"),   // reserved
	netip.MustParsePrefix("64:ff9b::/96"),  // NAT64: carries an IPv4 address, which may be private
	netip.MustParsePrefix("2002::/16"),     // 6to4: the same
}

func isPublic(ip netip.Addr) bool {
	ip = ip.Unmap()
	if !ip.IsGlobalUnicast() || ip.IsPrivate() || ip.IsLoopback() || ip.IsLinkLocalUnicast() {
		return false
	}
	for _, p := range nonPublic {
		if p.Contains(ip) {
			return false
		}
	}
	return true
}
