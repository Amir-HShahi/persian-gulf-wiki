//go:build vips

package imagepipe

import (
	"bytes"
	"context"
	"encoding/binary"
	"errors"
	"hash/crc32"
	"image"
	"image/color"
	"image/jpeg"
	"image/png"
	"os"
	"path/filepath"
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

	cases := []struct {
		name string
		path string
		want string
	}{
		{"not an image", garbage, RejectUnreadable},
		// Past MaxDimension on both sides: refused from the header alone.
		{"1.6 gigapixel header", writeBombPNG(t, dir, "huge.png", 40000, 40000), RejectDimension},
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
