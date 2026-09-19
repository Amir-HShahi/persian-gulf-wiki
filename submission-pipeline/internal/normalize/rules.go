package normalize

// Codepoints named once, so the tables below read as intent rather than as hex.
// Written as numeric values rather than character literals because several of
// them are invisible or change the direction of the surrounding source line.
const (
	arabicKaf        rune = 0x0643 // ك
	persianKeheh     rune = 0x06A9 // ک
	arabicYeh        rune = 0x064A // ي
	persianYeh       rune = 0x06CC // ی
	alefMaksura      rune = 0x0649 // ى
	alefHamzaAbove   rune = 0x0623 // أ
	alefHamzaBelow   rune = 0x0625 // إ
	alefMadda        rune = 0x0622 // آ — deliberately never touched
	bareAlef         rune = 0x0627 // ا
	tatweel          rune = 0x0640 // ـ
	zwnj             rune = 0x200C // zero-width non-joiner: real Persian orthography
	zwj              rune = 0x200D
	zeroWidthSpace   rune = 0x200B
	softHyphen       rune = 0x00AD
	byteOrderMark    rune = 0xFEFF
	nonBreakingSpace rune = 0x00A0
	leftToRightMark  rune = 0x200E
	rightToLeftMark  rune = 0x200F
	arabicComma      rune = 0x060C // ،
	arabicQuestion   rune = 0x061F // ؟
	arabicSemicolon  rune = 0x061B // ؛

	asciiZero   rune = '0'
	arabicZero  rune = 0x0660 // ٠
	persianZero rune = 0x06F0 // ۰

	harakatFirst rune = 0x064B // ً
	harakatLast  rune = 0x0652 // ْ
)

// sub is one suggested replacement. An empty suggest means "remove".
type sub struct {
	rule    string
	suggest string
}

// everyLocale holds the rules that are not language decisions at all: invisible
// junk from PDF paste and word processors, and a decoration that carries no
// meaning. Nothing in Persian, Arabic or English is supposed to contain these.
var everyLocale = map[rune]sub{
	tatweel:          {rule: "tatweel", suggest: ""},
	zeroWidthSpace:   {rule: "zero_width_space", suggest: ""},
	zwj:              {rule: "zero_width_joiner", suggest: ""},
	softHyphen:       {rule: "soft_hyphen", suggest: ""},
	byteOrderMark:    {rule: "byte_order_mark", suggest: ""},
	leftToRightMark:  {rule: "directional_mark", suggest: ""},
	rightToLeftMark:  {rule: "directional_mark", suggest: ""},
	nonBreakingSpace: {rule: "non_breaking_space", suggest: " "},
}

var localeSubs = map[Locale]map[rune]sub{
	LocaleFA: persianRules(),
	LocaleAR: arabicRules(),
	LocaleEN: englishRules(),
}

// persianRules covers the Arabic-keyboard slips that a Persian article is
// unlikely to have meant, plus Persian punctuation and digits.
func persianRules() map[rune]sub {
	m := map[rune]sub{
		arabicKaf:      {rule: "arabic_kaf", suggest: string(persianKeheh)},
		arabicYeh:      {rule: "arabic_yeh", suggest: string(persianYeh)},
		alefMaksura:    {rule: "alef_maksura", suggest: string(persianYeh)},
		alefHamzaAbove: {rule: "alef_hamza", suggest: string(bareAlef)},
		alefHamzaBelow: {rule: "alef_hamza", suggest: string(bareAlef)},
		// alefMadda (آ) is absent on purpose: the madda is part of the word,
		// not a typing artifact.
		'?': {rule: "latin_question_mark", suggest: string(arabicQuestion)},
		',': {rule: "latin_comma", suggest: string(arabicComma)},
		';': {rule: "latin_semicolon", suggest: string(arabicSemicolon)},
	}
	addDigits(m, asciiZero, persianZero, "ascii_digit")
	addDigits(m, arabicZero, persianZero, "arabic_indic_digit")
	return m
}

// arabicRules is digits only. ك and ي are correct Arabic letters — applying the
// Persian rules here would corrupt every Arabic article.
func arabicRules() map[rune]sub {
	m := map[rune]sub{}
	addDigits(m, asciiZero, arabicZero, "ascii_digit")
	addDigits(m, persianZero, arabicZero, "persian_digit")
	return m
}

// englishRules is digits only. Arabic-script letters in an English article are
// transliterations or quoted names, and are left alone.
func englishRules() map[rune]sub {
	m := map[rune]sub{}
	addDigits(m, persianZero, asciiZero, "persian_digit")
	addDigits(m, arabicZero, asciiZero, "arabic_indic_digit")
	return m
}

// addDigits maps one digit block onto another. The three blocks are contiguous
// and ordered 0-9, so the offset carries across directly.
func addDigits(m map[rune]sub, from, to rune, rule string) {
	for i := rune(0); i < 10; i++ {
		m[from+i] = sub{rule: rule, suggest: string(to + i)}
	}
}

// isPresentationForm reports whether r is an Arabic presentation form — a
// legacy positional or ligature variant that modern text never uses.
func isPresentationForm(r rune) bool {
	return (r >= 0xFB50 && r <= 0xFDFF) || (r >= 0xFE70 && r <= 0xFEFE)
}

func isHarakat(r rune) bool {
	return r >= harakatFirst && r <= harakatLast
}

// isDropped reports whether IndexKey discards r entirely.
func isDropped(r rune) bool {
	switch r {
	case tatweel, zwnj, zwj, zeroWidthSpace, softHyphen,
		byteOrderMark, leftToRightMark, rightToLeftMark:
		return true
	}
	return isHarakat(r)
}

// indexFold unifies letters across all three locales for matching only. It is
// deliberately more destructive than any stored-text rule: an Arabic article
// keeps its ك on the page, but indexes under ک so a Persian search finds it.
var indexFold = buildIndexFold()

func buildIndexFold() map[rune]string {
	m := map[rune]string{
		arabicKaf:       string(persianKeheh),
		arabicYeh:       string(persianYeh),
		alefMaksura:     string(persianYeh),
		alefHamzaAbove:  string(bareAlef),
		alefHamzaBelow:  string(bareAlef),
		alefMadda:       string(bareAlef), // for matching only; never on the page
		0x0629:          "ه",              // ة -> ه, same reasoning
		arabicComma:     ",",
		arabicQuestion:  "?",
		arabicSemicolon: ";",
	}
	// Every digit set collapses to ASCII so ۱۹۶۱ and 1961 find each other.
	addFoldDigits(m, persianZero, asciiZero)
	addFoldDigits(m, arabicZero, asciiZero)
	return m
}

func addFoldDigits(m map[rune]string, from, to rune) {
	for i := rune(0); i < 10; i++ {
		m[from+i] = string(to + i)
	}
}
