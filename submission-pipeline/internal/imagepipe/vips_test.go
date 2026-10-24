//go:build vips

package imagepipe

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/binary"
	"errors"
	"hash/crc32"
	"image"
	"image/color"
	"image/jpeg"
	"image/png"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/davidbyttow/govips/v2/vips"
)

func TestMain(m *testing.M) {
	if err := Start(); err != nil {
		panic(err)
	}
	code := m.Run()
	Stop()
	os.Exit(code)
}

// writeJPEG writes a w x h JPEG. A non-zero orientation is recorded in an EXIF
// segment exactly as a phone records it: the pixels stay as the sensor read
// them and only the tag says how to turn the picture.
func writeJPEG(t *testing.T, dir, name string, w, h, orientation int) string {
	t.Helper()

	img := image.NewRGBA(image.Rect(0, 0, w, h))
	for y := 0; y < h; y++ {
		for x := 0; x < w; x++ {
			img.Set(x, y, color.RGBA{uint8(x * 255 / w), uint8(y * 255 / h), 128, 255})
		}
	}

	var buf bytes.Buffer
	if err := jpeg.Encode(&buf, img, &jpeg.Options{Quality: 85}); err != nil {
		t.Fatal(err)
	}
	data := buf.Bytes()

	if orientation > 0 {
		exif := []byte{
			0xFF, 0xE1, 0x00, 0x22, 'E', 'x', 'i', 'f', 0, 0,
			'M', 'M', 0x00, 0x2A, 0, 0, 0, 8,
			0x00, 0x01,
			0x01, 0x12, 0x00, 0x03, 0, 0, 0, 1, 0x00, byte(orientation), 0, 0,
			0, 0, 0, 0,
		}
		// The segment goes straight after the SOI marker.
		data = append(append(append([]byte{}, data[:2]...), exif...), data[2:]...)
	}

	path := filepath.Join(dir, name)
	if err := os.WriteFile(path, data, 0o644); err != nil {
		t.Fatal(err)
	}
	return path
}

// writeTransparentPNG writes a PNG whose left half is fully transparent.
func writeTransparentPNG(t *testing.T, dir, name string, w, h int) string {
	t.Helper()

	img := image.NewNRGBA(image.Rect(0, 0, w, h))
	for y := 0; y < h; y++ {
		for x := w / 2; x < w; x++ {
			img.Set(x, y, color.NRGBA{20, 160, 60, 255})
		}
	}

	path := filepath.Join(dir, name)
	f, err := os.Create(path)
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	if err := png.Encode(f, img); err != nil {
		t.Fatal(err)
	}
	return path
}

// writeBombPNG writes a few dozen bytes that declare a w x h image. There is no
// pixel data: the header is all the guard is allowed to read.
func writeBombPNG(t *testing.T, dir, name string, w, h uint32) string {
	t.Helper()

	var b bytes.Buffer
	b.Write([]byte{0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A})
	chunk := func(typ string, data []byte) {
		binary.Write(&b, binary.BigEndian, uint32(len(data)))
		td := append([]byte(typ), data...)
		b.Write(td)
		binary.Write(&b, binary.BigEndian, crc32.ChecksumIEEE(td))
	}
	ihdr := make([]byte, 13)
	binary.BigEndian.PutUint32(ihdr[0:], w)
	binary.BigEndian.PutUint32(ihdr[4:], h)
	ihdr[8], ihdr[9] = 8, 2 // 8 bit RGB
	chunk("IHDR", ihdr)
	chunk("IDAT", []byte{0x78, 0x9c, 0x00, 0x00}) // present so the header read succeeds; never decoded
	chunk("IEND", nil)

	path := filepath.Join(dir, name)
	if err := os.WriteFile(path, b.Bytes(), 0o644); err != nil {
		t.Fatal(err)
	}
	return path
}

// outDir makes the directory Process writes into. Process does not create it:
// the worker and the CLI each own that, and a missing one is the caller's bug.
func outDir(t *testing.T, dir string) string {
	t.Helper()
	out := filepath.Join(dir, "out")
	if err := os.MkdirAll(out, 0o755); err != nil {
		t.Fatal(err)
	}
	return out
}

// quick keeps the encoder cheap; quality is not what these tests are about.
func quick(widths ...int) Options {
	opt := DefaultOptions()
	opt.Widths = widths
	opt.Effort = 0
	return opt
}

func TestProcessSizesAPhoneShotByItsDisplayedShape(t *testing.T) {
	dir := t.TempDir()

	// Stored 1600x1200 and tagged "rotate 90 degrees": shown as 1200x1600
	// portrait. Sizing from the stored shape fits it into a 300x225 box and
	// returns a 169x225 image instead of 300x400.
	src := writeJPEG(t, dir, "portrait.jpg", 1600, 1200, 6)

	res, err := Process(context.Background(), src, outDir(t, dir), quick(300, 600))
	if err != nil {
		t.Fatal(err)
	}

	if res.Source.Width != 1200 || res.Source.Height != 1600 {
		t.Errorf("source = %dx%d, want the displayed 1200x1600", res.Source.Width, res.Source.Height)
	}

	want := map[string][2]int{"w300": {300, 400}, "w600": {600, 800}}
	if len(res.Variants) != len(want) {
		t.Fatalf("got %d variants, want %d", len(res.Variants), len(want))
	}
	for _, v := range res.Variants {
		w, ok := want[v.Label]
		if !ok {
			t.Errorf("unexpected variant %q", v.Label)
			continue
		}
		if v.Width != w[0] || v.Height != w[1] {
			t.Errorf("%s = %dx%d, want %dx%d", v.Label, v.Width, v.Height, w[0], w[1])
		}
	}
}

func TestProcessHitsTheTargetWidthExactly(t *testing.T) {
	dir := t.TempDir()
	src := writeJPEG(t, dir, "wide.jpg", 3000, 2000, 0)

	res, err := Process(context.Background(), src, outDir(t, dir), quick(400))
	if err != nil {
		t.Fatal(err)
	}
	if got := res.Variants[0].Width; got != 400 {
		t.Errorf("width = %d, want 400", got)
	}
}

func TestProcessStripsMetadataFromVariants(t *testing.T) {
	dir := t.TempDir()
	src := writeJPEG(t, dir, "tagged.jpg", 800, 600, 6)

	res, err := Process(context.Background(), src, outDir(t, dir), quick(200))
	if err != nil {
		t.Fatal(err)
	}

	out, err := vips.NewImageFromFile(res.Variants[0].Path)
	if err != nil {
		t.Fatal(err)
	}
	defer out.Close()

	// The turn was applied to the pixels, so a surviving tag would make a
	// viewer turn the picture a second time.
	if o := out.Orientation(); o > 1 {
		t.Errorf("output still carries orientation %d", o)
	}
}

func TestProcessKeepsTransparency(t *testing.T) {
	dir := t.TempDir()
	src := writeTransparentPNG(t, dir, "logo.png", 800, 400)

	res, err := Process(context.Background(), src, outDir(t, dir), quick(200))
	if err != nil {
		t.Fatal(err)
	}
	if !res.Source.HasAlpha {
		t.Error("source reported as opaque")
	}

	out, err := vips.NewImageFromFile(res.Variants[0].Path)
	if err != nil {
		t.Fatal(err)
	}
	defer out.Close()
	if !out.HasAlpha() {
		t.Error("variant lost its alpha channel")
	}
}

func TestProcessRejectsWhatItCannotOrShouldNotDecode(t *testing.T) {
	dir := t.TempDir()

	garbage := filepath.Join(dir, "notanimage.jpg")
	if err := os.WriteFile(garbage, []byte("this is not a picture"), 0o644); err != nil {
		t.Fatal(err)
	}
	damaged := filepath.Join(dir, "damaged.jpg")
	if err := os.WriteFile(damaged, append([]byte{0xFF, 0xD8, 0xFF, 0xE0}, make([]byte, 64)...), 0o644); err != nil {
		t.Fatal(err)
	}

	cases := []struct {
		name string
		path string
		want string
	}{
		{"not an image", garbage, RejectUnsupported},
		// Starts like a JPEG, then nothing a decoder can read.
		{"a damaged JPEG", damaged, RejectCorrupt},
		// Past MaxDimension on both sides: refused from the header alone.
		{"1.6 gigapixel header", writeBombPNG(t, dir, "huge.png", 40000, 40000), RejectTooLarge},
		// Under MaxDimension but over MaxPixels, so only the pixel count catches it.
		{"400 megapixel header", writeBombPNG(t, dir, "big.png", 20000, 20000), RejectTooLarge},
	}

	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			_, err := Process(context.Background(), c.path, outDir(t, dir), DefaultOptions())

			var rej *Rejection
			if !errors.As(err, &rej) {
				t.Fatalf("err = %v, want a *Rejection", err)
			}
			if rej.Code != c.want {
				t.Errorf("Code = %q, want %q", rej.Code, c.want)
			}
		})
	}
}

func TestProcessGivesATinyImageOneVariantAtItsOwnSize(t *testing.T) {
	dir := t.TempDir()
	// Narrower than every target. Nothing may be upscaled, but the submission
	// must still end up with a picture it can show.
	src := writeJPEG(t, dir, "tiny.jpg", 100, 75, 0)

	res, err := Process(context.Background(), src, outDir(t, dir), quick(320, 640))
	if err != nil {
		t.Fatal(err)
	}

	if len(res.Variants) != 1 {
		t.Fatalf("got %d variants, want 1", len(res.Variants))
	}
	if v := res.Variants[0]; v.Label != "w100" || v.Width != 100 || v.Height != 75 {
		t.Errorf("variant = %s %dx%d, want w100 100x75", v.Label, v.Width, v.Height)
	}
}

func TestProcessReturnsAPlaceholderCoreAccepts(t *testing.T) {
	dir := t.TempDir()
	// Portrait by EXIF, so the placeholder must follow the displayed shape too.
	src := writeJPEG(t, dir, "portrait.jpg", 1600, 1200, 6)

	res, err := Process(context.Background(), src, outDir(t, dir), quick(300))
	if err != nil {
		t.Fatal(err)
	}

	// Core's check (MediaProcessingService): a raster data URI, at most 16 KiB.
	const prefix = "data:image/webp;base64,"
	if !strings.HasPrefix(res.Placeholder, prefix) {
		t.Fatalf("placeholder = %.40q, want a %s URI", res.Placeholder, prefix)
	}
	if len(res.Placeholder) > MaxPlaceholderBytes {
		t.Errorf("placeholder is %d bytes, over core's %d", len(res.Placeholder), MaxPlaceholderBytes)
	}

	raw, err := base64.StdEncoding.DecodeString(strings.TrimPrefix(res.Placeholder, prefix))
	if err != nil {
		t.Fatalf("placeholder is not base64: %v", err)
	}
	img, err := vips.NewImageFromBuffer(raw)
	if err != nil {
		t.Fatalf("placeholder does not decode: %v", err)
	}
	defer img.Close()
	if img.Width() != 24 || img.Height() != 32 {
		t.Errorf("placeholder is %dx%d, want 24x32 (the portrait shape)", img.Width(), img.Height())
	}
	if exif := img.GetExif(); len(exif) > 0 {
		t.Errorf("placeholder carries metadata: %v", exif)
	}
}

func TestProcessOmitsThePlaceholderWhenTurnedOff(t *testing.T) {
	dir := t.TempDir()
	src := writeJPEG(t, dir, "a.jpg", 400, 300, 0)

	opt := quick(300)
	opt.PlaceholderWidth = 0
	res, err := Process(context.Background(), src, outDir(t, dir), opt)
	if err != nil {
		t.Fatal(err)
	}
	if res.Placeholder != "" {
		t.Errorf("placeholder produced although turned off")
	}
}

func TestProcessMakesPanoramaVariantsByCoreLabels(t *testing.T) {
	dir := t.TempDir()
	src := writeJPEG(t, dir, "pano.jpg", 4096, 2048, 0)

	opt := PanoramaOptions()
	opt.Effort = 0
	res, err := Process(context.Background(), src, outDir(t, dir), opt)
	if err != nil {
		t.Fatal(err)
	}

	// 8k would be an upscale; 4k is the source's own width and is kept.
	want := map[string][2]int{"2k": {2048, 1024}, "4k": {4096, 2048}}
	if len(res.Variants) != len(want) {
		t.Fatalf("got %d variants, want %d: %+v", len(res.Variants), len(want), res.Variants)
	}
	for _, v := range res.Variants {
		w, ok := want[v.Label]
		if !ok {
			t.Errorf("unexpected variant %q", v.Label)
			continue
		}
		if v.Width != w[0] || v.Height != w[1] {
			t.Errorf("%s = %dx%d, want %dx%d", v.Label, v.Width, v.Height, w[0], w[1])
		}
		if filepath.Base(v.Path) != v.Label+".webp" {
			t.Errorf("%s written as %s", v.Label, filepath.Base(v.Path))
		}
	}
	if res.Placeholder == "" {
		t.Error("no placeholder for a panorama")
	}
}

func TestProcessRejectsAPanoramaThatIsNotTwoToOne(t *testing.T) {
	dir := t.TempDir()
	src := writeJPEG(t, dir, "photo.jpg", 3000, 2000, 0)

	_, err := Process(context.Background(), src, outDir(t, dir), PanoramaOptions())
	var rej *Rejection
	if !errors.As(err, &rej) || rej.Code != RejectAspect {
		t.Fatalf("err = %v, want a %q rejection", err, RejectAspect)
	}
}

func TestProcessReadsTheAuthorAndLicenceBeforeStripping(t *testing.T) {
	dir := t.TempDir()

	// A photo as a camera and an editor leave it: EXIF from the camera, XMP
	// with a Persian name and a Creative Commons licence from the editor, and a
	// location, which must not come out anywhere.
	img, err := vips.Black(800, 600)
	if err != nil {
		t.Fatal(err)
	}
	defer img.Close()
	img.SetString("exif-ifd0-Artist", "Camera Owner")
	img.SetString("exif-ifd2-DateTimeOriginal", "2024:05:01 10:20:30")
	img.SetString("exif-ifd3-GPSLatitudeRef", "N")
	img.SetBlob("xmp-data", []byte(xmpPacket))
	params := vips.NewJpegExportParams()
	params.StripMetadata = false
	buf, _, err := img.ExportJpeg(params)
	if err != nil {
		t.Fatal(err)
	}
	src := filepath.Join(dir, "tagged.jpg")
	if err := os.WriteFile(src, buf, 0o644); err != nil {
		t.Fatal(err)
	}

	res, err := Process(context.Background(), src, outDir(t, dir), quick(320))
	if err != nil {
		t.Fatal(err)
	}
	a := res.Attribution
	if a == nil {
		t.Fatal("no attribution read from a tagged photo")
	}
	if a.Author != "مریم احمدی; Jane Doe" || a.License != "CC BY-SA 4.0" ||
		a.LicenseURL != "https://creativecommons.org/licenses/by-sa/4.0/" {
		t.Errorf("attribution = %+v", *a)
	}

	// Read from the original only: the variant still carries nothing.
	v, err := vips.NewImageFromFile(res.Variants[0].Path)
	if err != nil {
		t.Fatal(err)
	}
	defer v.Close()
	for _, f := range v.ImageFields() {
		if f == "xmp-data" || f == "exif-data" {
			t.Errorf("variant keeps %s", f)
		}
	}
}

func TestProcessGivesAnUntaggedPhotoNoAttribution(t *testing.T) {
	dir := t.TempDir()
	res, err := Process(context.Background(), writeJPEG(t, dir, "plain.jpg", 400, 300, 0), outDir(t, dir), quick(320))
	if err != nil {
		t.Fatal(err)
	}
	if res.Attribution != nil {
		t.Errorf("attribution = %+v, want nil", *res.Attribution)
	}
}
