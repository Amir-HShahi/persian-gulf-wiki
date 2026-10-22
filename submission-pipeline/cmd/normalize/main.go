// Command normalize reports what the normaliser would change in a text file,
// without changing it. It exists so a rule can be checked against a real
// article from the shell, rather than by enqueuing a job and reading the
// worker's logs.
package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"os"

	"wikipg/internal/normalize"
)

func main() {
	if err := run(); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}

func run() error {
	in := flag.String("in", "-", `input file, or "-" for stdin`)
	locale := flag.String("locale", string(normalize.LocaleFA), "locale: fa, ar or en")
	flag.Parse()

	loc, err := parseLocale(*locale)
	if err != nil {
		return err
	}

	text, err := read(*in)
	if err != nil {
		return err
	}

	// The CLI holds no logic of its own: it calls the same function the worker
	// calls, so a finding seen here is exactly what a submission would produce.
	// protected is nil because the shell has no way to say which ranges are
	// quotes; the worker passes real ranges.
	findings := normalize.Report(string(text), loc, nil)

	// Findings are data, not failure — a file with a hundred of them still
	// exits zero, so the command composes with jq and test scripts.
	out := struct {
		Locale   normalize.Locale    `json:"locale"`
		Count    int                 `json:"count"`
		Findings []normalize.Finding `json:"findings"`
	}{loc, len(findings), findings}

	enc := json.NewEncoder(os.Stdout)
	enc.SetIndent("", "  ")
	return enc.Encode(out)
}

func parseLocale(s string) (normalize.Locale, error) {
	switch normalize.Locale(s) {
	case normalize.LocaleFA:
		return normalize.LocaleFA, nil
	case normalize.LocaleAR:
		return normalize.LocaleAR, nil
	case normalize.LocaleEN:
		return normalize.LocaleEN, nil
	}
	return "", fmt.Errorf("unknown locale %q: want fa, ar or en", s)
}

// read returns the whole input. These are articles, not archives, so reading
// it all is simpler than streaming and costs nothing at this size.
func read(path string) ([]byte, error) {
	if path == "-" {
		return io.ReadAll(os.Stdin)
	}
	return os.ReadFile(path)
}
