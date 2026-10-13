package com.oriah.cart.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import com.oriah.jpa.BaseEntity;

@Entity
@Table(name = "cart_items")
public class CartItem extends BaseEntity {

    @Id
    @Column(nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cart_id", nullable = false)
    private Cart cart;

    @Column(name = "variant_id", nullable = false)
    private UUID variantId;

    @Column(nullable = false)
    private Integer quantity;

    @Column(name = "unit_price", nullable = false)
    private BigDecimal unitPrice;

    /**
     * TEXT in the database, not VARCHAR(255).
     *
     * <p>This holds catalog's composed {@code variantTitle} -- a product title
     * of up to 200 characters, plus " - ", plus one {@code name + " " + value}
     * pair per option axis at up to 64 each. One option axis already reaches
     * ~332 characters, so the old VARCHAR(255) would have turned the server's
     * own label into a DataIntegrityViolationException rendered as a 500: the
     * same defect the client-supplied title used to cause, moved to a place no
     * client could trigger or avoid. Matches {@code products.title} in catalog,
     * which is TEXT against a plain String field and passes ddl-auto=validate.
     */
    @Column(nullable = false)
    private String title;

    /**
     * What {@link #unitPrice} is denominated in, as resolved by catalog.
     *
     * <p>Cart had no currency at all: not in either migration, not on this
     * entity. A stored price with no unit cannot be reported by a degraded bag
     * and cannot tell anything downstream what to charge in.
     */
    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "added_at", nullable = false)
    private Instant addedAt;

    /**
     * The brand default, mirroring catalog's
     * {@code ProductVariant.DEFAULT_CURRENCY}. Used only when a caller supplies
     * nothing: the column is NOT NULL, so a null field would be a Hibernate
     * PropertyValueException at flush -- an opaque 500 instead of a sane
     * default.
     */
    public static final String DEFAULT_CURRENCY = "ZMW";

    protected CartItem() {}

    CartItem(Cart cart, UUID variantId, int quantity, BigDecimal unitPrice, String title,
             String currency) {
        this.id = UUID.randomUUID();
        this.cart = cart;
        this.variantId = variantId;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
        this.title = title;
        this.currency = normalise(currency);
        this.addedAt = Instant.now();
    }

    private static String normalise(String currency) {
        return currency == null || currency.isBlank()
                ? DEFAULT_CURRENCY
                : currency.trim().toUpperCase();
    }

    public UUID id() { return id; }
    public UUID variantId() { return variantId; }
    public Integer quantity() { return quantity; }
    public BigDecimal unitPrice() { return unitPrice; }
    public String title() { return title; }
    public String currency() { return currency; }
    public Instant addedAt() { return addedAt; }

    /**
     * Sets the quantity ABSOLUTELY and refreshes the price snapshot.
     *
     * <p>Replaces {@code void bump(int by) { this.quantity += by; }}, and the
     * change of shape is the fix rather than a tidy-up. That {@code +=} ran on
     * an Integer with no maximum anywhere, so two adds of Integer.MAX_VALUE
     * wrapped the quantity negative and the row was rejected by V1's
     * {@code CHECK (quantity > 0)} as a 500. Taking the resulting quantity as a
     * parameter moves the arithmetic into {@link Cart}, where the cap lives and
     * where it can be done in long arithmetic -- see
     * {@code Cart.addOrIncrement}.
     *
     * <p>Refreshing price, title and currency is a deliberate reversal of the
     * old documented behaviour ("the cart holds the price snapshot from when
     * the customer first added the item"). That snapshot made sense while the
     * shopper supplied the price; now that catalog does, keeping the first one
     * means a repriced variant is sold at yesterday's price every time it is
     * added again. Pinned by
     * {@code CartCapsTest.aBumpRefreshesTheLineFromTheNewEntry}.
     */
    void bumpTo(int newQuantity, BigDecimal unitPrice, String title, String currency) {
        this.quantity = newQuantity;
        this.unitPrice = unitPrice;
        this.title = title;
        this.currency = normalise(currency);
    }

    void setQuantity(int q) { this.quantity = q; }
}
