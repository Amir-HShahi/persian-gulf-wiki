// Command imgpipe runs one image through the same pipeline the worker uses and
// writes the variants to a directory. It exists to check a real photo — an
// iPhone HEIC, a Display P3 export, a CMYK scan — without enqueuing a job.
//
// It only does anything when built with the vips tag:
//
//	go build -tags vips ./cmd/imgpipe
package main

import (
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"os"
	"os/signal"
	"syscall"

	"wikipg/internal/imagepipe"
)

func main() {
	if err := run(); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}

func run() error {
	in := flag.String("in", "", "input image")
	out := flag.String("out", ".", "directory the variants are written to")
	quality := flag.Int("quality", 0, "WebP quality 1-100 (default: the pipeline's own)")
	flag.Parse()

	if *in == "" {
		return fmt.Errorf("-in is required")
	}

	// Without the build tag this binary carries the refusing stub, and every
	// call returns ErrUnavailable. Saying so here is clearer than letting the
	// first operation fail with what looks like a problem in the image.
	if !imagepipe.Available() {
		return fmt.Errorf("built without libvips support: rebuild with -tags vips")
	}

	opt := imagepipe.DefaultOptions()
	if *quality != 0 {
		if *quality < 1 || *quality > 100 {
			return fmt.Errorf("-quality must be between 1 and 100, got %d", *quality)
		}
		opt.Quality = *quality
	}

	if err := os.MkdirAll(*out, 0o755); err != nil {
		return fmt.Errorf("creating output directory: %w", err)
	}

	if err := imagepipe.Start(); err != nil {
		return err
	}
	defer imagepipe.Stop()

	// Ctrl-C cancels between variants rather than killing the process midway
	// through writing one, which would leave a truncated file in the output.
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	result, err := imagepipe.Process(ctx, *in, *out, opt)
	if err != nil {
		// A rejection is a verdict about the file, not a failure of the run,
		// so it is reported as structured output and not as an error string.
		var rejection *imagepipe.Rejection
		if errors.As(err, &rejection) {
			enc := json.NewEncoder(os.Stdout)
			enc.SetIndent("", "  ")
			if encErr := enc.Encode(rejection); encErr != nil {
				return encErr
			}
			os.Exit(2)
		}
		return err
	}

	enc := json.NewEncoder(os.Stdout)
	enc.SetIndent("", "  ")
	return enc.Encode(result)
}
