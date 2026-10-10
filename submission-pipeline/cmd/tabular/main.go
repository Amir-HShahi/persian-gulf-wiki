// Command tabular reports the structural faults in a CSV or XLSX file. It is
// the same inspection the worker runs on a submission, reachable from the
// shell so a contributor's file can be checked without uploading it.
package main

import (
	"bytes"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"os"

	"wikipg/internal/tabular"
)

func main() {
	if err := run(); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}

func run() error {
	in := flag.String("in", "", "input .csv or .xlsx file")
	flag.Parse()

	if *in == "" {
		return fmt.Errorf("-in is required")
	}

	f, err := os.Open(*in)
	if err != nil {
		return err
	}
	defer f.Close()

	// The container is decided from the bytes, never the extension: a file
	// named .csv holding an XLSX export is routine once somebody renames one.
	head := make([]byte, 512)
	n, err := io.ReadFull(f, head)
	if err != nil && err != io.EOF && err != io.ErrUnexpectedEOF {
		return fmt.Errorf("reading header of %s: %w", *in, err)
	}
	head = head[:n]

	var report tabular.Report
	switch format := tabular.Sniff(head); format {
	case tabular.FormatCSV:
		// InspectCSV takes a reader and the header bytes are already consumed,
		// so the two are stitched back together rather than reopening the file.
		report, err = tabular.InspectCSV(io.MultiReader(bytes.NewReader(head), f))

	case tabular.FormatXLSX:
		// XLSX is a zip: the reader has to seek, so it is handed the path and
		// opens the file itself.
		report, err = tabular.InspectXLSX(*in)

	default:
		return fmt.Errorf("%s is neither CSV nor XLSX", *in)
	}
	if err != nil {
		return fmt.Errorf("inspecting %s: %w", *in, err)
	}

	// A file with problems is not a failed run — the report is the output, and
	// a human decides. Exit stays zero so this pipes into jq.
	enc := json.NewEncoder(os.Stdout)
	enc.SetIndent("", "  ")
	return enc.Encode(report)
}
