package com.oriah.catalog.model;

import com.oriah.money.Ngwee;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.oriah.jpa.BaseEntity;

/**
 * A purchasable configuration of a product: this ring in size M, this watch on
 * an alligator strap. The variant — not the product — carries the SKU and the
 * price.
 *
 * <h2>Why the id matters more here than anywhere else in the codebase</h2>
 *
 * A variant's id is used AS the key in four other services' databases:
 * {@code inventory.variant_id} and {@code product_index.variant_id} (both
 * primary keys), {@code cart_items.variant_id} (the line identity, under
 * {@code UNIQUE (cart_id, variant_id)}), {@code order_items.variant_id} (an
 * immutable financial snapshot) and {@code search_index.variant_id}. None of
 * those has a foreign key pointing here, because they are separate databases.
 *
 * <p>That is why {@code V3} gave every pre-existing product a variant whose id
 * IS the product's id, and why a variant is never physically deleted — see
 * {@link #deactivate()}.
 *
 * <h2>Money</h2>
 *
 * {@code NUMERIC(12,2)} and {@code BigDecimal}, matching every other money
 * column in the platform (cart, order, payment, promotion). The currency
 * defaults to the brand's — Zambian Kwacha, which has two minor units, so the
 * shape holds. A variant carries its own currency rather than inheriting a
 * global one so that the single-currency-per-cart rule has something concrete
 * to check.
 */
@Entity
@Table(name = "product_variants")
public class ProductVariant extends BaseEntity {

    /** The brand default. Overridable per variant; see the class javadoc. */
    public static final String DEFAULT_CURRENCY = "ZMW";

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(nullable = false, unique = true)
    private String sku;

    @Column(name = "unit_price", nullable = false)
    private BigDecimal unitPrice;

    @Column(nullable = false, length = 3)
    private String currency;

    /**
     * The option combination, e.g. {@code {"Ring Size": "M"}}. Empty for a
     * one-off piece that varies on no axis, which is what the V3 backfill
     * produced for the entire existing catalogue.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, String> options = new LinkedHashMap<>();

    /**
     * SHA-256 of the canonical JSON of {@link #options}, maintained in step
     * with it. It exists so {@code UNIQUE (product_id, option_digest)} can
     * reject a duplicate combination in the database rather than in a
     * read-then-write check that two concurrent admins both pass.
     */
    @Column(name = "option_digest", nullable = false, length = 64)
    private String optionDigest;

    @Column(nullable = false)
    private int position;

    @Column(nullable = false)
    private boolean active;

    protected ProductVariant() {
        // For JPA hydration only.
    }

    public ProductVariant(Product product, String sku, BigDecimal unitPrice,
                          String currency, Map<String, String> options) {
        this(UUID.randomUUID(), product, sku, unitPrice, currency, options);
    }

    /**
     * Reconstitution constructor. Public because the V3-backfilled variants —
     * and only those — must be materialised with an id equal to their
     * product's, and because tests need deterministic ids.
     */
    public ProductVariant(UUID id, Product product, String sku, BigDecimal unitPrice,
                          String currency, Map<String, String> options) {
        if (product == null) {
            throw new IllegalArgumentException("variant must belong to a product");
        }
        if (sku == null || sku.isBlank()) {
            throw new IllegalArgumentException("sku must not be blank");
        }
        if (unitPrice == null || unitPrice.signum() < 0) {
            throw new IllegalArgumentException("unitPrice must not be negative");
        }
        Ngwee.require(unitPrice, "unitPrice");
        this.id = id;
        this.product = product;
        this.sku = sku.trim();
        this.unitPrice = unitPrice;
        this.currency = normaliseCurrency(currency);
        applyOptions(options);
        this.position = 0;
        this.active = true;
    }

    private static String normaliseCurrency(String currency) {
        if (currency == null || currency.isBlank()) {
            return DEFAULT_CURRENCY;
        }
        String code = currency.trim().toUpperCase();
        if (code.length() != 3) {
            throw new IllegalArgumentException("currency must be a 3-letter ISO 4217 code");
        }
        return code;
    }

    /** Options and digest are always set together, so they cannot drift. */
    private void applyOptions(Map<String, String> raw) {
        Map<String, String> validated = VariantOptions.validated(raw);
        this.options = new LinkedHashMap<>(validated);
        this.optionDigest = VariantOptions.digest(validated);
    }

    public UUID id() {
        return id;
    }

    public Product product() {
        return product;
    }

    public String sku() {
        return sku;
    }

    public BigDecimal unitPrice() {
        return unitPrice;
    }

    public String currency() {
        return currency;
    }

    public Map<String, String> options() {
        return Map.copyOf(options);
    }

    public String optionDigest() {
        return optionDigest;
    }

    public int position() {
        return position;
    }

    public boolean isActive() {
        return active;
    }

    public void reprice(BigDecimal newPrice, String newCurrency) {
        if (newPrice == null || newPrice.signum() < 0) {
            throw new IllegalArgumentException("unitPrice must not be negative");
        }
        Ngwee.require(newPrice, "unitPrice");
        this.unitPrice = newPrice;
        this.currency = normaliseCurrency(newCurrency);
    }

    /**
     * Replaces the option combination, keeping {@link #optionDigest} in step.
     *
     * <p>The caller is responsible for checking that the new key set still
     * matches the product's declared axes and does not collide with a sibling
     * -- the product owns both of those invariants because it is the only
     * thing that can see the other variants.
     */
    public void updateOptions(Map<String, String> newOptions) {
        applyOptions(newOptions);
    }

    public void setPosition(int newPosition) {
        this.position = newPosition;
    }

    /**
     * Withdraws the variant from sale. There is no physical delete: this id is
     * a key in four other databases that no foreign key protects, so catalog
     * cannot prove a delete is safe. Deactivating is the operation that is
     * always correct.
     */
    public void deactivate() {
        this.active = false;
    }

    public void reactivate() {
        this.active = true;
    }

    /**
     * Whether this variant can actually be sold — active and priced above
     * zero. A zero price is the V3 backfill's marker for "nobody has priced
     * this yet", so it must not count towards a product being publishable.
     */
    public boolean isPurchasable() {
        // Zero is not a price -- it is the V3 backfill's marker for "nobody
        // has priced this yet". Counting it would have made the entire
        // migrated catalogue free the moment someone published it.
        return active && unitPrice != null && unitPrice.signum() > 0;
    }
}
