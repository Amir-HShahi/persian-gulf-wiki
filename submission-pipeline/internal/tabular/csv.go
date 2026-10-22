package tabular

import (
	"bufio"
	"bytes"
	"encoding/csv"
	"errors"
	"fmt"
	"io"
)

// peekBytes is how far ahead the delimiter sniffer looks for the header line.
const peekBytes = 8 << 10

// utf8BOM is emitted by Excel at the start of UTF-8 CSV exports. Left in place
// it becomes an invisible prefix on the first column name.
var utf8BOM = []byte{0xEF, 0xBB, 0xBF}

// candidates are the separators worth considering, in preference order. A
// comma wins ties because it is the default everywhere.
var candidates = []rune{',', ';', '\t', '|'}

// InspectCSV reads delimited text and reports its structure.
//
// It streams: one row is held at a time, so a two-million-row file costs the
// same memory as a twenty-row one.
func InspectCSV(r io.Reader) (Report, error) {
	br := bufio.NewReaderSize(r, peekBytes)

	if head, err := br.Peek(len(utf8BOM)); err == nil && bytes.Equal(head, utf8BOM) {
		_, _ = br.Discard(len(utf8BOM))
	}

	comma, err := sniffDelimiter(br)
	if err != nil {
		return Report{}, err
	}

	cr := csv.NewReader(br)
	cr.Comma = comma
	// Rows of differing length are reported as findings rather than aborting
	// the read, so one ragged row cannot hide every other problem in the file.
	cr.FieldsPerRecord = -1
	cr.LazyQuotes = true

	c := newCollector()
	report := Report{Format: FormatCSV}

	header, err := cr.Read()
	if errors.Is(err, io.EOF) {
		c.add(ProblemNoRows, 0)
		report.Problems = c.problems()
		return report, nil
	}
	if err != nil {
		return Report{}, fmt.Errorf("reading header: %w", err)
	}

	report.Columns = inspectHeader(header, c)

	var p pending
	for rowNo := 1; ; rowNo++ {
		rec, err := cr.Read()
		if errors.Is(err, io.EOF) {
			break
		}
		if err != nil {
			// A malformed row is counted and skipped; the rest of the file is
			// still worth reporting on.
			c.add(ProblemUnreadableRows, rowNo)
			continue
		}

		report.Rows++
		if inspectRow(rec, rowNo, report.Columns, c, &p) {
			// A non-empty row proves anything held back was interior, not the
			// trailing blank lines every spreadsheet writes.
			p.flush(c)
		}
	}
	// Whatever is still pending was trailing: discarded, not reported.

	report.Problems = c.problems()
	return report, nil
}

// sniffDelimiter picks the separator by counting candidates in the header line.
//
// Excel in a locale that uses the comma as a decimal separator writes CSV with
// semicolons. Reading such a file as comma-delimited yields a single column and
// makes a perfectly good file look broken.
func sniffDelimiter(br *bufio.Reader) (rune, error) {
	head, err := br.Peek(peekBytes)
	if err != nil && !errors.Is(err, io.EOF) && !errors.Is(err, bufio.ErrBufferFull) {
		return 0, fmt.Errorf("peeking header: %w", err)
	}
	if i := bytes.IndexByte(head, '\n'); i >= 0 {
		head = head[:i]
	}

	best, bestCount := ',', 0
	for _, sep := range candidates {
		if n := bytes.Count(head, []byte(string(sep))); n > bestCount {
			best, bestCount = sep, n
		}
	}
	return best, nil
}
