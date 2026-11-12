package com.oriah.procurement.model;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;


import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a file really is, read from its own first bytes. A name and a content type are only claims;
 * the bytes are what a browser or a PDF reader will act on.
 */
class DocumentTypeTest {

    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }

    @Test
    @DisplayName("a PDF starts %PDF-, a JPEG FF D8 FF, a PNG with its eight-byte signature")
    void recognisesThreeTypes() {
        assertThat(DocumentType.detect("%PDF-1.7 ...".getBytes(StandardCharsets.US_ASCII))).isEqualTo(DocumentType.PDF);
        assertThat(DocumentType.detect(bytes(0xFF, 0xD8, 0xFF, 0xE1, 0x00))).isEqualTo(DocumentType.JPEG);
        assertThat(DocumentType.detect(bytes(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0x00))).isEqualTo(DocumentType.PNG);
    }

    @Test
    @DisplayName("exactly the signature is enough, and one byte less is not")
    void boundaries() {
        assertThat(DocumentType.detect("%PDF-".getBytes(StandardCharsets.US_ASCII))).isEqualTo(DocumentType.PDF);
        assertThat(DocumentType.detect(bytes(0xFF, 0xD8, 0xFF))).isEqualTo(DocumentType.JPEG);
        assertThatThrownBy(() -> DocumentType.detect("%PDF".getBytes(StandardCharsets.US_ASCII))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DocumentType.detect(bytes(0xFF, 0xD8))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DocumentType.detect(bytes(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the signature must be at the START: a PDF after other bytes, or a program, is refused")
    void mustBeAtTheStart() {
        assertThatThrownBy(() -> DocumentType.detect(" %PDF-1.4".getBytes(StandardCharsets.US_ASCII)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("PDF, JPEG or PNG");
        assertThatThrownBy(() -> DocumentType.detect("MZ\u0090\u0000".getBytes(StandardCharsets.ISO_8859_1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DocumentType.detect("<html><script>alert(1)</script>".getBytes(StandardCharsets.US_ASCII)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DocumentType.detect(new byte[0])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DocumentType.detect(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a type knows its media type and which extensions fit it; .jpeg is a JPEG")
    void mediaTypesAndExtensions() {
        assertThat(DocumentType.PDF.mediaType()).isEqualTo("application/pdf");
        assertThat(DocumentType.JPEG.mediaType()).isEqualTo("image/jpeg");
        assertThat(DocumentType.PNG.mediaType()).isEqualTo("image/png");
        assertThat(DocumentType.JPEG.fits("holiday.JPEG")).isTrue();
        assertThat(DocumentType.JPEG.fits("holiday.jpg")).isTrue();
        assertThat(DocumentType.JPEG.fits("holiday.png")).isFalse();
        assertThat(DocumentType.JPEG.extension()).isEqualTo(".jpg");
        assertThat(DocumentType.ofMediaType("image/png")).isEqualTo(DocumentType.PNG);
        assertThatThrownBy(() -> DocumentType.ofMediaType("text/html")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a document kind is read by name; a courier invoice and a ZRA assessment do not fit a purchase order")
    void kinds() {
        assertThat(DocumentKind.parse("courier invoice")).isEqualTo(DocumentKind.COURIER_INVOICE);
        assertThat(DocumentKind.parse(" zra-assessment ")).isEqualTo(DocumentKind.ZRA_ASSESSMENT);
        assertThat(DocumentKind.SUPPLIER_INVOICE.fitsAnOrder()).isTrue();
        assertThat(DocumentKind.OTHER.fitsAnOrder()).isTrue();
        assertThat(DocumentKind.COURIER_INVOICE.fitsAnOrder()).isFalse();
        assertThat(DocumentKind.ZRA_ASSESSMENT.fitsAnOrder()).isFalse();
        assertThatThrownBy(() -> DocumentKind.parse("receipt")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("COURIER_INVOICE");
        assertThatThrownBy(() -> DocumentKind.parse(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
