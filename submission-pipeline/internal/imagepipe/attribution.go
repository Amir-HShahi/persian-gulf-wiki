package imagepipe

import (
	"bytes"
	"encoding/xml"
	"regexp"
	"strings"
	"unicode"
)

// Attribution is who made an image and on what terms, as the file itself
// states it, read before the variants are stripped of their metadata. Wikimedia
// Commons requires an author and a licence on every file; this is what the
// uploader's camera or photo software already recorded, for a reviewer to check
// against what the uploader declares. It is a claim the file makes, not proof.
//
// Only these fields are ever read. Location, camera serial numbers and the rest
// of the metadata are not, and the variants carry none of it.
type Attribution struct {
	Author     string `json:"author,omitempty"`
	Credit     string `json:"credit,omitempty"`
	Copyright  string `json:"copyright,omitempty"`
	License    string `json:"license,omitempty"`     // usage terms, as text
	LicenseURL string `json:"license_url,omitempty"` // a licence or rights statement, as a link
	CreatedAt  string `json:"created_at,omitempty"`  // when it was taken, as the file gives it
}

func (a Attribution) empty() bool { return a == Attribution{} }

// maxAttributionRunes caps each field. A file can carry a whole essay in its
// copyright notice, and the result is stored with every job.
const maxAttributionRunes = 300

// attributionFrom builds an Attribution from libvips' EXIF fields and the raw
// XMP packet. XMP wins where both say something: it is UTF-8, so it can hold a
// Persian name, while EXIF text is ASCII only, and it is where licences live.
// Returns nil when the file says nothing about its origin.
func attributionFrom(exif map[string]string, xmpPacket []byte) *Attribution {
	x := parseXMP(xmpPacket)
	a := Attribution{
		Author:     first(x.creator, exifText(exif["exif-ifd0-Artist"])),
		Credit:     x.credit,
		Copyright:  first(x.rights, exifText(exif["exif-ifd0-Copyright"])),
		License:    x.usageTerms,
		LicenseURL: first(x.license, x.webStatement),
		CreatedAt:  first(x.created, exifDate(exifText(exif["exif-ifd2-DateTimeOriginal"]))),
	}
	a.Author = clean(a.Author)
	a.Credit = clean(a.Credit)
	a.Copyright = clean(a.Copyright)
	a.License = clean(a.License)
	a.LicenseURL = clean(a.LicenseURL)
	a.CreatedAt = clean(a.CreatedAt)
	if a.empty() {
		return nil
	}
	return &a
}

// exifASCIISuffix is how libvips ends an ASCII EXIF field, after repeating the
// value: "Jane Doe (Jane Doe, ASCII, 9 components, 9 bytes)".
var exifASCIISuffix = regexp.MustCompile(`, ASCII, \d+ components?, \d+ bytes?\)$`)

// exifText returns the value of an ASCII EXIF field as libvips reports it, or ""
// for any other shape. The value appears twice, "V (V" before the suffix, and is
// taken only when both halves agree, so a value that itself contains " (" is
// still read whole.
func exifText(field string) string {
	loc := exifASCIISuffix.FindStringIndex(field)
	if loc == nil {
		return ""
	}
	rest := field[:loc[0]]
	if (len(rest)-2)%2 != 0 || len(rest) < 2 {
		return ""
	}
	n := (len(rest) - 2) / 2
	if rest[n:n+2] != " (" || rest[:n] != rest[n+2:] {
		return ""
	}
	return rest[:n]
}

// exifDate turns EXIF's "2024:05:01 10:20:30" into "2024-05-01T10:20:30". EXIF
// stores no time zone, so none is added. Anything else is returned unchanged.
var exifDateShape = regexp.MustCompile(`^(\d{4}):(\d{2}):(\d{2}) (\d{2}:\d{2}:\d{2})$`)

func exifDate(s string) string {
	if m := exifDateShape.FindStringSubmatch(strings.TrimSpace(s)); m != nil {
		return m[1] + "-" + m[2] + "-" + m[3] + "T" + m[4]
	}
	return s
}

// The XMP namespaces the fields below are read from.
const (
	nsRDF       = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
	nsDC        = "http://purl.org/dc/elements/1.1/"
	nsXMPRights = "http://ns.adobe.com/xap/1.0/rights/"
	nsCC        = "http://creativecommons.org/ns#"
	nsPhotoshop = "http://ns.adobe.com/photoshop/1.0/"
	nsXMP       = "http://ns.adobe.com/xap/1.0/"
	nsEXIF      = "http://ns.adobe.com/exif/1.0/"
)

type xmpFields struct {
	creator, rights, usageTerms, webStatement, license, credit, created string
}

// xmpProperty names the properties that are read, by namespace and local name.
type xmpProperty struct{ space, local string }

var (
	propCreator      = xmpProperty{nsDC, "creator"}
	propRights       = xmpProperty{nsDC, "rights"}
	propUsageTerms   = xmpProperty{nsXMPRights, "UsageTerms"}
	propWebStatement = xmpProperty{nsXMPRights, "WebStatement"}
	propLicense      = xmpProperty{nsCC, "license"}
	propCredit       = xmpProperty{nsPhotoshop, "Credit"}
	propDateCreated  = xmpProperty{nsPhotoshop, "DateCreated"}
	propCreateDate   = xmpProperty{nsXMP, "CreateDate"}
	propDateOriginal = xmpProperty{nsEXIF, "DateTimeOriginal"}
)

// parseXMP reads the attribution properties out of an XMP packet. XMP is RDF,
// and the same property can be written as an attribute of rdf:Description, as
// an element with text, as an element pointing elsewhere with rdf:resource, or
// as a list (rdf:Seq, rdf:Bag, rdf:Alt) of rdf:li. All four are read. A packet
// that is not well-formed yields whatever was read before the fault.
func parseXMP(packet []byte) xmpFields {
	values := map[xmpProperty][]string{}
	if len(bytes.TrimSpace(packet)) == 0 {
		return xmpFields{}
	}

	dec := xml.NewDecoder(bytes.NewReader(packet))
	var (
		current *xmpProperty // the property being read, if any
		depth   int          // elements open inside it
		text    strings.Builder
		items   []string
	)
	for {
		tok, err := dec.Token()
		if err != nil {
			break
		}
		switch t := tok.(type) {
		case xml.StartElement:
			if current != nil {
				depth++
				if t.Name.Space == nsRDF && t.Name.Local == "li" {
					text.Reset()
				}
				continue
			}
			for _, attr := range t.Attr {
				p := xmpProperty{attr.Name.Space, attr.Name.Local}
				if wanted(p) {
					values[p] = append(values[p], attr.Value)
				}
			}
			p := xmpProperty{t.Name.Space, t.Name.Local}
			if wanted(p) {
				current, depth, items = &p, 0, nil
				text.Reset()
				for _, attr := range t.Attr {
					if attr.Name.Space == nsRDF && attr.Name.Local == "resource" {
						items = append(items, attr.Value)
					}
				}
			}
		case xml.CharData:
			if current != nil {
				text.Write(t)
			}
		case xml.EndElement:
			if current == nil {
				continue
			}
			if depth > 0 {
				if t.Name.Space == nsRDF && t.Name.Local == "li" {
					if v := strings.TrimSpace(text.String()); v != "" {
						items = append(items, v)
					}
					text.Reset()
				}
				depth--
				continue
			}
			if len(items) == 0 {
				if v := strings.TrimSpace(text.String()); v != "" {
					items = append(items, v)
				}
			}
			values[*current] = append(values[*current], items...)
			current = nil
		}
	}

	get := func(p xmpProperty) string { return firstOf(values[p]) }
	return xmpFields{
		// Several creators are all authors; the other fields have one value, or
		// one per language in an rdf:Alt, of which the first is the default.
		creator:      strings.Join(values[propCreator], "; "),
		rights:       get(propRights),
		usageTerms:   get(propUsageTerms),
		webStatement: get(propWebStatement),
		license:      get(propLicense),
		credit:       get(propCredit),
		created:      first(get(propDateCreated), get(propDateOriginal), get(propCreateDate)),
	}
}

func wanted(p xmpProperty) bool {
	switch p {
	case propCreator, propRights, propUsageTerms, propWebStatement, propLicense,
		propCredit, propDateCreated, propCreateDate, propDateOriginal:
		return true
	}
	return false
}

func firstOf(vs []string) string {
	for _, v := range vs {
		if strings.TrimSpace(v) != "" {
			return v
		}
	}
	return ""
}

func first(vs ...string) string { return firstOf(vs) }

// clean trims a value, turns control characters and runs of space into one
// space, and caps its length, so a field is always one short line of text.
func clean(s string) string {
	var b strings.Builder
	space := false
	n := 0
	for _, r := range strings.TrimSpace(s) {
		if unicode.IsControl(r) || unicode.IsSpace(r) {
			space = true
			continue
		}
		if space && b.Len() > 0 {
			b.WriteRune(' ')
			n++
		}
		space = false
		if n >= maxAttributionRunes {
			break
		}
		b.WriteRune(r)
		n++
	}
	return b.String()
}
