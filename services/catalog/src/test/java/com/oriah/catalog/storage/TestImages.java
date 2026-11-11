package com.oriah.catalog.storage;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;

import javax.imageio.ImageIO;

/**
 * Image fixtures built in code rather than committed as binaries.
 *
 * <h2>Why generated and not checked in</h2>
 *
 * A committed {@code .jpg} is opaque: nobody reviewing a change can see what
 * is inside it, and "the EXIF test fixture" becomes a file whose contents
 * nobody can verify without a hex editor. Generating them makes the thing
 * under test explicit — the orientation tag below is assembled byte by byte,
 * so the test documents the format it depends on.
 */
public final class TestImages {

    private TestImages() {
    }

    /** A plain opaque JPEG of the given size. */
    public static byte[] jpeg(int width, int height) {
        return encode(solid(width, height, Color.WHITE, false), "jpeg");
    }

    /** A plain opaque PNG. */
    public static byte[] png(int width, int height) {
        return encode(solid(width, height, Color.WHITE, false), "png");
    }

    /**
     * A PNG with a genuinely transparent region.
     *
     * <p>Used to pin that a transparent PNG is never re-encoded as JPEG:
     * JPEG has no alpha channel, so the transparency would be composited onto
     * black and a product photographed on a transparent background would come
     * back with a black one.
     */
    public static byte[] transparentPng(int width, int height) {
        return encode(solid(width, height, new Color(255, 255, 255, 0), true), "png");
    }

    /**
     * A JPEG carrying an EXIF Orientation tag.
     *
     * <p>Orientation 6 means "rotate 90° clockwise for display": the stored
     * pixels are sideways and the tag tells a viewer to turn them. This is the
     * single most common form of camera metadata, and the reason it matters
     * here is that STRIPPING the tag without APPLYING it leaves the pixels
     * sideways with nothing left to say so — so a supplier's photo silently
     * ends up rotated on the storefront.
     *
     * <p>The segment is assembled by hand because ImageIO cannot write EXIF.
     * Structure, which is also the documentation for what the ingest path has
     * to parse:
     *
     * <pre>
     *   FF E1              APP1 marker
     *   00 22              segment length (34, including these two bytes)
     *   "Exif\0\0"         6 bytes
     *   4D 4D 00 2A        TIFF header, big-endian ("MM"), magic 42
     *   00 00 00 08        offset of IFD0 from the TIFF header
     *   00 01              one directory entry
     *   01 12 00 03        tag 0x0112 (Orientation), type 3 (SHORT)
     *   00 00 00 01        one value
     *   00 0N 00 00        the value, left-aligned in a 4-byte field
     *   00 00 00 00        no next IFD
     * </pre>
     */
    public static byte[] jpegWithOrientation(int width, int height, int orientation) {
        byte[] plain = jpeg(width, height);

        byte[] app1 = new byte[] {
                (byte) 0xFF, (byte) 0xE1,
                0x00, 0x22,
                'E', 'x', 'i', 'f', 0x00, 0x00,
                0x4D, 0x4D, 0x00, 0x2A, 0x00, 0x00, 0x00, 0x08,
                0x00, 0x01,
                0x01, 0x12, 0x00, 0x03, 0x00, 0x00, 0x00, 0x01,
                0x00, (byte) orientation, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x00,
        };

        // Spliced immediately after SOI (FF D8), which is where APP segments
        // belong. Inserting it anywhere else produces a file decoders will
        // either ignore or reject, and the test would then prove nothing.
        byte[] out = new byte[plain.length + app1.length];
        out[0] = plain[0];
        out[1] = plain[1];
        System.arraycopy(app1, 0, out, 2, app1.length);
        System.arraycopy(plain, 2, out, 2 + app1.length, plain.length - 2);
        return out;
    }

    /**
     * Bytes that are not an image at all, with a name that claims otherwise.
     *
     * <p>The content-type and filename a client sends are assertions, not
     * facts; this is what the magic-byte check exists to catch.
     */
    public static byte[] notAnImage() {
        return "This is a text file pretending to be a photograph.".getBytes();
    }

    /** A minimal SVG. Rejected outright: it can carry script. */
    public static byte[] svg() {
        return ("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"10\" height=\"10\">"
                + "<script>alert(1)</script></svg>").getBytes();
    }

    /**
     * A PNG that is small on disk and enormous when decoded.
     *
     * <p>A single flat colour compresses to almost nothing, so this is a few
     * kilobytes that expands to width×height×4 bytes in memory. It is the
     * decompression bomb a byte-size limit cannot see: the check has to read
     * the dimensions from the header and refuse BEFORE decoding.
     */
    public static byte[] decompressionBomb(int width, int height) {
        return encode(solid(width, height, Color.BLACK, false), "png");
    }

    /**
     * A 16-bit-per-channel RGBA PNG: 8 bytes a pixel once decoded, twice what an ordinary
     * 8-bit picture costs, and still tiny on disk when it is a flat colour. Used to pin that
     * the memory charged for an upload comes from the picture's real sample size and not from
     * an assumption that every picture is 8-bit.
     */
    public static byte[] png16(int width, int height) {
        var spec = javax.imageio.ImageTypeSpecifier.createInterleaved(
                java.awt.color.ColorSpace.getInstance(java.awt.color.ColorSpace.CS_sRGB),
                new int[] {0, 1, 2, 3}, java.awt.image.DataBuffer.TYPE_USHORT, true, false);
        BufferedImage image = spec.createBufferedImage(width, height);
        return encode(image, "png");
    }

    /** A JPEG truncated mid-stream, as an interrupted upload would be. */
    public static byte[] truncatedJpeg() {
        byte[] full = jpeg(200, 200);
        return Arrays.copyOf(full, full.length / 2);
    }

    private static BufferedImage solid(int width, int height, Color colour, boolean alpha) {
        var image = new BufferedImage(width, height,
                alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setComposite(java.awt.AlphaComposite.Src);
            g.setColor(colour);
            g.fillRect(0, 0, width, height);
        } finally {
            g.dispose();
        }
        return image;
    }

    private static byte[] encode(BufferedImage image, String format) {
        var out = new ByteArrayOutputStream();
        try {
            if (!ImageIO.write(image, format, out)) {
                throw new IllegalStateException("no ImageIO writer for " + format);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }
}
