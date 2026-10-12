package com.oriah.catalog.storage;

import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Iterator;
import java.util.UUID;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.exif.ExifIFD0Directory;

/**
 * Turns an uploaded file into bytes that are safe to store and serve.
 *
 * <h2>Why the upload is re-encoded rather than stored as received</h2>
 *
 * Three reasons, each of which would justify it alone:
 *
 * <ul>
 *   <li><b>Metadata.</b> A supplier's photograph carries EXIF: camera serial,
 *       timestamps, and often GPS coordinates. Publishing the location where
 *       jewellery is made, on a public storefront, is not a decision anyone
 *       made deliberately.</li>
 *   <li><b>Polyglots.</b> A file can be a valid image AND a valid archive or
 *       script. Re-encoding makes that impossible, because the output is bytes
 *       this service produced from decoded pixels — not bytes a client
 *       supplied with an image header in front of them.</li>
 *   <li><b>Type honesty.</b> The stored content-type comes from the decode, so
 *       it cannot disagree with the bytes. These images are served from the
 *       storefront's own origin through a rewrite, so a file that is really
 *       HTML, served as an image, is same-origin stored XSS against both the
 *       shop and the admin.</li>
 * </ul>
 *
 * <h2>The order of the checks is load-bearing</h2>
 *
 * Size, then signature, then DIMENSIONS FROM THE HEADER, and only then a
 * decode. A flat-colour PNG of 20000x20000 compresses to a few kilobytes and
 * expands to 1.6 GB of raster — a byte-size limit cannot see it, because the
 * file is small. Reading width and height from the header costs nothing and
 * refuses it before anything is allocated.
 */
public final class ProductImageIngest {

    /**
     * 5 MB. Also enforced at the edge by the gateway's RequestSize filter and
     * by Spring's multipart limit, so an oversized body is rejected before it
     * streams through the reactive gateway, which runs with a 128 MB
     * direct-buffer ceiling for reasons its own pom records.
     */
    public static final int MAX_BYTES = 5 * 1024 * 1024;

    /** Beyond any plausible product photograph, and well short of a bomb. */
    private static final int MAX_DIMENSION = 8_000;

    /** 40 MP: a 8000x5000 image is fine, 20000x20000 is not. */
    private static final long MAX_PIXELS = 40_000_000L;

    private ProductImageIngest() {
    }

    /**
     * @param declaredContentType the client's claim, used for NOTHING except
     *                            the error message. It is a request header; the
     *                            bytes are the fact.
     * @throws IllegalArgumentException on anything that is not a storable
     *         photograph, rendered as a 400 by the shared error contract
     */
    public static StoredImage accept(UUID productId, byte[] uploaded,
                                     String filename, String declaredContentType) {
        if (uploaded == null || uploaded.length == 0) {
            throw new IllegalArgumentException("the uploaded file is empty");
        }
        if (uploaded.length > MAX_BYTES) {
            throw new IllegalArgumentException(
                    "the uploaded file is too large: " + uploaded.length
                            + " bytes, limit " + MAX_BYTES);
        }

        Format format = Format.sniff(uploaded);
        if (format == null) {
            throw new IllegalArgumentException(
                    "not a supported image: expected JPEG, PNG or WebP"
                            + (declaredContentType == null ? "" : " (sent as " + declaredContentType + ")"));
        }
        if (!format.isComplete(uploaded)) {
            throw new IllegalArgumentException(
                    "the uploaded file is truncated: the " + format.name()
                            + " data ends before the format's end marker");
        }

        // Header only. No raster is allocated by this.
        int[] declared = readDimensions(uploaded);
        guardDimensions(declared[0], declared[1]);

        BufferedImage decoded = decode(uploaded);
        // Re-checked after the decode: the header is itself client-supplied,
        // and a file whose header lies about its size would otherwise slip the
        // guard above and allocate anyway.
        guardDimensions(decoded.getWidth(), decoded.getHeight());

        BufferedImage upright = applyOrientation(decoded, orientationOf(uploaded));

        // Alpha decides the output format, not the input format. A product
        // photographed on a transparent background re-encoded as JPEG comes
        // back on black, because JPEG has no alpha channel -- so a transparent
        // source must stay PNG. An opaque source becomes JPEG, which is far
        // smaller for photographs.
        Format target = upright.getColorModel().hasAlpha() ? Format.PNG : Format.JPEG;
        byte[] stored = encode(upright, target);

        String hash = sha256Hex(stored);
        return new StoredImage(
                stored,
                target.contentType,
                upright.getWidth(),
                upright.getHeight(),
                hash,
                // Content-addressed and scoped to the product. The FILENAME is
                // deliberately absent: the same photograph uploaded twice under
                // different names is one object, and a client-supplied name in
                // a storage key is a path-traversal surface for no benefit.
                "products/" + productId + "/" + hash + "." + target.extension);
    }

    private static void guardDimensions(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("image dimensions could not be read");
        }
        if (width > MAX_DIMENSION || height > MAX_DIMENSION
                || (long) width * height > MAX_PIXELS) {
            throw new IllegalArgumentException(
                    "image dimensions are too large: " + width + "x" + height
                            + ", limit " + MAX_DIMENSION + "x" + MAX_DIMENSION
                            + " and " + MAX_PIXELS + " pixels");
        }
    }

    /**
     * Width and height without decoding the pixels.
     *
     * <p>This is the decompression-bomb guard. {@code ImageReader.getWidth}
     * parses the header only, so a gigapixel file is refused for a few bytes
     * of work instead of a gigabyte of allocation.
     */
    private static int[] readDimensions(byte[] bytes) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                throw new IllegalArgumentException("no decoder for this image format");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in);
                return new int[] { reader.getWidth(0), reader.getHeight(0) };
            } finally {
                reader.dispose();
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("the image header could not be read", e);
        }
    }

    private static BufferedImage decode(byte[] bytes) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null) {
                throw new IllegalArgumentException("the image could not be decoded");
            }
            return image;
        } catch (IOException e) {
            // A truncated upload lands here: the header parsed, the pixel data
            // ran out. Rejected rather than stored partially decoded, which
            // would publish a half-grey photograph.
            throw new IllegalArgumentException("the image could not be decoded", e);
        }
    }

    /**
     * The EXIF orientation tag, or 1 (upright) when there is none.
     *
     * <p>Read with metadata-extractor rather than by hand: ImageIO exposes
     * EXIF only as a raw APP1 segment, and parsing TIFF directories inline is
     * how this gets quietly wrong for the one camera that writes
     * little-endian.
     */
    private static int orientationOf(byte[] bytes) {
        try {
            var metadata = ImageMetadataReader.readMetadata(new ByteArrayInputStream(bytes));
            var directory = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
            if (directory == null || !directory.containsTag(ExifIFD0Directory.TAG_ORIENTATION)) {
                return 1;
            }
            return directory.getInt(ExifIFD0Directory.TAG_ORIENTATION);
        } catch (Exception e) {
            // Absent, unreadable or malformed metadata is not a reason to
            // reject a photograph that decodes perfectly well. Treated as
            // upright, which is what a viewer ignoring EXIF would also do.
            return 1;
        }
    }

    /**
     * Rotates and flips the PIXELS to match the EXIF orientation, so the tag
     * can then be discarded.
     *
     * <h2>Why applying it matters as much as stripping it</h2>
     *
     * Orientation 6 means "the stored pixels are sideways; rotate 90 degrees
     * clockwise to display". Nearly every phone and camera writes this rather
     * than rotating the raster. Strip the tag without applying it and the
     * pixels stay sideways with nothing left in the file to say so — so every
     * photograph taken in portrait appears on its side on the storefront, and
     * the cause is invisible because the file is now "clean".
     *
     * <p>All eight values are handled. The mirrored ones (2, 4, 5, 7) are rare
     * but real, and treating them as upright flips a product photograph —
     * which on a watch means the dial reads backwards.
     */
    private static BufferedImage applyOrientation(BufferedImage source, int orientation) {
        if (orientation <= 1 || orientation > 8) {
            return source;
        }

        int width = source.getWidth();
        int height = source.getHeight();
        // 5-8 involve a quarter turn, which transposes the dimensions.
        boolean transposed = orientation >= 5;
        int targetWidth = transposed ? height : width;
        int targetHeight = transposed ? width : height;

        var transform = new AffineTransform();
        switch (orientation) {
            case 2 -> { // mirror horizontally
                transform.translate(width, 0);
                transform.scale(-1, 1);
            }
            case 3 -> { // rotate 180
                transform.translate(width, height);
                transform.rotate(Math.PI);
            }
            case 4 -> { // mirror vertically
                transform.translate(0, height);
                transform.scale(1, -1);
            }
            case 5 -> { // transpose
                transform.rotate(-Math.PI / 2);
                transform.scale(-1, 1);
            }
            case 6 -> { // rotate 90 clockwise
                transform.translate(height, 0);
                transform.rotate(Math.PI / 2);
            }
            case 7 -> { // transverse
                transform.scale(-1, 1);
                transform.translate(-height, 0);
                transform.rotate(-Math.PI / 2);
                transform.translate(-width, 0);
            }
            case 8 -> { // rotate 90 anticlockwise
                transform.translate(0, width);
                transform.rotate(-Math.PI / 2);
            }
            default -> {
                return source;
            }
        }

        var rotated = new BufferedImage(targetWidth, targetHeight,
                source.getColorModel().hasAlpha()
                        ? BufferedImage.TYPE_INT_ARGB
                        : BufferedImage.TYPE_INT_RGB);
        var g = rotated.createGraphics();
        try {
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                    java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(source, transform, null);
        } finally {
            g.dispose();
        }
        return rotated;
    }

    /**
     * Encodes to the target format.
     *
     * <p>Nothing is copied through from the source, which is the point: the
     * output contains pixels and a format header, and no metadata of any kind.
     * An opaque image is also flattened onto white rather than written with an
     * alpha channel it does not use.
     */
    private static byte[] encode(BufferedImage image, Format format) {
        BufferedImage toWrite = image;
        if (format == Format.JPEG && image.getColorModel().hasAlpha()) {
            // Unreachable given the caller's choice, but a JPEG writer handed
            // an alpha raster produces either an exception or an inverted
            // image depending on the plugin, so it is worth being explicit.
            var flattened = new BufferedImage(image.getWidth(), image.getHeight(),
                    BufferedImage.TYPE_INT_RGB);
            var g = flattened.createGraphics();
            try {
                g.setColor(java.awt.Color.WHITE);
                g.fillRect(0, 0, image.getWidth(), image.getHeight());
                g.drawImage(image, 0, 0, null);
            } finally {
                g.dispose();
            }
            toWrite = flattened;
        }

        var out = new ByteArrayOutputStream();
        try {
            if (!ImageIO.write(toWrite, format.imageIoName, out)) {
                throw new IllegalStateException("no ImageIO writer for " + format.imageIoName);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    /** Lowercase hex, 64 characters, matching the {@code content_hash} column. */
    public static String sha256Hex(byte[] bytes) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
            var hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * The formats accepted on ingest, identified by signature.
     *
     * <p>SVG is absent deliberately. It is a legitimate image format, but it
     * is XML that can carry script, and unlike a raster format there is no
     * decode-and-re-encode that neutralises it while keeping it an SVG. GIF is
     * absent because nothing in a jewellery catalogue needs it and animation
     * would not survive re-encoding anyway.
     */
    private enum Format {
        JPEG("image/jpeg", "jpg", "jpeg"),
        PNG("image/png", "png", "png"),
        WEBP("image/webp", "webp", "webp");

        private final String contentType;
        private final String extension;
        private final String imageIoName;

        Format(String contentType, String extension, String imageIoName) {
            this.contentType = contentType;
            this.extension = extension;
            this.imageIoName = imageIoName;
        }

        /**
         * Identifies the format from its leading bytes.
         *
         * <p>Signatures, not the filename and not the {@code Content-Type}
         * header: both are supplied by the client and neither is evidence.
         */
        static Format sniff(byte[] b) {
            if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8
                    && (b[2] & 0xFF) == 0xFF) {
                return JPEG;
            }
            if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G'
                    && (b[4] & 0xFF) == 0x0D && (b[5] & 0xFF) == 0x0A
                    && (b[6] & 0xFF) == 0x1A && (b[7] & 0xFF) == 0x0A) {
                return PNG;
            }
            // RIFF container with a WEBP fourcc at offset 8.
            if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                    && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
                return WEBP;
            }
            return null;
        }

        /**
         * Whether the container actually runs to its end marker.
         *
         * <h2>Why a decode is not enough</h2>
         *
         * ImageIO is deliberately lenient: handed a JPEG whose scan data is
         * cut off halfway, it returns a perfectly valid BufferedImage with the
         * missing rows filled grey, and reports no error. So an interrupted
         * upload passed every other check here and was stored as a
         * half-grey product photograph -- a defect a human notices on the
         * storefront and nothing in the pipeline reports.
         *
         * <p>Checking the terminator catches it for a few bytes of work:
         * JPEG ends with EOI (FFD9), PNG with an IEND chunk, and a RIFF
         * container declares its own length in bytes 4..7.
         */
        boolean isComplete(byte[] b) {
            return switch (this) {
                case JPEG -> b.length >= 2
                        && (b[b.length - 2] & 0xFF) == 0xFF
                        && (b[b.length - 1] & 0xFF) == 0xD9;
                case PNG -> b.length >= 12
                        && b[b.length - 8] == 'I' && b[b.length - 7] == 'E'
                        && b[b.length - 6] == 'N' && b[b.length - 5] == 'D';
                // RIFF size counts everything after the first 8 bytes.
                case WEBP -> {
                    long declared = ((long) (b[4] & 0xFF))
                            | ((long) (b[5] & 0xFF) << 8)
                            | ((long) (b[6] & 0xFF) << 16)
                            | ((long) (b[7] & 0xFF) << 24);
                    yield b.length >= declared + 8;
                }
            };
        }

        @SuppressWarnings("unused")
        String contentType() {
            return contentType;
        }
    }
}
