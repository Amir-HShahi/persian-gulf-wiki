package imagepipe

import (
	"strings"
	"testing"
)

// The strings below are what libvips v8.16+ returns, copied from a real file.
func TestExifTextReadsLibvipsASCIIFields(t *testing.T) {
	cases := map[string]string{
		"Canon (Canon, ASCII, 6 components, 6 bytes)":                               "Canon",
		"Jane (Doe), Kish (Jane (Doe), Kish, ASCII, 17 components, 17 bytes)":       "Jane (Doe), Kish",
		"2024:05:01 10:20:30 (2024:05:01 10:20:30, ASCII, 20 components, 20 bytes)": "2024:05:01 10:20:30",
		// Not ASCII, or not libvips' shape: nothing is guessed.
		"1 (Top-left, Short, 1 components, 2 bytes)": "",
		"just text": "",
		"":          "",
	}
	for in, want := range cases {
		if got := exifText(in); got != want {
			t.Errorf("exifText(%q) = %q, want %q", in, got, want)
		}
	}
}

func TestExifDateBecomesISOWithoutInventingAZone(t *testing.T) {
	if got := exifDate("2024:05:01 10:20:30"); got != "2024-05-01T10:20:30" {
		t.Errorf("exifDate = %q", got)
	}
	if got := exifDate("sometime in May"); got != "sometime in May" {
		t.Errorf("an unknown shape was rewritten: %q", got)
	}
}

// One packet using every way XMP writes a property: attribute, rdf:Seq,
// rdf:Alt with a language, rdf:resource, and plain element text.
const xmpPacket = `<?xpacket begin="" id="W5M0MpCehiHzreSzNTczkc9d"?>
<x:xmpmeta xmlns:x="adobe:ns:meta/">
 <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
  <rdf:Description rdf:about=""
    xmlns:dc="http://purl.org/dc/elements/1.1/"
    xmlns:xmpRights="http://ns.adobe.com/xap/1.0/rights/"
    xmlns:cc="http://creativecommons.org/ns#"
    xmlns:photoshop="http://ns.adobe.com/photoshop/1.0/"
    photoshop:Credit="Persian Gulf Survey"
    xmpRights:WebStatement="https://example.org/rights">
   <dc:creator><rdf:Seq><rdf:li>مریم احمدی</rdf:li><rdf:li>Jane Doe</rdf:li></rdf:Seq></dc:creator>
   <dc:rights><rdf:Alt><rdf:li xml:lang="x-default">© 2026 مریم احمدی</rdf:li><rdf:li xml:lang="en">© 2026 Maryam Ahmadi</rdf:li></rdf:Alt></dc:rights>
   <xmpRights:UsageTerms><rdf:Alt><rdf:li xml:lang="x-default">CC BY-SA 4.0</rdf:li></rdf:Alt></xmpRights:UsageTerms>
   <cc:license rdf:resource="https://creativecommons.org/licenses/by-sa/4.0/"/>
   <photoshop:DateCreated>2024-05-01T10:20:30+03:30</photoshop:DateCreated>
  </rdf:Description>
 </rdf:RDF>
</x:xmpmeta>
<?xpacket end="w"?>`

func TestAttributionReadsEveryXMPForm(t *testing.T) {
	a := attributionFrom(nil, []byte(xmpPacket))
	if a == nil {
		t.Fatal("no attribution")
	}
	want := Attribution{
		Author:     "مریم احمدی; Jane Doe",
		Credit:     "Persian Gulf Survey",
		Copyright:  "© 2026 مریم احمدی",
		License:    "CC BY-SA 4.0",
		LicenseURL: "https://creativecommons.org/licenses/by-sa/4.0/",
		CreatedAt:  "2024-05-01T10:20:30+03:30",
	}
	if *a != want {
		t.Errorf("got  %+v\nwant %+v", *a, want)
	}
}

func TestXMPWinsOverEXIFAndEXIFFillsTheGaps(t *testing.T) {
	exif := map[string]string{
		"exif-ifd0-Artist":           "Camera Owner (Camera Owner, ASCII, 13 components, 13 bytes)",
		"exif-ifd0-Copyright":        "(C) Owner (C) Owner, ASCII, 10 components, 10 bytes)", // malformed: ignored
		"exif-ifd2-DateTimeOriginal": "2024:05:01 10:20:30 (2024:05:01 10:20:30, ASCII, 20 components, 20 bytes)",
		// Never read, whatever is in them.
		"exif-ifd3-GPSLatitude": "26 deg 32' (26 deg 32', ASCII, 11 components, 11 bytes)",
		"exif-ifd0-Make":        "Canon (Canon, ASCII, 6 components, 6 bytes)",
	}
	xmp := `<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
	  <rdf:Description xmlns:dc="http://purl.org/dc/elements/1.1/">
	    <dc:creator><rdf:Seq><rdf:li>مریم احمدی</rdf:li></rdf:Seq></dc:creator>
	  </rdf:Description></rdf:RDF></x:xmpmeta>`

	a := attributionFrom(exif, []byte(xmp))
	if a == nil {
		t.Fatal("no attribution")
	}
	if a.Author != "مریم احمدی" {
		t.Errorf("author = %q, want the XMP creator over the EXIF artist", a.Author)
	}
	if a.CreatedAt != "2024-05-01T10:20:30" {
		t.Errorf("created_at = %q, want the EXIF date", a.CreatedAt)
	}
	if a.Copyright != "" {
		t.Errorf("a malformed EXIF field was read: %q", a.Copyright)
	}

	// The only fields there are: nothing about location or the camera.
	if got := strings.ToLower(a.Author + a.Credit + a.Copyright + a.License + a.LicenseURL + a.CreatedAt); strings.Contains(got, "deg") || strings.Contains(got, "canon") {
		t.Errorf("location or camera data leaked: %+v", *a)
	}
}

func TestAFileThatSaysNothingHasNoAttribution(t *testing.T) {
	for name, xmp := range map[string]string{
		"nothing":       "",
		"not xml":       "<<<",
		"other fields":  `<rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><rdf:Description xmlns:tiff="http://ns.adobe.com/tiff/1.0/" tiff:Make="Canon"/></rdf:RDF>`,
		"blank creator": `<rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><rdf:Description xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:creator><rdf:Seq><rdf:li>  </rdf:li></rdf:Seq></dc:creator></rdf:Description></rdf:RDF>`,
	} {
		if a := attributionFrom(map[string]string{}, []byte(xmp)); a != nil {
			t.Errorf("%s: got %+v, want nil", name, *a)
		}
	}
}

func TestCleanKeepsEachFieldToOneShortLine(t *testing.T) {
	if got := clean("  Jane\n\tDoe \u0007 "); got != "Jane Doe" {
		t.Errorf("clean = %q", got)
	}
	long := strings.Repeat("ب", 1000)
	if got := []rune(clean(long)); len(got) != maxAttributionRunes {
		t.Errorf("clean kept %d runes, want %d", len(got), maxAttributionRunes)
	}
}
