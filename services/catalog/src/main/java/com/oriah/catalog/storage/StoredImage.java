package com.oriah.catalog.storage;

/**
 * The result of accepting an upload: the bytes that will actually be stored,
 * plus everything the database row and the HTTP response need.
 *
 * @param bytes       the RE-ENCODED bytes, not what the client sent
 * @param contentType normalised from the decode, never echoed from the client
 * @param width       after any EXIF rotation has been applied
 * @param height      after any EXIF rotation has been applied
 * @param contentHash SHA-256 of {@link #bytes}, which doubles as the HTTP
 *                    ETag -- so it must describe what a client receives, not
 *                    what was uploaded
 * @param storageKey  content-addressed and scoped to the product
 */
public record StoredImage(
        byte[] bytes,
        String contentType,
        int width,
        int height,
        String contentHash,
        String storageKey) {

    public long byteSize() {
        return bytes.length;
    }
}
