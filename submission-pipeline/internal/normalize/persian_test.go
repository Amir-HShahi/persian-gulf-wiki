package normalize

import "testing"

const zw = "\u200c" // ZWNJ, spelled out: it is invisible in a string literal

// only returns the findings of one rule.
func only(fs []Finding, rule string) []Finding {
	var out []Finding
	for _, f := range fs {
		if f.Rule == rule {
			out = append(out, f)
		}
	}
	return out
}

func TestDetachedPrefixAndSuffixSuggestAZWNJ(t *testing.T) {
	cases := []struct {
		in, rule, found, suggest string
	}{
		{"این کار می شود", "detached_prefix", "می ", "می" + zw},
		{"او نمی  داند", "detached_prefix", "نمی  ", "نمی" + zw},
		{"کتاب ها", "detached_suffix", " ها", zw + "ها"},
		{"درخت های بلند", "detached_suffix", " های", zw + "های"},
		{"بزرگ ترین جزیره", "detached_suffix", " ترین", zw + "ترین"},
	}
	for _, c := range cases {
		fs := only(Report(c.in, LocaleFA, nil), c.rule)
		if len(fs) != 1 || fs[0].Found != c.found || fs[0].Suggest != c.suggest {
			t.Errorf("%q: %s findings %+v, want found %q suggest %q", c.in, c.rule, fs, c.found, c.suggest)
		}
	}
}

// What must stay quiet: the correct spelling, words that only look like the
// prefix or suffix, and other locales.
func TestDetachedRulesLeaveCorrectAndLookalikeWordsAlone(t *testing.T) {
	for _, in := range []string{
		"می" + zw + "شود",  // already correct
		"کتاب" + zw + "ها", // already correct
		"میز و میوه",       // start with می, but are words
		"میشود",            // no break at all: not reported, see detachedPrefixes
		"هوا تر است",       // تر alone means "wet"
		"ماهی",             // ends in ی, not a suffix
		"گلها",             // joined with no break: not reported
		"می",               // nothing after it
	} {
		fs := Report(in, LocaleFA, nil)
		if hasRule(fs, "detached_prefix") || hasRule(fs, "detached_suffix") {
			t.Errorf("%q: got %v", in, rulesOf(fs))
		}
	}
	if fs := Report("خواهد شد و می ماند", LocaleAR, nil); hasRule(fs, "detached_prefix") {
		t.Errorf("Persian rules ran on an Arabic article: %v", rulesOf(fs))
	}
}

func TestStraightQuotesBesidePersianBecomeGuillemets(t *testing.T) {
	fs := only(Report(`او گفت "سلام" و رفت`, LocaleFA, nil), "latin_quotes")
	if len(fs) != 2 || fs[0].Suggest != "«" || fs[1].Suggest != "»" {
		t.Fatalf("got %+v", fs)
	}
	if fs := only(Report("نام «خلیج فارس»", LocaleFA, nil), "latin_quotes"); len(fs) != 0 {
		t.Errorf("guillemets reported: %+v", fs)
	}
	// An English phrase quoted in a Persian article keeps its quotes.
	if fs := only(Report(`کتاب "The Persian Gulf" چاپ شد`, LocaleFA, nil), "latin_quotes"); len(fs) != 0 {
		t.Errorf("English quotes reported: %+v", fs)
	}
	if fs := only(Report("او گفت “سلام” و رفت", LocaleFA, nil), "latin_quotes"); len(fs) != 2 {
		t.Errorf("curly quotes: got %+v", fs)
	}
}

func TestPunctuationSpacing(t *testing.T) {
	fs := only(Report("سلام ، خوبی ؟", LocaleFA, nil), "space_before_punctuation")
	if len(fs) != 2 || fs[0].Found != " " || fs[0].Suggest != "" {
		t.Errorf("space before: got %+v", fs)
	}
	fs = only(Report("سلام،خوبی؛ بله", LocaleFA, nil), "no_space_after_punctuation")
	if len(fs) != 1 || fs[0].Found != "،" || fs[0].Suggest != "، " {
		t.Errorf("space after: got %+v", fs)
	}
	for _, in := range []string{"سلام، خوبی؟", "چرا؟!", "۱۲ ، ۱۳"} {
		fs := Report(in, LocaleFA, nil)
		if hasRule(fs, "space_before_punctuation") || hasRule(fs, "no_space_after_punctuation") {
			t.Errorf("%q: got %v", in, rulesOf(fs))
		}
	}
}

func TestNumberSeparatorsAreNotTheComma(t *testing.T) {
	fs := Report("جمعیت ۱,۲۰۰ و ۳.۵ درصد، ۱۲ نفر", LocaleFA, nil)
	if got := only(fs, "thousands_separator"); len(got) != 1 || got[0].Suggest != "٬" {
		t.Errorf("thousands: %+v", got)
	}
	if got := only(fs, "decimal_separator"); len(got) != 1 || got[0].Suggest != "٫" {
		t.Errorf("decimal: %+v", got)
	}
	// The comma inside the number is not also a Latin comma to turn into ،.
	if hasRule(fs, "latin_comma") {
		t.Errorf("a digit comma was reported as a Latin comma: %v", rulesOf(fs))
	}
	if fs := Report("population 1,200", LocaleEN, nil); hasRule(fs, "thousands_separator") {
		t.Errorf("English numbers reported: %v", rulesOf(fs))
	}
}

func TestFindingsComeInTextOrder(t *testing.T) {
	fs := Report("كتاب ها و ماهي", LocaleFA, nil)
	for i := 1; i < len(fs); i++ {
		if fs[i].Offset < fs[i-1].Offset {
			t.Fatalf("out of order: %+v", fs)
		}
	}
	if len(fs) < 3 {
		t.Errorf("expected kaf, suffix and yeh, got %v", rulesOf(fs))
	}
}

func TestProtectedRangesCoverThePersianRules(t *testing.T) {
	const in = "> کتاب ها"
	if fs := Report(in, LocaleFA, []Range{{Start: 0, End: len(in)}}); len(fs) != 0 {
		t.Errorf("finding inside a protected range: %v", rulesOf(fs))
	}
}
