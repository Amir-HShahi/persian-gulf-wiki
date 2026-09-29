//go:build !vips

package imagepipe

import "context"

// This file is what compiles on a machine without libvips. It exists so the
// rest of the worker still builds, tests and runs locally — the image handler
// simply is not registered.
//
// Refusing the work is deliberate: a fallback that resized with the standard
// library would ignore ICC colour profiles, resize in sRGB instead of linear
// light, and support neither HEIC input nor WebP output. Quietly producing
// worse images is a worse outcome than not producing them.

// Available reports whether this binary can process images.
func Available() bool { return false }

func Start() error { return ErrUnavailable }

func Stop() {}

func Inspect(string, Options) (Info, *Rejection, error) {
	return Info{}, nil, ErrUnavailable
}

func Process(context.Context, string, string, Options) (Result, error) {
	return Result{}, ErrUnavailable
}
