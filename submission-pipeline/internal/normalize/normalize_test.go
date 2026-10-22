package normalize

import "testing"

func rulesOf(fs []Finding) []string {
	out := make([]string, 0, len(fs))
	for _, f := range fs {
		out = append(out, f.Rule)
	}
	return out
}

func hasRule(fs []Finding, rule string) bool {
	for _, f := range fs {
		if f.Rule == rule {
			return true
		}
	}
	return false
}

func TestReportNeverMutates(t *testing.T) {
	const in = "ماهي طلايي 1961"
	before := in
	Report(in, LocaleFA, nil)
	if in != before {
		t.Fatal("Report changed its input")
	}
}

func TestPersianLetterAndDigitRules(t *testing.T) {
	// Arabic yeh and ASCII digits in a Persian article.
	fs := Report("ماهي 1961", LocaleFA, nil)

	if !hasRule(fs, "arabic_yeh") {
		t.Errorf("missing arabic_yeh, got %v", rulesOf(fs))
	}
	if !hasRule(fs, "ascii_digit") {
		t.Errorf("missing ascii_digit, got %v", rulesOf(fs))
	}
}

func TestAlefMaddaIsLeftAlone(t *testing.T) {
	// آ is part of the word; only أ and إ are reported.
	if fs := Report("آب", LocaleFA, nil); len(fs) != 0 {
		t.Errorf("alef madda should not be reported, got %v", rulesOf(fs))
	}
	if fs := Report("أب", LocaleFA, nil); !hasRule(fs, "alef_hamza") {
		t.Errorf("alef hamza should be reported, got %v", rulesOf(fs))
	}
}

func TestArabicArticleKeepsItsLetters(t *testing.T) {
	// ك and ي are correct Arabic: digits only in this locale.
	fs := Report("الكتاب العربي 1961", LocaleAR, nil)

	if hasRule(fs, "arabic_kaf") || hasRule(fs, "arabic_yeh") {
		t.Errorf("arabic letters must not be reported in an arabic article, got %v", rulesOf(fs))
	}
	if !hasRule(fs, "ascii_digit") {
		t.Errorf("missing ascii_digit, got %v", rulesOf(fs))
	}
}

func TestEnglishArticleFoldsDigitsOnly(t *testing.T) {
	fs := Report("discovered in ۱۹۶۱", LocaleEN, nil)

	if !hasRule(fs, "persian_digit") {
		t.Errorf("missing persian_digit, got %v", rulesOf(fs))
	}
	if len(rulesOf(fs)) != 4 {
		t.Errorf("expected one finding per digit, got %v", rulesOf(fs))
	}
}

func TestProtectedRangesSuppressFindings(t *testing.T) {
	const in = "see https://example.com/page/12 for more"
	start, end := 4, 30 // the URL

	if fs := Report(in, LocaleFA, nil); !hasRule(fs, "ascii_digit") {
		t.Fatalf("expected digits to be reported without protection, got %v", rulesOf(fs))
	}
	fs := Report(in, LocaleFA, []Range{{Start: start, End: end}})
	for _, f := range fs {
		if f.Offset >= start && f.Offset < end {
			t.Errorf("finding inside protected range: %+v", f)
		}
	}
}

func TestTier1AppliesToEveryLocale(t *testing.T) {
	// A tatweel is decoration with no meaning, in any language.
	for _, loc := range []Locale{LocaleFA, LocaleAR, LocaleEN} {
		if fs := Report("كــتاب", loc, nil); !hasRule(fs, "tatweel") {
			t.Errorf("%s: missing tatweel, got %v", loc, rulesOf(fs))
		}
	}
}

func TestZWNJKeptButMisuseReported(t *testing.T) {
	// Correct compound: nothing to report.
	if fs := Report("کتاب‌ها", LocaleFA, nil); len(fs) != 0 {
		t.Errorf("valid ZWNJ should not be reported, got %v", rulesOf(fs))
	}
	// Beside a space it can only be an accident.
	if fs := Report("کتاب ‌ها", LocaleFA, nil); !hasRule(fs, "zwnj_beside_space") {
		t.Errorf("missing zwnj_beside_space, got %v", rulesOf(fs))
	}
	if fs := Report("کتاب‌‌ها", LocaleFA, nil); !hasRule(fs, "repeated_zwnj") {
		t.Errorf("missing repeated_zwnj, got %v", rulesOf(fs))
	}
}

func TestFindingCarriesPosition(t *testing.T) {
	fs := Report("سلام\nماهي", LocaleFA, nil)
	if len(fs) == 0 {
		t.Fatal("expected a finding")
	}
	f := fs[0]
	if f.Line != 2 {
		t.Errorf("Line = %d, want 2", f.Line)
	}
	if f.Found != "ي" || f.Suggest != "ی" {
		t.Errorf("Found/Suggest = %q/%q, want ي/ی", f.Found, f.Suggest)
	}
}

func TestIndexKeyMatchesAcrossSpellings(t *testing.T) {
	cases := []struct{ a, b string }{
		{"ماهی", "ماهي"},         // persian yeh vs arabic yeh
		{"کتاب", "كتاب"},         // persian keheh vs arabic kaf
		{"کتاب‌ها", "کتابها"},    // with and without ZWNJ
		{"۱۹۶۱", "1961"},         // persian digits vs ascii
		{"کِتاب", "کتاب"},        // with and without harakat
		{"GOLDFISH", "goldfish"}, // the english case everyone already accepts
	}
	for _, c := range cases {
		if got, want := IndexKey(c.a, LocaleFA), IndexKey(c.b, LocaleFA); got != want {
			t.Errorf("IndexKey(%q) = %q, IndexKey(%q) = %q — should match", c.a, got, c.b, want)
		}
	}
}

func TestIndexKeyCrossesLocales(t *testing.T) {
	// An arabic article keeps ك on the page but must be findable by a persian
	// reader typing ک.
	if got, want := IndexKey("الكتاب", LocaleAR), IndexKey("الکتاب", LocaleFA); got != want {
		t.Errorf("cross-locale index mismatch: %q vs %q", got, want)
	}
}
