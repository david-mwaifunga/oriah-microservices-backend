package com.oriah.catalog.service;

import java.util.Optional;
import java.util.UUID;

import com.oriah.catalog.dto.ProductImageResponse;

/** Skeleton only -- behaviour arrives next, driven by ProductImageServiceTest. */
public interface ProductImageService {

    ProductImageResponse upload(UUID productId, byte[] bytes, String filename,
                                String declaredContentType, String altText);

    Optional<ServedImage> servePublicly(UUID imageId);

    Optional<ServedImage> serveForAdmin(UUID imageId);

    boolean delete(UUID productId, UUID imageId);

    /**
     * Schedules removal of every stored object belonging to a product, for
     * use immediately BEFORE the product's rows are deleted.
     *
     * <p>Split that way because the two halves have opposite timing
     * requirements. The storage keys must be read while the rows still exist
     * — once the FK cascade has run there is nothing left to tell us which
     * objects belonged to the product — but the objects must not be removed
     * until the transaction COMMITS, or a rollback would leave rows pointing
     * at bytes that no longer exist.
     *
     * <p>Without this, every deleted product leaves its photographs in storage
     * permanently: object storage has no foreign keys, so the cascade that
     * removes {@code product_images} rows cannot touch the objects they
     * reference.
     */
    void reclaimStorageFor(UUID productId);
}
