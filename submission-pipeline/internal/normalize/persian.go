package normalize

import "unicode"

// Persian typography: patterns longer than one character, unlike the
// per-letter tables in rules.go. Each is a rule only where it is almost never
// wrong, because a reviewer shown ten false suggestions stops reading them.

const (
	arabicDecimalSeparator   rune = 0x066B // ٫
	arabicThousandsSeparator rune = 0x066C // ٬
	leftGuillemet            rune = 0x00AB // «
	rightGuillemet           rune = 0x00BB // »
	leftDoubleQuote          rune = 0x201C // “
	rightDoubleQuote         rune = 0x201D // ”
)

// detachedPrefixes are verb prefixes written as a word of their own, "می شود"
// for می‌شود. Neither is a word in running text, so a space after one is the
// missing ZWNJ. A prefix typed with no break at all, "میشود", is not reported:
// میز and میوه look the same.
var detachedPrefixes = map[string]bool{"می": true, "نمی": true}

// detachedSuffixes are plural and superlative endings written as a word of
// their own, "کتاب ها" for کتاب‌ها. None is a Persian word alone. تر is left
// out because alone it means "wet".
var detachedSuffixes = map[string]bool{
	"ها": true, "های": true, "هایی": true,
	"هایم": true, "هایت": true, "هایش": true,
	"هایمان": true, "هایتان": true, "هایشان": true,
	"ترین": true,
}

// persianMarks take no space before them and one after, as in Persian
// typesetting. The Latin ones are here too for the space before: that they are
// Latin is reported separately.
func isPersianMark(r rune) bool {
	return r == arabicComma || r == arabicSemicolon || r == arabicQuestion
}

func takesNoSpaceBefore(r rune) bool {
	return isPersianMark(r) || r == '!' || r == ',' || r == ';' || r == '?'
}

// reportPersian finds the multi-character Persian rules in one line.
func reportPersian(line string, lineNo, base int, protected []Range) []Finding {
	runes := []rune(line)
	offsets := runeOffsets(line)
	var findings []Finding

	add := func(rule string, at int, found, suggest string) {
		abs := base + offsets[at]
		if covered(protected, abs) {
			return
		}
		findings = append(findings, Finding{
			Rule:    rule,
			Line:    lineNo,
			Column:  at + 1,
			Offset:  abs,
			Found:   found,
			Suggest: suggest,
			Context: contextAround(runes, at),
		})
	}

	for i := 0; i < len(runes); {
		r := runes[i]

		if isWordRune(r) {
			end := i
			for end < len(runes) && isWordRune(runes[end]) {
				end++
			}
			word := string(runes[i:end])

			// "می شود": the prefix, then spaces, then a Persian word.
			if detachedPrefixes[word] {
				gap := end
				for gap < len(runes) && runes[gap] == ' ' {
					gap++
				}
				if gap > end && gap < len(runes) && isArabicScript(runes[gap]) {
					add("detached_prefix", i, string(runes[i:gap]), word+string(zwnj))
				}
			}

			// "کتاب ها": a Persian word, then spaces, then the suffix.
			if detachedSuffixes[word] {
				gap := i
				for gap > 0 && runes[gap-1] == ' ' {
					gap--
				}
				if gap < i && gap > 0 && isArabicScript(runes[gap-1]) {
					add("detached_suffix", gap, string(runes[gap:end]), string(zwnj)+word)
				}
			}
			i = end
			continue
		}

		switch {
		case r == '"' || r == leftDoubleQuote || r == rightDoubleQuote:
			// A straight quote opens when it follows a space, a bracket or the
			// start of the line. Only quotes beside Persian text are reported:
			// an English phrase quoted in a Persian article keeps its own.
			opening := r == leftDoubleQuote ||
				(r == '"' && (i == 0 || isSpace(runes[i-1]) || runes[i-1] == '(' || runes[i-1] == '['))
			if opening && i+1 < len(runes) && isArabicScript(runes[i+1]) {
				add("latin_quotes", i, string(r), string(leftGuillemet))
			} else if !opening && i > 0 && isArabicScript(runes[i-1]) {
				add("latin_quotes", i, string(r), string(rightGuillemet))
			}

		case r == ' ':
			// Spaces before a mark that takes none: "سلام ،".
			end := i
			for end < len(runes) && runes[end] == ' ' {
				end++
			}
			if end < len(runes) && takesNoSpaceBefore(runes[end]) && i > 0 && isWordRune(runes[i-1]) {
				add("space_before_punctuation", i, string(runes[i:end]), "")
			}
			i = end
			continue

		case isPersianMark(r):
			// No space after a mark, straight into the next word: "سلام،خوبی".
			if i+1 < len(runes) && isArabicScript(runes[i+1]) && unicode.IsLetter(runes[i+1]) {
				add("no_space_after_punctuation", i, string(r), string(r)+" ")
			}
		}
		i++
	}
	return findings
}

// numberSeparator reports what a comma or full stop between two digits should
// be in Persian: ٬ groups thousands and ٫ marks decimals. A comma there is not
// the Persian comma ، that the letter table would otherwise suggest.
func numberSeparator(runes []rune, at int) (sub, bool) {
	if at == 0 || at+1 >= len(runes) || !isDigit(runes[at-1]) || !isDigit(runes[at+1]) {
		return sub{}, false
	}
	switch runes[at] {
	case ',':
		return sub{rule: "thousands_separator", suggest: string(arabicThousandsSeparator)}, true
	case '.':
		return sub{rule: "decimal_separator", suggest: string(arabicDecimalSeparator)}, true
	}
	return sub{}, false
}

func isDigit(r rune) bool {
	return (r >= asciiZero && r <= asciiZero+9) ||
		(r >= persianZero && r <= persianZero+9) ||
		(r >= arabicZero && r <= arabicZero+9)
}

// isWordRune is a letter or a mark on one, so a word with harakat stays whole.
// ZWNJ is not one: کتاب‌ها is two runs, joined on purpose.
func isWordRune(r rune) bool {
	return unicode.IsLetter(r) || unicode.Is(unicode.Mn, r)
}

func isArabicScript(r rune) bool {
	return unicode.Is(unicode.Arabic, r)
}
