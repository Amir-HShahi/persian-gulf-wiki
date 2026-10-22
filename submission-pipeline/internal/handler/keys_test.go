package handler

import (
	"strings"
	"testing"
)

// Core refuses an ok result whose variant keys are not under
// pending/{mediaId}/, or that contain ".." or "//" (MediaProcessingService).
func TestVariantKeysAreWhereCoreAcceptsThem(t *testing.T) {
	const id = "9b1e7c3a-2d4f-4e8b-a1c6-7f5e3d2b1a09"
	key := variantKey(id, "w640.webp")
	if want := "pending/" + id + "/w640.webp"; key != want {
		t.Fatalf("variant key = %q, want %q", key, want)
	}
	if strings.Contains(key, "..") || strings.Contains(key, "//") {
		t.Errorf("variant key %q would be refused by core", key)
	}
}

// Core deletes pending/{mediaId}/ wholesale after publishing or rejecting an
// item, so the original must live outside it.
func TestTheOriginalIsKeptOutsideThePendingPrefix(t *testing.T) {
	const id = "9b1e7c3a-2d4f-4e8b-a1c6-7f5e3d2b1a09"
	key := originalKey(id, "heic")
	if want := "originals/" + id + "/original.heic"; key != want {
		t.Fatalf("original key = %q, want %q", key, want)
	}
	if strings.HasPrefix(key, "pending/") || strings.HasPrefix(key, "public/") {
		t.Errorf("original key %q is inside a prefix core manages", key)
	}
}
