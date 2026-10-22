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
	"math"
)

// ErrUnavailable is returned when the binary was built without image support.
var ErrUnavailable = errors.New("imagepipe: built without libvips support")

// Rejection codes. They are core's own (MediaFailureCode), so each one reaches
// the uploader as a translated message in fa, en and ar; a code core does not
// know would be shown as a generic "rejected". A new code has to be added on
// core's side first.
//
// Both size limits share RejectTooLarge, as core has one message for them;
// Detail["limit"] says which one it was, for the logs.
const (
	RejectCorrupt     = "corrupt_file"
	RejectUnsupported = "unsupported_format"
	RejectTooLarge    = "dimensions_too_large"
	RejectAspect      = "panorama_aspect_ratio"
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

	// PlaceholderWidth is the width of the inline preview returned with the
	// variants. Zero means no placeholder.
	PlaceholderWidth int

	// Labels names the variant of a width, which is also its file name. A
	// width with no entry is named "w" and its width, as photo variants are.
	Labels map[int]string

	// AspectRatio, when not zero, is the width-to-height ratio every image
	// must have, give or take AspectTolerance. Panoramas use it: an
	// equirectangular 360 degree image is exactly 2:1, and anything else would
	// be drawn distorted by the viewer.
	AspectRatio     float64
	AspectTolerance float64
}

// MaxPlaceholderBytes is the longest data URI core stores as a placeholder. A
// placeholder that would be longer is left out rather than refused: it is an
// optimisation, and the image is usable without one.
const MaxPlaceholderBytes = 16 * 1024

func DefaultOptions() Options {
	return Options{
		// Thumbnail, card, article body and large view. 1920 is the sharpest
		// variant ever generated, and it is enough: the original is never
		// modified and stays in the raw bucket for anything that needs full
		// resolution, so there is no need for a larger derived size.
		Widths: []int{320, 640, 1280, 1920},

		// 100 MP rejects any plausible bomb (a 40,000 x 40,000 image is 1.6
		// gigapixels) while still accepting a high-end scan, which is about
		// 11,500 x 8,600. libvips streams rather than holding width x height x 4
		// bytes, which is what makes a limit this generous safe.
		MaxPixels:    100_000_000,
		MaxDimension: 30_000,

		Quality: 85,
		Effort:  6,

		// Small enough to be a few hundred bytes inline, large enough that the
		// browser's smoothing turns it into a recognisable blur of the photo.
		PlaceholderWidth: 24,
	}
}

// PanoramaOptions is DefaultOptions for 360 degree panoramas, as core's
// contract (docs/MEDIA_PIPELINE.md) sets them: 2:1, and variants 2k, 4k and 8k
// wide, named by those labels.
func PanoramaOptions() Options {
	opt := DefaultOptions()
	opt.Widths = []int{2048, 4096, 8192}
	opt.Labels = map[int]string{2048: "2k", 4096: "4k", 8192: "8k"}
	// 16384 x 8192 is the largest panorama a viewer is expected to take, and is
	// 134 MP: over the photo limit, so panoramas get a budget of their own.
	opt.MaxPixels = 135_000_000
	opt.AspectRatio = 2
	opt.AspectTolerance = 0.01
	return opt
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
//
// Placeholder is a tiny WebP of the whole image as a data URI, for the
// frontend to show stretched while the real variant loads. It is empty when
// Options.PlaceholderWidth is zero or the URI would exceed MaxPlaceholderBytes.
type Result struct {
	Source      Info      `json:"source"`
	Variants    []Variant `json:"variants"`
	Placeholder string    `json:"placeholder,omitempty"`
}

// check applies the size limits to an already-read header. Returns nil when the
// image is acceptable.
func check(info Info, opt Options) *Rejection {
	if info.Width <= 0 || info.Height <= 0 {
		return &Rejection{Code: RejectCorrupt}
	}

	if opt.MaxDimension > 0 && (info.Width > opt.MaxDimension || info.Height > opt.MaxDimension) {
		return &Rejection{
			Code: RejectTooLarge,
			Detail: map[string]string{
				"limit":  "side",
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
					"limit":  "pixels",
					"width":  itoa(info.Width),
					"height": itoa(info.Height),
					"pixels": itoa64(pixels),
					"max":    itoa64(opt.MaxPixels),
				},
			}
		}
	}

	if opt.AspectRatio > 0 {
		ratio := float64(info.Width) / float64(info.Height)
		if math.Abs(ratio-opt.AspectRatio) > opt.AspectTolerance {
			return &Rejection{
				Code: RejectAspect,
				Detail: map[string]string{
					"width":  itoa(info.Width),
					"height": itoa(info.Height),
					"ratio":  fmt.Sprintf("%.3f", ratio),
				},
			}
		}
	}

	return nil
}

// plan returns the widths worth generating for a source of the given width.
//
// A target above the source is dropped: upscaling invents no detail, it only
// produces a larger file that looks softer than the original. A target equal
// to the source is kept, because that is a re-encode, not an upscale, and the
// original is private: without it a 4096 px panorama would have no 4k variant.
//
// A source narrower than every target would leave nothing, and the original is
// private, so the submission would have no picture to show at all. It gets one
// variant at its own width instead: still no upscaling, but a stripped,
// colour-corrected WebP that is safe to serve.
func plan(widths []int, srcWidth int) []int {
	out := make([]int, 0, len(widths))
	for _, w := range widths {
		if w > 0 && w <= srcWidth {
			out = append(out, w)
		}
	}
	if len(out) == 0 && srcWidth > 0 {
		out = append(out, srcWidth)
	}
	return out
}

// scaledHeight returns the height a source scales to when fitted to a target
// width, preserving aspect ratio and never returning zero.
//
// It rounds up. The result is the height of a box the image is fitted inside,
// and a box one pixel short makes the width the limiting side, so a 400 px
// target came out 399 wide.
func scaledHeight(srcW, srcH, targetW int) int {
	if srcW <= 0 {
		return 0
	}
	h := int(math.Ceil(float64(srcH) * (float64(targetW) / float64(srcW))))
	if h < 1 {
		h = 1
	}
	return h
}

func label(opt Options, width int) string {
	if l, ok := opt.Labels[width]; ok {
		return l
	}
	return "w" + itoa(width)
}

func itoa(n int) string     { return fmt.Sprintf("%d", n) }
func itoa64(n int64) string { return fmt.Sprintf("%d", n) }
