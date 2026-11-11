package com.oriah.catalog.storage;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Where the working-memory gate sits inside the ingest: after the cheap checks, before the
 * allocation, and always given back.
 *
 * <p>The order is the point. A file that is too big, is not an image or is cut short is
 * refused before it costs any memory, so it must not queue behind a good upload; and the
 * header's own size is what the gate is asked about, because the raster that follows is
 * that many pixels.
 */
class ProductImageIngestAdmissionTest {

    private static final UUID PRODUCT = UUID.randomUUID();

    /** Records what the ingest asks for and whether it gives it back. */
    private static final class Recorder implements IngestGate.Admission {
        final List<Long> asked = new ArrayList<>();
        final AtomicInteger open = new AtomicInteger();

        @Override
        public IngestGate.Permit admit(long pixels) {
            asked.add(pixels);
            open.incrementAndGet();
            return open::decrementAndGet;
        }
    }

    @Test
    @DisplayName("the gate is asked for the picture's pixel count, once, and the permit is returned afterwards")
    void askedOnceForTheHeadersPixels() {
        var gate = new Recorder();

        var stored = ProductImageIngest.accept(PRODUCT, TestImages.jpeg(800, 600), "a.jpg", "image/jpeg", gate);

        assertThat(gate.asked).containsExactly(480_000L);
        assertThat(gate.open.get()).as("permit given back").isZero();
        assertThat(stored.width()).isEqualTo(800);
    }

    @Test
    @DisplayName("a sideways photograph is asked for by the pixels it was stored with; the gate's 8 bytes a pixel already covers the rotated copy")
    void sidewaysPictureIsAskedForItsOwnPixels() {
        var gate = new Recorder();

        var stored = ProductImageIngest.accept(PRODUCT, TestImages.jpegWithOrientation(600, 400, 6), "a.jpg", "image/jpeg", gate);

        assertThat(gate.asked).containsExactly(240_000L);
        assertThat(stored.width()).isEqualTo(400);   // turned upright
        assertThat(gate.open.get()).isZero();
    }

    @Test
    @DisplayName("a file that is refused before decoding never asks the gate for anything")
    void refusedFilesCostNoMemoryAndDoNotQueue() {
        var gate = new Recorder();

        assertThatThrownBy(() -> ProductImageIngest.accept(PRODUCT, TestImages.notAnImage(), "a.jpg", "image/jpeg", gate))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProductImageIngest.accept(PRODUCT, TestImages.truncatedJpeg(), "a.jpg", "image/jpeg", gate))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProductImageIngest.accept(PRODUCT, TestImages.decompressionBomb(20_000, 20_000), "a.png", "image/png", gate))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProductImageIngest.accept(PRODUCT, new byte[0], "a.jpg", "image/jpeg", gate))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(gate.asked).isEmpty();
    }

    @Test
    @DisplayName("a picture that passes the header but cannot be decoded still gives the permit back")
    void permitReturnedWhenDecodingFails() {
        var gate = new Recorder();
        byte[] broken = TestImages.png(64, 64);
        // keep the signature, the header and the end marker; ruin the compressed pixel data in between
        int idat = indexOf(broken, "IDAT".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        for (int i = idat + 6; i < idat + 14; i++) {
            broken[i] = (byte) 0xFF;
        }

        assertThatThrownBy(() -> ProductImageIngest.accept(PRODUCT, broken, "a.png", "image/png", gate))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(gate.asked).containsExactly(64L * 64L);
        assertThat(gate.open.get()).as("permit given back after the failure").isZero();
    }

    @Test
    @DisplayName("a busy gate refuses the upload and nothing is decoded")
    void busyGateStopsTheDecode() {
        IngestGate.Admission busy = pixels -> {
            throw new ImageIngestBusyException();
        };

        assertThatThrownBy(() -> ProductImageIngest.accept(PRODUCT, TestImages.jpeg(100, 100), "a.jpg", "image/jpeg", busy))
                .isInstanceOf(ImageIngestBusyException.class);
    }

    @Test
    @DisplayName("without a gate (the four-argument form) behaviour is as it always was")
    void noGateStillWorks() {
        var stored = ProductImageIngest.accept(PRODUCT, TestImages.jpeg(320, 200), "a.jpg", "image/jpeg");
        assertThat(stored.width()).isEqualTo(320);
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        throw new AssertionError("no IDAT chunk");
    }
}
