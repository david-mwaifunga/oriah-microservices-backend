package com.oriah.inventory.model;

import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.oriah.jpa.BaseEntity;

/**
 * Inventory's locally-cached awareness of which products catalog knows
 * about. Populated by {@link com.oriah.inventory.events.ProductEventConsumer}
 * from the {@code catalog.product} Kafka topic.
 *
 * <p>Not the source of truth for product data — that's catalog. We keep
 * the title here so inventory's own admin UI can show a friendly label
 * when listing low-stock variants without doing a synchronous lookup
 * back to catalog.
 *
 * <p>JPA requires a no-args constructor + non-final fields for entities,
 * so this is a plain class (not a record) even though logically it's
 * immutable from outside the package.
 */
@Entity
@Table(name = "product_index")
public class ProductRef extends BaseEntity {

    @Id
    @Column(name = "variant_id", nullable = false)
    private UUID variantId;

    @Column(name = "title", nullable = false)
    private String title;

    /** JPA-only no-args constructor. Do not call from application code. */
    protected ProductRef() {}

    public ProductRef(UUID variantId, String title) {
        this.variantId = Objects.requireNonNull(variantId, "variantId must not be null");
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        this.title = title;
    }

    public UUID variantId() {
        return variantId;
    }

    public String title() {
        return title;
    }

    /**
     * Updates the cached title to reflect a {@code product.updated} event.
     * The {@link com.oriah.jpa.BaseEntity#updatedAt() updatedAt}
     * field on the superclass is refreshed by the AuditingEntityListener
     * on the next flush.
     */
    public void updateTitle(String newTitle) {
        if (newTitle == null || newTitle.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        this.title = newTitle;
    }
}
