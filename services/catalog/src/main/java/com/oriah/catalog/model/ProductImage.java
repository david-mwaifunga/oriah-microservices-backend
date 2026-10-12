package com.oriah.catalog.model;

import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import com.oriah.jpa.BaseEntity;

/**
 * Metadata for one stored product image. The bytes live in object storage;
 * this row holds an opaque key and the facts needed to serve them.
 *
 * <h2>No URL is stored, on purpose</h2>
 *
 * {@link #storageKey} is a bucket-relative key such as
 * {@code products/{productId}/{sha256}.jpg}, never an absolute URL. An
 * absolute URL baked into a row is an environment-specific landmine the first
 * time the stack runs on another host or port, and it is exactly what would
 * turn "move the read path to a CDN" from a URL-builder change into a data
 * migration.
 *
 * <h2>The hash is the ETag</h2>
 *
 * {@link #contentHash} is the SHA-256 of the STORED bytes — after
 * re-encoding, not of whatever was uploaded. Two consequences, both tested:
 * {@code UNIQUE (product_id, content_hash)} makes re-uploading the same file
 * idempotent, and the HTTP {@code ETag} served to clients comes from this
 * column. The second matters because the object store was measured returning
 * no {@code ETag} at all on {@code HeadObject}, so there is nothing to
 * forward even if we wanted to.
 *
 * <h2>Variant association is elsewhere</h2>
 *
 * There is deliberately no {@code variantId} field. With one, an image could
 * belong to at most one variant, while the uniqueness constraint above
 * simultaneously forbade storing the same bytes twice — so using one
 * photograph for two variants of a product was impossible. The association
 * lives in {@code product_variant_images} instead.
 */
@Entity
@Table(name = "product_images")
public class ProductImage extends BaseEntity {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(name = "storage_key", nullable = false, unique = true)
    private String storageKey;

    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    /**
     * Normalised from the decode, never echoed from the client's header. A
     * client-supplied content type is how an uploaded HTML or SVG file gets
     * served back as something a browser will execute.
     */
    @Column(name = "content_type", nullable = false)
    private String contentType;

    @Column(nullable = false)
    private int width;

    @Column(nullable = false)
    private int height;

    @Column(name = "byte_size", nullable = false)
    private long byteSize;

    @Column(name = "alt_text")
    private String altText;

    @Column(nullable = false)
    private int position;

    protected ProductImage() {
        // For JPA hydration only.
    }

    public ProductImage(Product product, String storageKey, String contentHash,
                        String contentType, int width, int height, long byteSize,
                        String altText) {
        this(UUID.randomUUID(), product, storageKey, contentHash, contentType,
                width, height, byteSize, altText);
    }

    public ProductImage(UUID id, Product product, String storageKey, String contentHash,
                        String contentType, int width, int height, long byteSize,
                        String altText) {
        if (product == null) {
            throw new IllegalArgumentException("image must belong to a product");
        }
        if (storageKey == null || storageKey.isBlank()) {
            throw new IllegalArgumentException("storageKey must not be blank");
        }
        if (contentHash == null || contentHash.length() != 64) {
            throw new IllegalArgumentException("contentHash must be 64 hex characters");
        }
        if (contentType == null || contentType.isBlank()) {
            throw new IllegalArgumentException("contentType must not be blank");
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("image dimensions must be positive");
        }
        if (byteSize <= 0) {
            throw new IllegalArgumentException("byteSize must be positive");
        }
        this.id = id;
        this.product = product;
        this.storageKey = storageKey;
        this.contentHash = contentHash;
        this.contentType = contentType;
        this.width = width;
        this.height = height;
        this.byteSize = byteSize;
        this.altText = altText;
        this.position = 0;
    }

    public UUID id() {
        return id;
    }

    public Product product() {
        return product;
    }

    public String storageKey() {
        return storageKey;
    }

    public String contentHash() {
        return contentHash;
    }

    public String contentType() {
        return contentType;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public long byteSize() {
        return byteSize;
    }

    public Optional<String> altText() {
        return Optional.ofNullable(altText);
    }

    public int position() {
        return position;
    }

    public void setPosition(int newPosition) {
        this.position = newPosition;
    }

    /** Alt text is the one mutable field; the bytes and their hash never change. */
    public void updateAltText(String newAltText) {
        this.altText = newAltText;
    }
}
