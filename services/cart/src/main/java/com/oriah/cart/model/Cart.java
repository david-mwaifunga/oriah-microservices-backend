package com.oriah.cart.model;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import com.oriah.jpa.BaseEntity;

/**
 * Shopping cart owned by a Keycloak user (the JWT {@code sub}). Lazy-
 * created on first add-to-cart and persisted across sessions.
 *
 * <p>Cart is the aggregate root for cart items: adding/removing items
 * goes through methods here so the (cart, variant) uniqueness rule and
 * timestamps stay consistent.
 */
@Entity
@Table(name = "carts")
public class Cart extends BaseEntity {

    @Id
    @Column(nullable = false)
    private UUID id;

    @Column(name = "keycloak_id", nullable = false, unique = true)
    private String keycloakId;

    @OneToMany(mappedBy = "cart", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<CartItem> items = new ArrayList<>();

    /**
     * The most lines one bag may hold.
     *
     * <p>100 because that is catalog's limit on a single pricing request
     * ({@code @Size(max = 100)} on {@code VariantPricingRequest.variantIds}),
     * and catalog's javadoc already claims "cart enforces the same limit on
     * lines per cart, so a bag that cannot be priced in one request cannot
     * exist". Until this constant existed that claim was false: a 101-line bag
     * was constructible and every pricing call for it was rejected, stranding
     * the shopper behind a 400 with no way back except removing lines.
     */
    public static final int MAX_LINES = 100;

    /**
     * The most units of one variant a single line may hold.
     *
     * <p>A two-digit quantity is past any plausible retail purchase of a piece
     * of jewellery, so this is an upper sanity bound rather than a business
     * rule. Its real job is to make the quantity arithmetic bounded: without
     * any maximum, {@code quantity += by} on an Integer wrapped negative.
     */
    public static final int MAX_QUANTITY_PER_LINE = 99;

    protected Cart() {}

    public Cart(String keycloakId) {
        this.id = UUID.randomUUID();
        this.keycloakId = keycloakId;
    }

    public UUID id() { return id; }
    public String keycloakId() { return keycloakId; }
    public List<CartItem> items() { return Collections.unmodifiableList(items); }

    /**
     * Adds a line, or increases the quantity of one already present and
     * refreshes its price snapshot. Returns the resulting line.
     *
     * <h2>Price, title and currency come from the caller's pricing lookup</h2>
     *
     * They used to come from the request body, which is how a K4,500 ring could
     * be bought for one cent. This method is the aggregate's only write door
     * for a price, so it is the one place that can refuse an unpriced one, and
     * a bump now REFRESHES the snapshot rather than keeping the first one --
     * otherwise a repriced variant is sold at the stale price on every
     * subsequent add.
     *
     * <h2>Both caps are checked against the RESULT, in long arithmetic</h2>
     *
     * The quantity check is on {@code existing + quantity}, widened to a long
     * first. Checking the incoming quantity alone would let two legal adds
     * store an illegal sum, and doing the sum in {@code int} overflows for a
     * large increment -- producing a negative that sails under the cap, which
     * is the very input the cap exists to stop.
     *
     * <p>The lines cap is only consulted when a NEW line would be created. A
     * full bag whose shopper wants a second of something already in it adds no
     * line, and refusing that would be a bug in the guard.
     *
     * @throws IllegalArgumentException the request is nonsense on its face --
     *         a non-positive quantity or an unpriced variant (400)
     * @throws IllegalStateException    the request conflicts with the bag's
     *         current contents -- either cap exceeded (409 via the shared
     *         advice, with no new exception handler in cart)
     */
    public CartItem addOrIncrement(UUID variantId, int quantity, BigDecimal unitPrice,
                                   String title, String currency) {
        if (quantity <= 0) throw new IllegalArgumentException("quantity must be > 0");
        // Zero is catalog's "nobody has priced this yet" marker for a draft
        // variant, so a zero-priced line is not a free item -- it is an item
        // whose price nobody has decided. Refusing it here keeps the aggregate
        // consistent with the database CHECK.
        if (unitPrice == null || unitPrice.signum() <= 0) {
            throw new IllegalArgumentException("unitPrice must be > 0");
        }

        Optional<CartItem> existing = items.stream()
                .filter(i -> i.variantId().equals(variantId))
                .findFirst();

        // Note: BaseEntity's @LastModifiedDate updated_at is refreshed
        // automatically by AuditingEntityListener on the next save() / flush().
        if (existing.isPresent()) {
            CartItem line = existing.get();
            long resulting = (long) line.quantity() + quantity;
            requireWithinQuantityCap(resulting);
            line.bumpTo((int) resulting, unitPrice, title, currency);
            return line;
        }

        requireWithinQuantityCap(quantity);
        if (items.size() >= MAX_LINES) {
            throw new IllegalStateException("a cart may hold at most " + MAX_LINES
                    + " lines; remove something before adding another variant");
        }
        CartItem fresh = new CartItem(this, variantId, quantity, unitPrice, title, currency);
        items.add(fresh);
        return fresh;
    }

    public Optional<CartItem> updateQuantity(UUID variantId, int newQuantity) {
        if (newQuantity <= 0) throw new IllegalArgumentException("quantity must be > 0");
        // Capped here too, or the cap is one PATCH away from being useless.
        requireWithinQuantityCap(newQuantity);
        Optional<CartItem> item = items.stream().filter(i -> i.variantId().equals(variantId)).findFirst();
        item.ifPresent(i -> i.setQuantity(newQuantity));
        return item;
    }

    /** @param resulting a long on purpose: see addOrIncrement's javadoc. */
    private static void requireWithinQuantityCap(long resulting) {
        if (resulting > MAX_QUANTITY_PER_LINE) {
            throw new IllegalStateException("a single line may hold at most "
                    + MAX_QUANTITY_PER_LINE + " units");
        }
    }

    public boolean removeVariant(UUID variantId) {
        return items.removeIf(i -> i.variantId().equals(variantId));
    }

    public void clear() {
        items.clear();
    }

    /** Subtotal — sum of (qty * unitPrice) across all line items. */
    public BigDecimal subtotal() {
        return items.stream()
                .map(i -> i.unitPrice().multiply(BigDecimal.valueOf(i.quantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
