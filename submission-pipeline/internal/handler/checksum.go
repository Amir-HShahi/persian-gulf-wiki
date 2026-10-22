package handler

import (
	"crypto/sha256"
	"encoding/base64"
	"io"
	"os"
)

// sha256Base64 is the SHA-256 of a file, as standard padded base64: 44
// characters ending in one "=". That is the encoding core uses for
// declared_sha256 and expects back, not hex.
func sha256Base64(path string) (string, error) {
	f, err := os.Open(path)
	if err != nil {
		return "", err
	}
	defer f.Close()

	h := sha256.New()
	if _, err := io.Copy(h, f); err != nil {
		return "", err
	}
	return base64.StdEncoding.EncodeToString(h.Sum(nil)), nil
}

// validDeclaredSHA256 reports whether s is base64 of exactly 32 bytes. A value
// that is not cannot be compared, so it is a malformed payload rather than a
// mismatch.
func validDeclaredSHA256(s string) bool {
	b, err := base64.StdEncoding.DecodeString(s)
	return err == nil && len(b) == sha256.Size
}
