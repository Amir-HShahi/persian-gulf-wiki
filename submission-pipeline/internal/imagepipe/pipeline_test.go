package imagepipe

import (
	"reflect"
	"testing"
)

func TestPlanSkipsUpscaling(t *testing.T) {
	widths := []int{150, 400, 1200}

	cases := []struct {
		name     string
		srcWidth int
		want     []int
	}{
		{"large source keeps every width", 3000, []int{150, 400, 1200}},
		{"mid source drops the largest", 800, []int{150, 400}},
		{"small source keeps only the thumbnail", 300, []int{150}},
		{"tiny source produces nothing", 100, []int{}},
		{"source exactly on a target is not regenerated", 400, []int{150}},
	}

	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			got := plan(widths, c.srcWidth)
			if !reflect.DeepEqual(got, c.want) {
				t.Errorf("plan(%v, %d) = %v, want %v", widths, c.srcWidth, got, c.want)
			}
		})
	}
}

func TestCheckAcceptsOrdinaryImages(t *testing.T) {
	opt := DefaultOptions()
	// A 24 MP phone photo.
	if r := check(Info{Width: 6000, Height: 4000}, opt); r != nil {
		t.Errorf("expected acceptance, got %+v", r)
	}
	// A high-end scan, comfortably inside the limit.
	if r := check(Info{Width: 11500, Height: 8600}, opt); r != nil {
		t.Errorf("expected acceptance, got %+v", r)
	}
}

func TestCheckRejectsDecompressionBomb(t *testing.T) {
	opt := DefaultOptions()

	// The classic bomb: a few kilobytes on disk declaring 1.6 gigapixels.
	r := check(Info{Width: 40000, Height: 40000}, opt)
	if r == nil {
		t.Fatal("expected rejection")
	}
	// The dimension limit catches this one before the pixel count does, and
	// either verdict is terminal.
	if r.Code != RejectDimension && r.Code != RejectTooLarge {
		t.Errorf("Code = %q, want a size rejection", r.Code)
	}
}

func TestCheckRejectsOnPixelCountAlone(t *testing.T) {
	opt := DefaultOptions()

	// Both sides are under MaxDimension, so only the pixel count can catch it.
	r := check(Info{Width: 20000, Height: 20000}, opt)
	if r == nil {
		t.Fatal("expected rejection")
	}
	if r.Code != RejectTooLarge {
		t.Errorf("Code = %q, want %q", r.Code, RejectTooLarge)
	}
	if r.Detail["pixels"] != "400000000" {
		t.Errorf("Detail[pixels] = %q, want 400000000", r.Detail["pixels"])
	}
}

func TestCheckRejectsExtremeAspectRatio(t *testing.T) {
	opt := DefaultOptions()

	// 50,000 x 100 is only 5 MP, so MaxPixels lets it through. A single side
	// that long still breaks every downstream assumption.
	r := check(Info{Width: 50000, Height: 100}, opt)
	if r == nil {
		t.Fatal("expected rejection")
	}
	if r.Code != RejectDimension {
		t.Errorf("Code = %q, want %q", r.Code, RejectDimension)
	}
}

func TestCheckRejectsUnreadableHeader(t *testing.T) {
	if r := check(Info{Width: 0, Height: 0}, DefaultOptions()); r == nil || r.Code != RejectUnreadable {
		t.Errorf("got %+v, want %q", r, RejectUnreadable)
	}
}

func TestScaledHeightPreservesAspectRatio(t *testing.T) {
	cases := []struct {
		srcW, srcH, targetW, want int
	}{
		{3000, 2000, 1200, 800},
		{4000, 3000, 400, 300},
		{1000, 1000, 150, 150},
		// A wide panorama scaled down must never round to a zero-height image.
		{30000, 100, 150, 1},
	}
	for _, c := range cases {
		if got := scaledHeight(c.srcW, c.srcH, c.targetW); got != c.want {
			t.Errorf("scaledHeight(%d, %d, %d) = %d, want %d",
				c.srcW, c.srcH, c.targetW, got, c.want)
		}
	}
}

func TestDefaultOptionsAreSane(t *testing.T) {
	opt := DefaultOptions()

	if opt.Quality < 1 || opt.Quality > 100 {
		t.Errorf("Quality = %d, out of range", opt.Quality)
	}
	if opt.Effort < 0 || opt.Effort > 6 {
		t.Errorf("Effort = %d, out of range", opt.Effort)
	}
	// The pixel cap has to reject a bomb that slips past the dimension cap.
	if opt.MaxPixels >= int64(opt.MaxDimension)*int64(opt.MaxDimension) {
		t.Error("MaxPixels is so high that MaxDimension alone decides every case")
	}
}
