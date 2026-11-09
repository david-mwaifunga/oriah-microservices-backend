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

    /**
     * The catalog PRODUCT this variant belongs to.
     *
     * <p>Needed because {@code product.deleted} names a product, and without
     * it there is no way to find that product's variant rows. For rows that
     * predate catalog's variants migration it equals {@link #variantId}, which
     * is correct rather than coincidental: that migration gave every
     * pre-existing product one variant whose id is the product's own.
     */
    @Column(name = "product_id", nullable = false)
    private UUID productId;

    /**
     * Whether the owning product has been removed from the catalogue.
     *
     * <h2>Why a flag and not a delete</h2>
     *
     * Dropping the row on {@code product.deleted} would lose the only title an
     * operator has for stock that still physically exists. The admin inventory
     * list LEFT JOINs this table precisely so a stock row with no catalog
     * reference still appears, so after a delete it would show a bare UUID and
     * a quantity — which nobody can act on. Leaving the row unflagged is no
     * better: the screen would show a live-looking product that is gone.
     *
     * <p>Flagged, the row keeps its title and the list can say
     * "Aurelia 18k (deleted product)" beside the real figure.
     */
    @Column(name = "product_deleted", nullable = false)
    private boolean productDeleted;

    /** JPA-only no-args constructor. Do not call from application code. */
    protected ProductRef() {}

    public ProductRef(UUID variantId, UUID productId, String title) {
        this.variantId = Objects.requireNonNull(variantId, "variantId must not be null");
        this.productId = Objects.requireNonNull(productId, "productId must not be null");
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        this.title = title;
        this.productDeleted = false;
    }

    public UUID variantId() {
        return variantId;
    }

    public String title() {
        return title;
    }

    public UUID productId() {
        return productId;
    }

    public boolean isProductDeleted() {
        return productDeleted;
    }

    /**
     * Marks the owning product as gone from the catalogue.
     *
     * <p>There is no inverse. A product cannot be un-deleted in catalog, so an
     * un-flag would only ever be reached by a replayed or reordered event —
     * see {@code updateTitle}, which refuses to clear this for the same
     * reason.
     */
    public void markProductDeleted() {
        this.productDeleted = true;
    }

    /**
     * Updates the cached title to reflect a {@code product.updated} event.
     * The {@link com.oriah.jpa.BaseEntity#updatedAt() updatedAt}
     * field on the superclass is refreshed by the AuditingEntityListener
     * on the next flush.
     */
    /**
     * Refreshes the display title.
     *
     * <p>Deliberately does NOT clear {@link #productDeleted}. A product that
     * has been deleted cannot subsequently be updated, so an update applied
     * after its deletion is always stale and the correct response is to leave
     * the flag alone.
     *
     * <p>The consumer already skips a snapshot older than the newest applied
     * (each product event carries a per-product seq, kept in
     * processed_aggregate), so a late {@code product.updated} normally never
     * gets here. The flag stays monotonic anyway: it does not depend on every
     * producer numbering its events correctly.
     */
    public void updateTitle(String newTitle) {
        if (newTitle == null || newTitle.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        this.title = newTitle;
    }
}
