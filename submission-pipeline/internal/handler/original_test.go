package handler

import "testing"

func TestOriginalTypeIsDecidedByContent(t *testing.T) {
	heic := []byte{0, 0, 0, 24, 'f', 't', 'y', 'p', 'h', 'e', 'i', 'c'}
	avif := []byte{0, 0, 0, 24, 'f', 't', 'y', 'p', 'a', 'v', 'i', 'f'}
	avis := []byte{0, 0, 0, 24, 'f', 't', 'y', 'p', 'a', 'v', 'i', 's'}

	cases := []struct {
		name        string
		format      string
		head        []byte
		wantExt     string
		wantContent string
	}{
		{"jpeg", "jpeg", nil, "jpg", "image/jpeg"},
		{"png", "png", nil, "png", "image/png"},
		{"webp", "webp", nil, "webp", "image/webp"},
		{"gif", "gif", nil, "gif", "image/gif"},
		{"tiff", "tiff", nil, "tif", "image/tiff"},
		// One libvips name, two real formats: only the brand separates them.
		{"heic", "heif", heic, "heic", "image/heic"},
		{"avif", "heif", avif, "avif", "image/avif"},
		{"avif sequence", "heif", avis, "avif", "image/avif"},
		{"heif with a header too short to read", "heif", []byte{0, 0}, "heic", "image/heic"},
		{"something else libvips can load", "svg", nil, "bin", "application/octet-stream"},
	}

	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			ext, content := originalType(c.format, c.head)
			if ext != c.wantExt || content != c.wantContent {
				t.Errorf("originalType(%q) = %q, %q; want %q, %q",
					c.format, ext, content, c.wantExt, c.wantContent)
			}
		})
	}
}
