package com.oriah.catalog.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * How much working memory an upload will need, worked out from its header before anything is
 * decoded.
 *
 * <p>This is what the gate is asked for. It must be an honest figure, because the gate's whole
 * job is to keep the sum of these inside the heap:
 * <ul>
 *   <li>the decoded raster costs what the picture's own pixel format costs (3-4 bytes a pixel
 *       for an ordinary photograph, 8 for a 16-bit-per-channel PNG), never less than 4;</li>
 *   <li>and the upright copy is always counted, 4 bytes a pixel, whether or not this picture
 *       happens to need turning;</li>
 *   <li>a file refused by the cheap checks (too big, not an image, cut short, implausible size)
 *       fails here, before it can ask for, or wait for, any memory.</li>
 * </ul>
 */
class ProductImageWorkingBytesTest {

    @Test
    @DisplayName("an ordinary photograph costs 8 bytes a pixel: the raster and its upright copy")
    void ordinaryPhotographIsEightBytesAPixel() {
        assertThat(ProductImageIngest.workingBytes(TestImages.jpeg(800, 600), "image/jpeg"))
                .isEqualTo(800L * 600 * 8);
    }

    @Test
    @DisplayName("a sideways photograph costs the same: the upright copy is always counted")
    void sidewaysPhotographCostsTheSame() {
        assertThat(ProductImageWorkingBytesTest.cost(TestImages.jpegWithOrientation(600, 400, 6)))
                .isEqualTo(600L * 400 * 8);
        assertThat(ProductImageWorkingBytesTest.cost(TestImages.jpegWithOrientation(600, 400, 1)))
                .isEqualTo(600L * 400 * 8);
    }

    @Test
    @DisplayName("a transparent 8-bit PNG costs 8 bytes a pixel too")
    void transparentPngIsEightBytesAPixel() {
        assertThat(cost(TestImages.transparentPng(100, 50))).isEqualTo(100L * 50 * 8);
    }

    @Test
    @DisplayName("a 16-bit-per-channel PNG costs 12 bytes a pixel: its raster alone is 8")
    void sixteenBitPngIsTwelveBytesAPixel() {
        assertThat(cost(TestImages.png16(64, 64))).isEqualTo(64L * 64 * 12);
    }

    @Test
    @DisplayName("a file the cheap checks refuse fails here, so it never asks the gate for anything")
    void refusedFilesFailBeforeAnyMemoryIsAsked() {
        assertThatThrownBy(() -> cost(TestImages.notAnImage())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cost(TestImages.truncatedJpeg())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cost(TestImages.decompressionBomb(20_000, 20_000))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cost(new byte[0])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cost(new byte[ProductImageIngest.MAX_BYTES + 1])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProductImageIngest.workingBytes(null, "image/jpeg")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the biggest picture catalog accepts costs exactly what the gate's minimum budget covers")
    void biggestPictureMatchesTheMinimumBudget() {
        assertThat(ProductImageIngest.MAX_PIXELS * ProductImageIngest.BYTES_PER_PIXEL_8BIT)
                .isEqualTo(320_000_000L);
        assertThat(IngestGate.workingMemoryMiB(ProductImageIngest.MAX_PIXELS * ProductImageIngest.BYTES_PER_PIXEL_8BIT))
                .isEqualTo(306);
    }

    private static long cost(byte[] file) {
        return ProductImageIngest.workingBytes(file, "image/jpeg");
    }
}
