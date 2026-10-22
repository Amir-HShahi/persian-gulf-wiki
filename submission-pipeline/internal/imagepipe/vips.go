//go:build vips

package imagepipe

import (
	"context"
	"encoding/base64"
	"fmt"
	"io"
	"os"
	"path/filepath"

	"github.com/davidbyttow/govips/v2/vips"
)

func Available() bool { return true }

// Start initialises libvips. Call once at process startup; Stop on the way out.
func Start() error {
	vips.LoggingSettings(nil, vips.LogLevelWarning)
	vips.Startup(&vips.Config{
		// One thread per operation. The worker already runs Concurrency jobs in
		// parallel, and letting libvips also fan out per image would
		// oversubscribe the box and make everything slower.
		ConcurrencyLevel: 1,
	})
	return nil
}

func Stop() { vips.Shutdown() }

// Inspect reads the header and applies the size limits without decoding pixels.
//
// libvips is lazy: opening a file reads its header and nothing more, so an
// image that declares absurd dimensions costs one header read rather than
// gigabytes of allocation.
func Inspect(srcPath string, opt Options) (Info, *Rejection, error) {
	img, err := vips.NewImageFromFile(srcPath)
	if err != nil {
		// Unreadable now is unreadable on a retry too, so it is a rejection.
		// The first bytes tell the two cases apart for the uploader: a file
		// that does not start like any image format is the wrong kind of
		// file, one that does is a damaged image.
		return Info{}, &Rejection{Code: unreadableCode(srcPath)}, nil
	}
	defer img.Close()

	// Width and Height are as the image is displayed, not as it is stored.
	// Phones keep every photo in sensor orientation and record the turn in the
	// EXIF orientation tag, so a portrait shot is stored landscape. Orientations
	// 5 to 8 are the quarter turns; reading the stored size there would size every
	// variant for a landscape image that is then rotated into a much smaller box.
	width, height := img.Width(), img.Height()
	if o := img.Orientation(); o >= 5 && o <= 8 {
		width, height = height, width
	}

	info := Info{
		Width:    width,
		Height:   height,
		Format:   vips.ImageTypes[img.Format()],
		HasAlpha: img.HasAlpha(),
	}
	return info, check(info, opt), nil
}

// unreadableCode is RejectUnsupported when the file's leading bytes match no
// image format libvips knows, and RejectCorrupt when they do.
func unreadableCode(srcPath string) string {
	f, err := os.Open(srcPath)
	if err != nil {
		return RejectCorrupt
	}
	defer f.Close()

	head := make([]byte, 512)
	n, _ := io.ReadFull(f, head)
	if vips.DetermineImageType(head[:n]) == vips.ImageTypeUnknown {
		return RejectUnsupported
	}
	return RejectCorrupt
}

// Process writes one WebP variant per planned width into outDir.
//
// Each variant is produced from the source file rather than from the previous
// variant: resizing an already-resized image compounds the softening, and
// libvips can shrink-on-load straight from the original, which is both faster
// and sharper.
func Process(ctx context.Context, srcPath, outDir string, opt Options) (Result, error) {
	info, rejection, err := Inspect(srcPath, opt)
	if err != nil {
		return Result{}, err
	}
	if rejection != nil {
		return Result{}, rejection
	}

	widths := plan(opt.Widths, info.Width)
	result := Result{Source: info, Variants: make([]Variant, 0, len(widths))}

	for _, w := range widths {
		if err := ctx.Err(); err != nil {
			return Result{}, err
		}

		v, err := variant(srcPath, outDir, w, info, opt)
		if err != nil {
			return Result{}, fmt.Errorf("building %s variant: %w", label(w), err)
		}
		result.Variants = append(result.Variants, v)
	}

	if opt.PlaceholderWidth > 0 {
		p, err := placeholder(srcPath, info, opt.PlaceholderWidth)
		if err != nil {
			return Result{}, fmt.Errorf("building placeholder: %w", err)
		}
		if len(p) <= MaxPlaceholderBytes {
			result.Placeholder = p
		}
	}

	return result, nil
}

// placeholder renders the image width pixels wide as a low-quality WebP data
// URI. No blur is applied here: stretched to full size by the browser, an image
// this small is already a smooth blur, and blurring it first would only cost
// bytes. Colour and orientation are handled exactly as for a variant, and
// metadata is stripped, so the preview leaks no more than the variants do.
func placeholder(srcPath string, info Info, width int) (string, error) {
	img, err := vips.NewThumbnailWithSizeFromFile(
		srcPath, width, scaledHeight(info.Width, info.Height, width), vips.InterestingNone, vips.SizeDown,
	)
	if err != nil {
		return "", err
	}
	defer img.Close()

	if err := img.OptimizeICCProfile(); err != nil {
		return "", fmt.Errorf("converting colour profile: %w", err)
	}

	params := vips.NewWebpExportParams()
	params.Quality = 40
	params.StripMetadata = true
	buf, _, err := img.ExportWebp(params)
	if err != nil {
		return "", err
	}
	return "data:image/webp;base64," + base64.StdEncoding.EncodeToString(buf), nil
}

func variant(srcPath, outDir string, width int, info Info, opt Options) (Variant, error) {
	height := scaledHeight(info.Width, info.Height, width)

	// vips_thumbnail shrinks on load where the format allows it, applies the
	// EXIF orientation, and never scales up because of SizeDown. InterestingNone
	// fits the image inside the box instead of cropping it: cropping throws away
	// detail permanently, and the frontend can always crop with CSS, whereas it
	// can never un-crop.
	img, err := vips.NewThumbnailWithSizeFromFile(
		srcPath, width, height, vips.InterestingNone, vips.SizeDown,
	)
	if err != nil {
		return Variant{}, err
	}
	defer img.Close()

	// Convert through the embedded ICC profile to sRGB. Without this, a photo
	// shot in Display P3 or Adobe RGB — which most phones and cameras produce —
	// is served as if its numbers were sRGB, and the colours come out wrong.
	if err := img.OptimizeICCProfile(); err != nil {
		return Variant{}, fmt.Errorf("converting colour profile: %w", err)
	}

	params := vips.NewWebpExportParams()
	params.Quality = opt.Quality
	params.ReductionEffort = opt.Effort
	// Strips EXIF, including the GPS coordinates a phone writes into every
	// photo. Publishing a contributor's home location would be a real harm, not
	// an untidiness. Orientation has already been applied above, so dropping the
	// tag now is safe.
	params.StripMetadata = true

	buf, _, err := img.ExportWebp(params)
	if err != nil {
		return Variant{}, err
	}

	path := filepath.Join(outDir, label(width)+".webp")
	if err := os.WriteFile(path, buf, 0o644); err != nil {
		return Variant{}, err
	}

	return Variant{
		Label:  label(width),
		Width:  img.Width(),
		Height: img.Height(),
		Path:   path,
		Bytes:  int64(len(buf)),
	}, nil
}
