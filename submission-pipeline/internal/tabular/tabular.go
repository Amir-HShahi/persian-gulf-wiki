// Package tabular inspects the shape of a submitted CSV or XLSX file and
// reports what is structurally wrong with it. It never modifies anything, and
// it never judges the data.
//
// The scope is deliberately narrow: whether the file can be read and whether
// its rows line up with its header. What the values mean, whether a number is
// plausible, which script the text is written in — none of that is checked,
// because validating somebody's dataset is not this service's job.
//
// Findings are aggregated rather than listed. A file with three thousand empty
// rows produces one entry saying "empty_row, 3412 occurrences, first seen at
// rows 5, 12, 19…", not three thousand entries. The count is the information a
// reviewer needs; a handful of row numbers is enough to go and look.
package tabular

import (
	"bytes"
	"fmt"
)

// Format is the file container, decided from the bytes rather than the name.
type Format string

const (
	FormatCSV     Format = "csv"
	FormatXLSX    Format = "xlsx"
	FormatUnknown Format = "unknown"
)

// Problem codes. Each is a stable identifier so the frontend can render a
// translated message; none of them is ever an English sentence.
const (
	ProblemNoRows         = "no_rows"
	ProblemNoHeader       = "no_header"
	ProblemDuplicateCol   = "duplicate_column"
	ProblemBlankColName   = "blank_column_name"
	ProblemEmptyRow       = "empty_row"
	ProblemRowTooLong     = "row_longer_than_header"
	ProblemUnreadableRows = "unreadable_rows"
)

// sampleLimit is how many row numbers are kept per problem. Enough to open the
// file and look; small enough that the stored report stays a few hundred bytes
// whether the file has twenty rows or two million.
const sampleLimit = 10

// Problem is one kind of structural fault, with how often it occurred.
type Problem struct {
	Code       string            `json:"code"`
	Count      int               `json:"count"`
	SampleRows []int             `json:"sample_rows,omitempty"`
	Detail     map[string]string `json:"detail,omitempty"`
}

// Report is what an admin sees. Nothing here decides anything — a file with
// problems is not rejected, it is handed to a person.
type Report struct {
	Format   Format    `json:"format"`
	Rows     int       `json:"rows"` // data rows, excluding the header
	Columns  int       `json:"columns"`
	Problems []Problem `json:"problems"`
}

// Sniff identifies the container from the first few bytes. XLSX files are zip
// archives and begin with "PK"; anything else is treated as delimited text.
//
// The filename is never consulted: a file named .csv containing XLSX is routine
// once somebody renames an export.
func Sniff(head []byte) Format {
	switch {
	case len(head) >= 2 && head[0] == 'P' && head[1] == 'K':
		return FormatXLSX
	case len(head) == 0:
		return FormatUnknown
	default:
		return FormatCSV
	}
}

// collector accumulates problems by code, keeping counts rather than entries so
// that memory stays flat regardless of how bad the file is.
type collector struct {
	byCode map[string]*Problem
	order  []string
}

func newCollector() *collector {
	return &collector{byCode: make(map[string]*Problem)}
}

// add records one occurrence at a row. Pass row 0 for problems that belong to
// the file rather than to a line.
func (c *collector) add(code string, row int) {
	p, ok := c.byCode[code]
	if !ok {
		p = &Problem{Code: code}
		c.byCode[code] = p
		c.order = append(c.order, code)
	}
	p.Count++
	if row > 0 && len(p.SampleRows) < sampleLimit {
		p.SampleRows = append(p.SampleRows, row)
	}
}

// addN records several occurrences at once, for rows held back and flushed
// later.
func (c *collector) addN(code string, rows []int, count int) {
	for _, r := range rows {
		c.add(code, r)
	}
	// Any occurrences beyond the retained sample still count.
	if extra := count - len(rows); extra > 0 {
		c.byCode[code].Count += extra
	}
}

func (c *collector) detail(code string, detail map[string]string) {
	if p, ok := c.byCode[code]; ok {
		p.Detail = detail
	}
}

func (c *collector) problems() []Problem {
	out := make([]Problem, 0, len(c.order))
	for _, code := range c.order {
		out = append(out, *c.byCode[code])
	}
	return out
}

// pending holds empty rows that have not been reported yet.
//
// Blank lines at the end of a file are what every spreadsheet program produces
// and are not a fault. Blank lines in the middle of the data are. The only way
// to tell them apart is to wait: rows are held here until a non-empty row
// proves they were interior, and whatever is still pending at EOF was trailing.
type pending struct {
	count  int
	sample []int
}

func (p *pending) hold(row int) {
	p.count++
	if len(p.sample) < sampleLimit {
		p.sample = append(p.sample, row)
	}
}

func (p *pending) flush(c *collector) {
	if p.count == 0 {
		return
	}
	c.addN(ProblemEmptyRow, p.sample, p.count)
	p.count = 0
	p.sample = p.sample[:0]
}

// inspectHeader checks the header row itself and returns the number of columns.
func inspectHeader(header []string, c *collector) int {
	if len(header) == 0 || allBlank(header) {
		c.add(ProblemNoHeader, 0)
		return 0
	}

	seen := make(map[string]int, len(header))
	var dupes []string
	for _, name := range header {
		key := trimSpace(name)
		if key == "" {
			c.add(ProblemBlankColName, 0)
			continue
		}
		seen[key]++
		if seen[key] == 2 {
			dupes = append(dupes, key)
		}
	}

	for range dupes {
		c.add(ProblemDuplicateCol, 0)
	}
	if len(dupes) > 0 {
		c.detail(ProblemDuplicateCol, map[string]string{"names": joinComma(dupes)})
	}

	return len(header)
}

// inspectRow applies the per-row checks. It returns false when the row was
// blank and has been held back rather than counted.
func inspectRow(rec []string, rowNo, columns int, c *collector, p *pending) bool {
	if allBlank(rec) {
		p.hold(rowNo)
		return false
	}

	// A row is allowed to be shorter than the header — spreadsheet exports drop
	// trailing empty cells constantly, and a short row simply means the last
	// columns are blank. A row that is longer carries values with no column to
	// put them in, which is a genuine fault.
	if columns > 0 && len(rec) > columns {
		c.add(ProblemRowTooLong, rowNo)
	}
	return true
}

func allBlank(rec []string) bool {
	for _, v := range rec {
		if trimSpace(v) != "" {
			return false
		}
	}
	return true
}

func trimSpace(s string) string {
	return string(bytes.TrimSpace([]byte(s)))
}

func joinComma(xs []string) string {
	out := ""
	for i, x := range xs {
		if i > 0 {
			out += ", "
		}
		out += x
	}
	return out
}

func errUnsupported(f Format) error {
	return fmt.Errorf("unsupported format %q", f)
}
