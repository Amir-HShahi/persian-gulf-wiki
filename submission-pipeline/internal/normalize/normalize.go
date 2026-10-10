// Package normalize inspects article text and reports what could be
// normalised. It never changes anything.
//
// Every finding is a suggestion for a human: a reviewer decides whether the
// Arabic letter in a Persian article is a keyboard slip or a deliberate
// quotation from an Arabic source. Applying the change silently would falsify
// quoted material, which for an encyclopedia is worse than leaving a typo.
//
// The one transformation this package does perform is IndexKey, which derives
// a match-only string for search. That is not article content: it is never
// stored as text, never displayed, and can be rebuilt at any time.
package normalize

import (
	"strings"
	"unicode/utf8"

	"golang.org/x/text/unicode/norm"
)

// Locale is the language an article is written in. Rules differ by locale:
// an Arabic kaf is a mistake in a Persian article and correct in an Arabic one.
type Locale string

const (
	LocaleFA Locale = "fa"
	LocaleAR Locale = "ar"
	LocaleEN Locale = "en"
)

// Range is a half-open byte range in the source text that findings are
// suppressed inside — URLs, code spans, and block quotes. Quoted text is
// excluded because a quotation is expected to preserve its source's spelling.
type Range struct {
	Start int // inclusive
	End   int // exclusive
}

// Finding is one suggested change, for a reviewer to accept or reject.
type Finding struct {
	Rule    string `json:"rule"`
	Line    int    `json:"line"`   // 1-based
	Column  int    `json:"column"` // 1-based, in runes
	Offset  int    `json:"offset"` // byte offset into the whole text
	Found   string `json:"found"`
	Suggest string `json:"suggest"` // empty means "remove this"
	Context string `json:"context"`
}

// contextRunes is how much text either side of a finding is quoted back, so a
// reviewer can judge it without opening the article.
const contextRunes = 30

// Report lists everything that could be normalised in text, in document order.
// It returns findings only; text is never modified.
func Report(text string, loc Locale, protected []Range) []Finding {
	var findings []Finding

	subs := localeSubs[loc]
	base := 0 // byte offset of the current line within text

	for _, line := range strings.SplitAfter(text, "\n") {
		lineNo := 1 + strings.Count(text[:base], "\n")
		findings = append(findings, reportLine(line, lineNo, base, subs, protected)...)
		base += len(line)
	}

	// NFC is a property of the whole text rather than of one position: the same
	// letter can be stored as one codepoint or as a base plus a combining mark,
	// and the two are visually identical but never compare equal.
	if !norm.NFC.IsNormalString(text) {
		findings = append(findings, Finding{
			Rule:    "not_nfc",
			Line:    0,
			Suggest: "apply Unicode NFC",
		})
	}

	return findings
}

func reportLine(line string, lineNo, base int, subs map[rune]sub, protected []Range) []Finding {
	var findings []Finding
	runes := []rune(line)

	col := 0
	for i, r := range line { // i is a byte offset within line
		col++
		abs := base + i
		if covered(protected, abs) {
			continue
		}

		s, ok := lookup(r, subs)
		if !ok {
			continue
		}
		findings = append(findings, Finding{
			Rule:    s.rule,
			Line:    lineNo,
			Column:  col,
			Offset:  abs,
			Found:   string(r),
			Suggest: s.suggest,
			Context: contextAround(runes, col-1),
		})
	}

	findings = append(findings, reportZWNJ(line, lineNo, base, protected)...)
	return findings
}

// lookup resolves a rune against the locale table first, then the rules that
// apply to every locale.
func lookup(r rune, subs map[rune]sub) (sub, bool) {
	if s, ok := subs[r]; ok {
		return s, true
	}
	if s, ok := everyLocale[r]; ok {
		return s, true
	}
	if isPresentationForm(r) {
		// These are legacy positional and ligature forms. They arrive from PDF
		// copy-paste and never from typing, so the compatibility decomposition
		// is always the intended text.
		return sub{rule: "presentation_form", suggest: norm.NFKC.String(string(r))}, true
	}
	return sub{}, false
}

// reportZWNJ flags misuse of the zero-width non-joiner. The character itself is
// a real part of Persian orthography (می‌روم, کتاب‌ها) and is never removed —
// what is reported is a doubled one, or one sitting next to a space, where it
// can only be an editing accident.
func reportZWNJ(line string, lineNo, base int, protected []Range) []Finding {
	const zwnj = '‌'

	var findings []Finding
	runes := []rune(line)
	offsets := runeOffsets(line)

	for i, r := range runes {
		if r != zwnj {
			continue
		}
		abs := base + offsets[i]
		if covered(protected, abs) {
			continue
		}

		prev, next := ' ', ' '
		if i > 0 {
			prev = runes[i-1]
		}
		if i+1 < len(runes) {
			next = runes[i+1]
		}

		rule := ""
		switch {
		case prev == zwnj || next == zwnj:
			rule = "repeated_zwnj"
		case isSpace(prev) || isSpace(next):
			rule = "zwnj_beside_space"
		case i == 0 || i == len(runes)-1:
			rule = "zwnj_at_edge"
		}
		if rule == "" {
			continue
		}

		findings = append(findings, Finding{
			Rule:    rule,
			Line:    lineNo,
			Column:  i + 1,
			Offset:  abs,
			Found:   string(zwnj),
			Suggest: "",
			Context: contextAround(runes, i),
		})
	}
	return findings
}

// IndexKey derives the match-only form used for search and duplicate detection.
//
// Unlike Report this does transform, and far more aggressively — it unifies
// letters in every locale including Arabic, strips diacritics, and folds all
// digits to ASCII. That is safe precisely because the result is never stored as
// article content and never shown to anyone: it exists so that a reader
// searching ماهی finds an article spelled ماهي.
func IndexKey(text string, loc Locale) string {
	var b strings.Builder
	b.Grow(len(text))

	for _, r := range norm.NFC.String(text) {
		switch {
		case isPresentationForm(r):
			b.WriteString(norm.NFKC.String(string(r)))
		case isDropped(r):
			// tatweel, harakat, ZWNJ and the invisible junk all disappear, so
			// کتاب‌ها and کتابها match, as do کِتاب and کتاب.
		case isSpace(r):
			b.WriteRune(' ')
		default:
			if u, ok := indexFold[r]; ok {
				b.WriteString(u)
				continue
			}
			b.WriteRune(r)
		}
	}

	return strings.Join(strings.Fields(strings.ToLower(b.String())), " ")
}

func covered(ranges []Range, offset int) bool {
	for _, r := range ranges {
		if offset >= r.Start && offset < r.End {
			return true
		}
	}
	return false
}

func contextAround(runes []rune, at int) string {
	lo := max(0, at-contextRunes)
	hi := min(len(runes), at+contextRunes+1)
	return strings.TrimSpace(string(runes[lo:hi]))
}

// runeOffsets returns the byte offset of each rune in s.
func runeOffsets(s string) []int {
	offsets := make([]int, 0, utf8.RuneCountInString(s))
	for i := range s {
		offsets = append(offsets, i)
	}
	return offsets
}

func isSpace(r rune) bool {
	return r == ' ' || r == '\t' || r == '\n' || r == '\r' || r == ' '
}
