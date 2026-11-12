package com.oriah.procurement.model;

import java.util.Arrays;
import java.util.Locale;

/**
 * The kinds of file a procurement document may be, decided from the file's OWN first bytes and
 * never from what the uploader said it was (a name or a content type is only a claim).
 *
 * <p>Only a PDF, a JPEG or a PNG is kept: what a scanned or photographed invoice is. Anything
 * else, including a program renamed to look like one of these, is refused. Naming the type by
 * its first bytes (a "magic number") is the standard way to tell what a file really is.
 */
public enum DocumentType {

    PDF("application/pdf", ".pdf"),
    JPEG("image/jpeg", ".jpg", ".jpeg"),
    PNG("image/png", ".png");

    private static final byte[] PDF_MAGIC = {'%', 'P', 'D', 'F', '-'};
    private static final byte[] JPEG_MAGIC = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

    private final String mediaType;
    private final String[] extensions;

    DocumentType(String mediaType, String... extensions) {
        this.mediaType = mediaType;
        this.extensions = extensions;
    }

    /** The media type served and stored, e.g. {@code application/pdf}. */
    public String mediaType() {
        return mediaType;
    }

    /** The extension a file of this type is given when its name has none that fits. */
    public String extension() {
        return extensions[0];
    }

    /** Does this name already end the way a file of this type does? ({@code .jpeg} counts for a JPEG.) */
    public boolean fits(String filename) {
        String lower = filename.toLowerCase(Locale.ROOT);
        return Arrays.stream(extensions).anyMatch(lower::endsWith);
    }

    /** The extension this name ends with, when it fits; otherwise null. */
    public String extensionOf(String filename) {
        String lower = filename.toLowerCase(Locale.ROOT);
        return Arrays.stream(extensions).filter(lower::endsWith).findFirst().orElse(null);
    }

    /** The type a stored media type names. */
    public static DocumentType ofMediaType(String mediaType) {
        return Arrays.stream(values()).filter(t -> t.mediaType.equals(mediaType)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("not a stored document type: " + mediaType));
    }

    /**
     * What these bytes really are.
     *
     * @throws IllegalArgumentException if they are not a PDF, JPEG or PNG
     */
    public static DocumentType detect(byte[] bytes) {
        if (startsWith(bytes, PDF_MAGIC)) {
            return PDF;
        }
        if (startsWith(bytes, JPEG_MAGIC)) {
            return JPEG;
        }
        if (startsWith(bytes, PNG_MAGIC)) {
            return PNG;
        }
        throw new IllegalArgumentException("Only a PDF, JPEG or PNG is kept, and this file is none of them "
                + "(whatever its name says). Save or scan the invoice as one of those and try again.");
    }

    private static boolean startsWith(byte[] bytes, byte[] magic) {
        if (bytes == null || bytes.length < magic.length) {
            return false;
        }
        for (int i = 0; i < magic.length; i++) {
            if (bytes[i] != magic[i]) {
                return false;
            }
        }
        return true;
    }
}
