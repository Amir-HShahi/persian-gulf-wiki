package tabular

import (
	"strings"
	"testing"
)

func find(t *testing.T, rep Report, code string) Problem {
	t.Helper()
	for _, p := range rep.Problems {
		if p.Code == code {
			return p
		}
	}
	t.Fatalf("no %q problem in %+v", code, rep.Problems)
	return Problem{}
}

func absent(t *testing.T, rep Report, code string) {
	t.Helper()
	for _, p := range rep.Problems {
		if p.Code == code {
			t.Fatalf("unexpected %q problem: %+v", code, p)
		}
	}
}

func inspect(t *testing.T, body string) Report {
	t.Helper()
	rep, err := InspectCSV(strings.NewReader(body))
	if err != nil {
		t.Fatalf("InspectCSV: %v", err)
	}
	return rep
}

func TestCleanFileHasNoProblems(t *testing.T) {
	rep := inspect(t, "name,depth_m\nStrait A,54\nStrait B,61\n")

	if len(rep.Problems) != 0 {
		t.Errorf("expected no problems, got %+v", rep.Problems)
	}
	if rep.Rows != 2 || rep.Columns != 2 {
		t.Errorf("Rows/Columns = %d/%d, want 2/2", rep.Rows, rep.Columns)
	}
}

func TestShortRowIsNotAProblem(t *testing.T) {
	// Spreadsheets drop trailing empty cells constantly; a short row just means
	// the last columns are blank.
	rep := inspect(t, "name,depth_m,notes\nStrait A,54\n")
	absent(t, rep, ProblemRowTooLong)
	if len(rep.Problems) != 0 {
		t.Errorf("expected no problems, got %+v", rep.Problems)
	}
}

func TestRowLongerThanHeaderIsReported(t *testing.T) {
	rep := inspect(t, "name,depth\nA,1\nB,2,3\nC,4\n")

	p := find(t, rep, ProblemRowTooLong)
	if p.Count != 1 {
		t.Errorf("Count = %d, want 1", p.Count)
	}
	if len(p.SampleRows) != 1 || p.SampleRows[0] != 2 {
		t.Errorf("SampleRows = %v, want [2]", p.SampleRows)
	}
}

func TestDuplicateColumns(t *testing.T) {
	rep := inspect(t, "name,depth,name\nA,1,B\n")

	p := find(t, rep, ProblemDuplicateCol)
	if p.Detail["names"] != "name" {
		t.Errorf("Detail = %v, want names=name", p.Detail)
	}
}

func TestBlankColumnName(t *testing.T) {
	rep := inspect(t, "name,,depth\nA,x,1\n")
	find(t, rep, ProblemBlankColName)
}

// An empty row is one that exists with every cell blank — "," for a two-column
// file, which is what a spreadsheet writes. A bare newline is not a row at all:
// encoding/csv skips it, as does every other CSV parser.
func TestTrailingBlankRowsAreIgnored(t *testing.T) {
	rep := inspect(t, "name,depth\nA,1\n,\n,\n,\n")
	absent(t, rep, ProblemEmptyRow)
}

func TestInteriorBlankRowsAreReported(t *testing.T) {
	rep := inspect(t, "name,depth\nA,1\n,\n,\nB,2\n,\n,\n")

	p := find(t, rep, ProblemEmptyRow)
	if p.Count != 2 {
		t.Errorf("Count = %d, want 2 (trailing blanks must not count)", p.Count)
	}
	if len(p.SampleRows) != 2 || p.SampleRows[0] != 2 || p.SampleRows[1] != 3 {
		t.Errorf("SampleRows = %v, want [2 3]", p.SampleRows)
	}
}

func TestProblemsAreAggregatedNotListed(t *testing.T) {
	// 3000 interior blank rows must produce one entry with a count, not 3000
	// entries, and must keep only a bounded sample.
	var b strings.Builder
	b.WriteString("name,depth\n")
	for i := 0; i < 3000; i++ {
		b.WriteString(",\n")
	}
	b.WriteString("A,1\n")

	rep := inspect(t, b.String())

	p := find(t, rep, ProblemEmptyRow)
	if p.Count != 3000 {
		t.Errorf("Count = %d, want 3000", p.Count)
	}
	if len(p.SampleRows) != sampleLimit {
		t.Errorf("SampleRows length = %d, want %d", len(p.SampleRows), sampleLimit)
	}
	if len(rep.Problems) != 1 {
		t.Errorf("expected one aggregated problem, got %d", len(rep.Problems))
	}
}

func TestEmptyFile(t *testing.T) {
	rep := inspect(t, "")
	find(t, rep, ProblemNoRows)
}

func TestSemicolonDelimiterIsDetected(t *testing.T) {
	// Excel in a comma-decimal locale writes semicolons. Read as comma this
	// would be one column and every row would look fine but be useless.
	rep := inspect(t, "name;depth;notes\nA;1;x\n")

	if rep.Columns != 3 {
		t.Errorf("Columns = %d, want 3", rep.Columns)
	}
	if len(rep.Problems) != 0 {
		t.Errorf("expected no problems, got %+v", rep.Problems)
	}
}

func TestUTF8BOMDoesNotCorruptFirstColumn(t *testing.T) {
	bom := string([]byte{0xEF, 0xBB, 0xBF})
	rep := inspect(t, bom+"name,depth\nA,1\n")

	absent(t, rep, ProblemBlankColName)
	if rep.Columns != 2 {
		t.Errorf("Columns = %d, want 2", rep.Columns)
	}
}

func TestSniff(t *testing.T) {
	cases := []struct {
		name string
		head []byte
		want Format
	}{
		{"xlsx zip magic", []byte{'P', 'K', 0x03, 0x04}, FormatXLSX},
		{"plain text", []byte("name,depth"), FormatCSV},
		{"empty", nil, FormatUnknown},
	}
	for _, c := range cases {
		if got := Sniff(c.head); got != c.want {
			t.Errorf("%s: Sniff = %q, want %q", c.name, got, c.want)
		}
	}
}
