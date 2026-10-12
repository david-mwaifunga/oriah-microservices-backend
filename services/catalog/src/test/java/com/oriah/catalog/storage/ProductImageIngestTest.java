package com.oriah.catalog.storage;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.UUID;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What happens to bytes between an admin's upload and object storage.
 *
 * <h2>Why this does more than validate</h2>
 *
 * The uploaded bytes are re-encoded rather than stored as received, and that
 * is a deliberate choice with three distinct payoffs:
 *
 * <ul>
 *   <li>EXIF is stripped, so a supplier's photograph does not publish the GPS
 *       coordinates of their workshop on a jewellery storefront.</li>
 *   <li>A polyglot file — valid image, also valid script or archive — cannot
 *       survive, because the output is bytes this service produced from
 *       decoded pixels rather than bytes a client supplied.</li>
 *   <li>The stored content-type is the DECODED type, so it cannot disagree
 *       with the bytes. These images are served from the storefront's own
 *       origin through a rewrite, so a file that is really HTML served as an
 *       image would be same-origin stored XSS against both the shop and the
 *       admin.</li>
 * </ul>
 *
 * <p>Re-encoding is also where the subtle failures live, which is why the
 * cases below are mostly about not corrupting legitimate photographs.
 */
class ProductImageIngestTest {

    private static final UUID PRODUCT = UUID.fromString("11111111-1111-4111-8111-111111111111");

    private static BufferedImage decode(byte[] bytes) throws Exception {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
        assertThat(image).as("stored bytes must themselves be a decodable image").isNotNull();
        return image;
    }

    @Nested
    @DisplayName("rejecting what is not a photograph")
    class Rejection {

        @Test
        @DisplayName("a text file named .jpg is rejected, because the name and content-type are claims")
        void textPretendingToBeJpeg() {
            assertThatThrownBy(() -> ProductImageIngest.accept(
                    PRODUCT, TestImages.notAnImage(), "photo.jpg", "image/jpeg"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("SVG is rejected outright, even though it is a legitimate image format")
        void svgRejected() {
            // SVG is XML and can carry <script>. Served from the storefront's
            // own origin it would be stored XSS, and unlike a raster format
            // there is no "decode and re-encode" that neutralises it while
            // keeping it an SVG.
            assertThatThrownBy(() -> ProductImageIngest.accept(
                    PRODUCT, TestImages.svg(), "logo.svg", "image/svg+xml"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("bytes over the size cap are rejected")
        void oversizeRejected() {
            byte[] tooBig = new byte[ProductImageIngest.MAX_BYTES + 1];
            // Prefixed with a real PNG signature so the rejection is about
            // SIZE and not about failing the magic-byte check first.
            byte[] png = TestImages.png(8, 8);
            System.arraycopy(png, 0, tooBig, 0, png.length);

            assertThatThrownBy(() -> ProductImageIngest.accept(
                    PRODUCT, tooBig, "huge.png", "image/png"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("too large");
        }

        @Test
        @DisplayName("a decompression bomb is rejected BEFORE it is decoded")
        void decompressionBombRejected() {
            // A few kilobytes of flat colour that expands to gigabytes of
            // pixels. A byte-size limit cannot see this: the file is small.
            // The dimensions have to be read from the header and refused
            // before any decode allocates a raster.
            byte[] bomb = TestImages.decompressionBomb(20_000, 20_000);
            assertThat(bomb.length).as("the bomb must be small on disk, or it proves nothing")
                    .isLessThan(ProductImageIngest.MAX_BYTES);

            assertThatThrownBy(() -> ProductImageIngest.accept(
                    PRODUCT, bomb, "bomb.png", "image/png"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("dimensions");
        }

        @Test
        @DisplayName("a truncated upload is rejected rather than stored half-decoded")
        void truncatedRejected() {
            assertThatThrownBy(() -> ProductImageIngest.accept(
                    PRODUCT, TestImages.truncatedJpeg(), "cut.jpg", "image/jpeg"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("empty bytes are rejected")
        void emptyRejected() {
            assertThatThrownBy(() -> ProductImageIngest.accept(
                    PRODUCT, new byte[0], "nothing.jpg", "image/jpeg"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("accepting and normalising a real photograph")
    class Normalisation {

        @Test
        @DisplayName("a JPEG round-trips, and the stored content-type comes from the decode")
        void jpegAccepted() throws Exception {
            var stored = ProductImageIngest.accept(
                    PRODUCT, TestImages.jpeg(800, 600), "ring.jpg", "image/jpeg");

            assertThat(stored.contentType()).isEqualTo("image/jpeg");
            assertThat(stored.width()).isEqualTo(800);
            assertThat(stored.height()).isEqualTo(600);
            assertThat(decode(stored.bytes())).isNotNull();
        }

        @Test
        @DisplayName("the client's content-type is IGNORED in favour of what the bytes actually are")
        void clientContentTypeIsNotTrusted() {
            // A TRANSPARENT png announced as a JPEG, and the transparency is
            // what makes this test prove anything. The output format is chosen
            // by whether the image has an alpha channel, not by the input
            // format -- so an OPAQUE png legitimately becomes a jpeg, and
            // asserting "png in, png out" would have been asserting the wrong
            // rule (as the first version of this test did, and failed).
            //
            // With alpha present the answer must be png, so a stored type of
            // image/png against a declared image/jpeg shows the declaration
            // played no part.
            var stored = ProductImageIngest.accept(
                    PRODUCT, TestImages.transparentPng(100, 100), "ring.jpg", "image/jpeg");

            assertThat(stored.contentType()).isEqualTo("image/png");
            assertThat(stored.storageKey()).endsWith(".png");
        }

        @Test
        @DisplayName("an opaque PNG becomes a JPEG, because photographs are much smaller that way")
        void opaquePngBecomesJpeg() {
            // Stated as its own test rather than left implicit: the stored
            // format follows the alpha channel, not the uploaded format.
            var stored = ProductImageIngest.accept(
                    PRODUCT, TestImages.png(100, 100), "ring.png", "image/png");

            assertThat(stored.contentType()).isEqualTo("image/jpeg");
        }

        @Test
        @DisplayName("a transparent PNG stays a PNG, because JPEG would turn its background black")
        void transparentPngStaysPng() throws Exception {
            var stored = ProductImageIngest.accept(
                    PRODUCT, TestImages.transparentPng(120, 120), "cutout.png", "image/png");

            assertThat(stored.contentType()).isEqualTo("image/png");
            // The alpha channel has to survive: a product shot on a
            // transparent background re-encoded as JPEG comes back on black.
            assertThat(decode(stored.bytes()).getColorModel().hasAlpha()).isTrue();
        }

        @Test
        @DisplayName("EXIF orientation is APPLIED to the pixels, not merely discarded")
        void exifOrientationIsApplied() throws Exception {
            // Orientation 6 means "rotate 90 degrees clockwise to display".
            // The stored pixels are 800x600 but should be shown as 600x800.
            //
            // This is the case that matters most, and it is the one a naive
            // "strip all metadata" implementation gets silently wrong: with
            // the tag removed and the pixels untouched, every photograph from
            // a camera held vertically appears on its side, with nothing left
            // in the file to indicate it.
            var stored = ProductImageIngest.accept(
                    PRODUCT, TestImages.jpegWithOrientation(800, 600, 6), "ring.jpg", "image/jpeg");

            assertThat(stored.width()).as("width and height swap when the rotation is applied")
                    .isEqualTo(600);
            assertThat(stored.height()).isEqualTo(800);

            BufferedImage reread = decode(stored.bytes());
            assertThat(reread.getWidth()).isEqualTo(600);
            assertThat(reread.getHeight()).isEqualTo(800);
        }

        @Test
        @DisplayName("orientation 1 (already upright) changes nothing")
        void uprightOrientationIsANoOp() throws Exception {
            var stored = ProductImageIngest.accept(
                    PRODUCT, TestImages.jpegWithOrientation(800, 600, 1), "ring.jpg", "image/jpeg");

            assertThat(stored.width()).isEqualTo(800);
            assertThat(stored.height()).isEqualTo(600);
        }

        @Test
        @DisplayName("no EXIF survives in the stored bytes")
        void exifIsStripped() {
            var stored = ProductImageIngest.accept(
                    PRODUCT, TestImages.jpegWithOrientation(400, 300, 6), "ring.jpg", "image/jpeg");

            // Searched for literally: the marker is what a metadata reader
            // looks for, and its absence is what means a supplier's GPS
            // coordinates and camera serial are not being published.
            assertThat(new String(stored.bytes(), java.nio.charset.StandardCharsets.ISO_8859_1))
                    .doesNotContain("Exif");
        }
    }

    @Nested
    @DisplayName("the storage key and hash")
    class Addressing {

        @Test
        @DisplayName("the hash is of the STORED bytes, not the uploaded ones")
        void hashCoversStoredBytes() {
            byte[] uploaded = TestImages.jpegWithOrientation(400, 300, 6);
            var stored = ProductImageIngest.accept(PRODUCT, uploaded, "ring.jpg", "image/jpeg");

            // Re-encoding changes the bytes, so hashing the upload would
            // produce an identifier for something that was never stored --
            // and the hash is served as the HTTP ETag, which must describe
            // what a client actually receives.
            assertThat(stored.contentHash()).hasSize(64).matches("[0-9a-f]{64}");
            assertThat(stored.contentHash())
                    .isNotEqualTo(ProductImageIngest.sha256Hex(uploaded));
            assertThat(stored.contentHash())
                    .isEqualTo(ProductImageIngest.sha256Hex(stored.bytes()));
        }

        @Test
        @DisplayName("the key is content-addressed under the product, so a re-upload is idempotent")
        void keyIsContentAddressed() {
            byte[] bytes = TestImages.jpeg(200, 200);
            var first = ProductImageIngest.accept(PRODUCT, bytes, "a.jpg", "image/jpeg");
            var again = ProductImageIngest.accept(PRODUCT, bytes, "renamed.jpg", "image/jpeg");

            // The filename is NOT part of the key: the same photograph
            // uploaded twice under different names is one object, and the
            // database's UNIQUE (product_id, content_hash) returns the
            // existing row rather than storing a duplicate.
            assertThat(again.storageKey()).isEqualTo(first.storageKey());
            assertThat(first.storageKey())
                    .isEqualTo("products/" + PRODUCT + "/" + first.contentHash() + ".jpg");
        }

        @Test
        @DisplayName("the same bytes under different products are different objects")
        void keysArePerProduct() {
            byte[] bytes = TestImages.jpeg(200, 200);
            var mine = ProductImageIngest.accept(PRODUCT, bytes, "a.jpg", "image/jpeg");
            var theirs = ProductImageIngest.accept(UUID.randomUUID(), bytes, "a.jpg", "image/jpeg");

            // Scoped per product so deleting one product's images can never
            // remove bytes another product is still serving.
            assertThat(mine.storageKey()).isNotEqualTo(theirs.storageKey());
            assertThat(mine.contentHash()).isEqualTo(theirs.contentHash());
        }

        @Test
        @DisplayName("the key extension matches the stored content-type")
        void extensionMatchesType() {
            var png = ProductImageIngest.accept(
                    PRODUCT, TestImages.transparentPng(50, 50), "x.png", "image/png");
            assertThat(png.storageKey()).endsWith(".png");

            var jpeg = ProductImageIngest.accept(
                    PRODUCT, TestImages.jpeg(50, 50), "x.jpg", "image/jpeg");
            assertThat(jpeg.storageKey()).endsWith(".jpg");
        }
    }
}
