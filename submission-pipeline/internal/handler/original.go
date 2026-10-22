package handler

import (
	"io"
	"os"
)

// originalType names the saved copy of an upload: the extension it is stored
// under and the Content-Type it is served as. format is what libvips reports
// ("jpeg", "png", ...) and head is the first bytes of the file.
//
// The type comes from the content, never from the object key, because the key
// is chosen by the uploader and a PNG named photo.jpg is routine.
func originalType(format string, head []byte) (ext, contentType string) {
	switch format {
	case "jpeg":
		return "jpg", "image/jpeg"
	case "png":
		return "png", "image/png"
	case "webp":
		return "webp", "image/webp"
	case "gif":
		return "gif", "image/gif"
	case "tiff":
		return "tif", "image/tiff"
	case "heif":
		// libvips reports one name for both HEIC and AVIF; only the brand in
		// the file's "ftyp" box tells them apart, and a browser needs the right
		// one to decide whether it can show the file.
		if len(head) >= 12 && string(head[4:8]) == "ftyp" {
			switch string(head[8:12]) {
			case "avif", "avis":
				return "avif", "image/avif"
			}
		}
		return "heic", "image/heic"
	}
	return "bin", "application/octet-stream"
}

// originalOf reads just enough of the file at path to name its saved copy.
func originalOf(path, format string) (ext, contentType string, err error) {
	f, err := os.Open(path)
	if err != nil {
		return "", "", err
	}
	defer f.Close()

	head := make([]byte, 16)
	n, err := io.ReadFull(f, head)
	if err != nil && err != io.ErrUnexpectedEOF && err != io.EOF {
		return "", "", err
	}

	ext, contentType = originalType(format, head[:n])
	return ext, contentType, nil
}
