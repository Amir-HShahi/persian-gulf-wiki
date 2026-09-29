// Package imagepipe validates submitted images and derives the variants the
// site serves. The original is never modified: it stays in the raw bucket
// byte-for-byte, and every variant is a new file.
//
// Decoding is the one place in this worker where untrusted bytes reach a
// parser that allocates in proportion to what they claim. A 10 KB PNG can
// declare 40,000 x 40,000 pixels, so the header is always read and checked
// before any pixel is touched.
//
// The heavy lifting is done by libvips, which is behind the "vips" build tag:
// it needs a C library present at build time, and requiring that on every
// developer machine would be worse than the alternative. A binary built
// without the tag reports Available() == false and refuses image work rather
// than doing it badly.
package imagepipe

import (
	"errors"
	"fmt"
)

// ErrUnavailable is returned when the binary was built without image support.
var ErrUnavailable = errors.New("imagepipe: built without libvips support")

// Rejection codes. Each is a stable identifier the frontend translates; none
// is ever an English sentence.
const (
	RejectUnreadable  = "image_unreadable"
	RejectTooLarge    = "image_too_many_pixels"
	RejectDimension   = "image_dimension_too_large"
	RejectUnsupported = "image_format_unsupported"
)

// Options controls validation limits and output quality.
type Options struct {
	// Widths are the sizes each variant is scaled to fit within. Height
	// follows the source aspect ratio.
	Widths []int

	// MaxPixels rejects an image whose width times height exceeds it. This is
	// the decompression-bomb guard and is checked from the header alone.
	MaxPixels int64

	// MaxDimension rejects an image with any single side longer than this,
	// which catches the extreme-aspect-ratio case that MaxPixels lets through.
	MaxDimension int

	// Quality is the WebP quality target, 1-100.
	Quality int

	// Effort is how hard the encoder works, 0-6. Higher is slower and produces
	// smaller files at the same visual quality. This runs in a background
	// worker where nobody is waiting, so the default is the maximum.
	Effort int
}

func DefaultOptions() Options {
	return Options{
		Widths: []int{150, 400, 1200},

		// 100 MP rejects any plausible bomb (a 40,000 x 40,000 image is 1.6
		// gigapixels) while still accepting a high-end scan, which is about
		// 11,500 x 8,600. libvips streams rather than holding width x height x 4
		// bytes, which is what makes a limit this generous safe.
		MaxPixels:    100_000_000,
		MaxDimension: 30_000,

		Quality: 85,
		Effort:  6,
	}
}

// Info describes the source image as read from its header.
type Info struct {
	Width    int    `json:"width"`
	Height   int    `json:"height"`
	Format   string `json:"format"`
	HasAlpha bool   `json:"has_alpha"`
}

// Rejection is a terminal verdict: this file is not something the pipeline can
// process, and it will be exactly the same on a retry.
type Rejection struct {
	Code   string            `json:"code"`
	Detail map[string]string `json:"detail,omitempty"`
}

func (r *Rejection) Error() string {
	return fmt.Sprintf("image rejected: %s", r.Code)
}

// Variant is one derived file written to the output directory.
type Variant struct {
	Label  string `json:"label"`
	Width  int    `json:"width"`
	Height int    `json:"height"`
	Path   string `json:"path"`
	Bytes  int64  `json:"bytes"`
}

// Result is what Process produced.
type Result struct {
	Source   Info      `json:"source"`
	Variants []Variant `json:"variants"`
}

// check applies the size limits to an already-read header. Returns nil when the
// image is acceptable.
func check(info Info, opt Options) *Rejection {
	if info.Width <= 0 || info.Height <= 0 {
		return &Rejection{Code: RejectUnreadable}
	}

	if opt.MaxDimension > 0 && (info.Width > opt.MaxDimension || info.Height > opt.MaxDimension) {
		return &Rejection{
			Code: RejectDimension,
			Detail: map[string]string{
				"width":  itoa(info.Width),
				"height": itoa(info.Height),
				"max":    itoa(opt.MaxDimension),
			},
		}
	}

	if opt.MaxPixels > 0 {
		pixels := int64(info.Width) * int64(info.Height)
		if pixels > opt.MaxPixels {
			return &Rejection{
				Code: RejectTooLarge,
				Detail: map[string]string{
					"width":  itoa(info.Width),
					"height": itoa(info.Height),
					"pixels": itoa64(pixels),
					"max":    itoa64(opt.MaxPixels),
				},
			}
		}
	}

	return nil
}

// plan returns the widths worth generating for a source of the given width.
//
// A target at or above the source is dropped: upscaling invents no detail, it
// only produces a larger file that looks softer than the original.
func plan(widths []int, srcWidth int) []int {
	out := make([]int, 0, len(widths))
	for _, w := range widths {
		if w > 0 && w < srcWidth {
			out = append(out, w)
		}
	}
	return out
}

// scaledHeight returns the height a source scales to when fitted to a target
// width, preserving aspect ratio and never returning zero.
func scaledHeight(srcW, srcH, targetW int) int {
	if srcW <= 0 {
		return 0
	}
	h := int(float64(srcH) * (float64(targetW) / float64(srcW)))
	if h < 1 {
		h = 1
	}
	return h
}

func label(width int) string {
	return "w" + itoa(width)
}

func itoa(n int) string     { return fmt.Sprintf("%d", n) }
func itoa64(n int64) string { return fmt.Sprintf("%d", n) }
