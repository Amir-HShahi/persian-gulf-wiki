package tabular

import (
	"fmt"

	"github.com/xuri/excelize/v2"
)

// InspectXLSX reads the first sheet of an XLSX file and reports its structure.
//
// It takes a path rather than a reader because an XLSX is a zip archive, and a
// zip's table of contents lives at the end of the file — it cannot be read
// front to back, so the bytes have to be on disk first.
func InspectXLSX(path string) (Report, error) {
	f, err := excelize.OpenFile(path)
	if err != nil {
		return Report{}, fmt.Errorf("opening xlsx: %w", err)
	}
	defer f.Close()

	sheets := f.GetSheetList()
	if len(sheets) == 0 {
		return Report{}, fmt.Errorf("xlsx has no sheets")
	}

	// Rows returns an iterator rather than the whole sheet. GetRows would load
	// every cell into memory at once, which is fine at twenty thousand rows and
	// fatal at two million.
	rows, err := f.Rows(sheets[0])
	if err != nil {
		return Report{}, fmt.Errorf("reading sheet %q: %w", sheets[0], err)
	}
	defer rows.Close()

	c := newCollector()
	report := Report{Format: FormatXLSX}

	if !rows.Next() {
		c.add(ProblemNoRows, 0)
		report.Problems = c.problems()
		return report, nil
	}
	header, err := rows.Columns()
	if err != nil {
		return Report{}, fmt.Errorf("reading header: %w", err)
	}
	report.Columns = inspectHeader(header, c)

	var p pending
	for rowNo := 1; rows.Next(); rowNo++ {
		rec, err := rows.Columns()
		if err != nil {
			c.add(ProblemUnreadableRows, rowNo)
			continue
		}

		report.Rows++
		if inspectRow(rec, rowNo, report.Columns, c, &p) {
			p.flush(c)
		}
	}
	if err := rows.Error(); err != nil {
		return Report{}, fmt.Errorf("iterating rows: %w", err)
	}
	// Pending rows at this point were trailing blanks: not a fault.

	report.Problems = c.problems()
	return report, nil
}
