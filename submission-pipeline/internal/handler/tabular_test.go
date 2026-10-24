package handler

import (
	"os"
	"path/filepath"
	"testing"

	"github.com/xuri/excelize/v2"

	"wikipg/internal/tabular"
)

func writeFile(t *testing.T, name string, data []byte) string {
	t.Helper()
	p := filepath.Join(t.TempDir(), name)
	if err := os.WriteFile(p, data, 0o600); err != nil {
		t.Fatal(err)
	}
	return p
}

func TestInspectTabularReportsACSVWithoutRejectingIt(t *testing.T) {
	// A blank column name and an empty row: problems for a reviewer, not a
	// reason to refuse the file.
	p := writeFile(t, "data.xlsx", []byte("name,,depth\nKish,a,10\n,,\nQeshm,b,20\n"))

	report, rejection, err := inspectTabular(p)
	if err != nil || rejection != nil {
		t.Fatalf("err = %v, rejection = %+v", err, rejection)
	}
	// Decided by content: the .xlsx name does not make it XLSX.
	if report.Format != tabular.FormatCSV || report.Columns != 3 {
		t.Errorf("got %s with %d columns, want csv with 3", report.Format, report.Columns)
	}
	codes := map[string]bool{}
	for _, pr := range report.Problems {
		codes[pr.Code] = true
	}
	if !codes[tabular.ProblemBlankColName] {
		t.Errorf("problems %+v lack %s", report.Problems, tabular.ProblemBlankColName)
	}
}

func TestInspectTabularReadsXLSX(t *testing.T) {
	f := excelize.NewFile()
	defer f.Close()
	for cell, v := range map[string]any{"A1": "island", "B1": "area", "A2": "Kish", "B2": 91, "A3": "Qeshm", "B3": 1491} {
		if err := f.SetCellValue("Sheet1", cell, v); err != nil {
			t.Fatal(err)
		}
	}
	// Saved under an .xlsx name, as excelize requires, then renamed: uploads
	// arrive under a key with no extension, and only the content may decide.
	dir := t.TempDir()
	if err := f.SaveAs(filepath.Join(dir, "book.xlsx")); err != nil {
		t.Fatal(err)
	}
	p := filepath.Join(dir, "upload")
	if err := os.Rename(filepath.Join(dir, "book.xlsx"), p); err != nil {
		t.Fatal(err)
	}

	report, rejection, err := inspectTabular(p)
	if err != nil || rejection != nil {
		t.Fatalf("err = %v, rejection = %+v", err, rejection)
	}
	if report.Format != tabular.FormatXLSX || report.Rows != 2 || report.Columns != 2 {
		t.Errorf("got %+v, want xlsx with 2 rows and 2 columns", report)
	}
}

func TestInspectTabularRefusesWhatIsNotASpreadsheet(t *testing.T) {
	cases := []struct {
		name string
		data []byte
		want string
	}{
		{"empty file", nil, "unsupported_format"},
		// A photo uploaded by mistake: once read as a CSV of noise.
		{"a jpeg", []byte{0xFF, 0xD8, 0xFF, 0xE0, 0, 0x10, 'J', 'F', 'I', 'F', 0, 1}, "unsupported_format"},
		// Starts like a zip, so it is taken for XLSX, and then is not one.
		{"broken xlsx", append([]byte("PK\x03\x04"), make([]byte, 64)...), "corrupt_file"},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			_, rejection, err := inspectTabular(writeFile(t, "upload", c.data))
			if err != nil {
				t.Fatal(err)
			}
			if rejection == nil || rejection.Code != c.want {
				t.Errorf("rejection = %+v, want %q", rejection, c.want)
			}
		})
	}
}
