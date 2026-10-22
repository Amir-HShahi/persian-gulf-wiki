package handler

import (
	"os"
	"path/filepath"
	"testing"
)

func TestSHA256IsStandardPaddedBase64(t *testing.T) {
	p := filepath.Join(t.TempDir(), "f")
	if err := os.WriteFile(p, []byte("abc"), 0o600); err != nil {
		t.Fatal(err)
	}
	got, err := sha256Base64(p)
	if err != nil {
		t.Fatal(err)
	}
	// SHA-256("abc"), which is ba7816bf...15ad in hex.
	if want := "ungWv48Bz+pBQUDeXa4iI7ADYaOWF3qctBD/YfIAFa0="; got != want {
		t.Errorf("sha256Base64 = %q, want %q", got, want)
	}
	if !validDeclaredSHA256(got) {
		t.Errorf("its own output is not accepted as a declared value")
	}
}

func TestDeclaredSHA256MustBeBase64OfThirtyTwoBytes(t *testing.T) {
	for _, bad := range []string{
		"ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", // hex
		"ungWv48Bz+pBQUDeXa4iI7ADYaOWF3qctBD/YfIAFa0",                      // padding missing
		"YWJj", // base64, but of 3 bytes
		"not base64!",
	} {
		if validDeclaredSHA256(bad) {
			t.Errorf("%q accepted as a declared SHA-256", bad)
		}
	}
}
