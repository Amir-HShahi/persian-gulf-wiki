package com.persiangulfwiki.core.user.service;

import com.persiangulfwiki.core.user.exception.InvalidAvatarImageException;

import org.springframework.stereotype.Service;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Map;

// Turns an uploaded avatar into the one file that is ever served: a square JPEG, center-cropped
// and at most OUTPUT_SIZE on a side. Re-encoding from decoded pixels is the point, not just the
// resize -- the output carries none of the upload's metadata (EXIF GPS position, camera serial)
// and none of any non-image payload a polyglot file smuggled in.
//
// Dimensions are read from the header before anything is decoded, so a small file that declares
// a huge image (a decompression bomb) is refused without allocating its pixels.
@Service
public class AvatarImageService {

    private static final int OUTPUT_SIZE = 512;
    private static final int MIN_SIDE = 64;
    private static final int MAX_SIDE = 4096;
    private static final float JPEG_QUALITY = 0.85f;

    // The ImageIO format name each accepted content type must actually decode as.
    private static final Map<String, String> FORMAT_BY_CONTENT_TYPE = Map.of(
            "image/jpeg", "jpeg",
            "image/png", "png");

    public byte[] toAvatarJpeg(byte[] upload, String contentType) {
        String expectedFormat = FORMAT_BY_CONTENT_TYPE.get(contentType);
        if (expectedFormat == null) {
            throw new InvalidAvatarImageException();
        }
        return encodeJpeg(cropAndScale(decode(upload, expectedFormat)));
    }

    private static BufferedImage decode(byte[] upload, String expectedFormat) {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(upload))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new InvalidAvatarImageException();
            }
            ImageReader reader = readers.next();
            try {
                if (!reader.getFormatName().equalsIgnoreCase(expectedFormat)) {
                    throw new InvalidAvatarImageException();
                }
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width < MIN_SIDE || height < MIN_SIDE || width > MAX_SIDE || height > MAX_SIDE) {
                    throw new InvalidAvatarImageException();
                }
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException ex) {
            if (ex instanceof InvalidAvatarImageException invalid) {
                throw invalid;
            }
            // A truncated or corrupt file, or one ImageIO cannot decode (e.g. a CMYK JPEG).
            throw new InvalidAvatarImageException("could not decode avatar: " + ex.getClass().getSimpleName());
        }
    }

    // White background: the output is JPEG, so a transparent PNG would otherwise turn black.
    private static BufferedImage cropAndScale(BufferedImage source) {
        int side = Math.min(source.getWidth(), source.getHeight());
        int x = (source.getWidth() - side) / 2;
        int y = (source.getHeight() - side) / 2;
        int outputSide = Math.min(side, OUTPUT_SIZE);

        BufferedImage output = new BufferedImage(outputSide, outputSide, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = output.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, outputSide, outputSide);
            graphics.drawImage(source, 0, 0, outputSide, outputSide, x, y, x + side, y + side, null);
        } finally {
            graphics.dispose();
        }
        return output;
    }

    private static byte[] encodeJpeg(BufferedImage image) {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ImageOutputStream output = ImageIO.createImageOutputStream(bytes)) {
            ImageWriteParam params = writer.getDefaultWriteParam();
            params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            params.setCompressionQuality(JPEG_QUALITY);
            writer.setOutput(output);
            writer.write(null, new IIOImage(image, null, null), params);
        } catch (IOException ex) {
            throw new IllegalStateException("could not encode avatar", ex);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }
}
