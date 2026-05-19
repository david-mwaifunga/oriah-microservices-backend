package com.oriah.cart.model;

import java.math.BigDecimal;
import java.time.Instant;
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
public class Cart {

    @Id
    @Column(nullable = false)
    private UUID id;

    @Column(name = "keycloak_id", nullable = false, unique = true)
    private String keycloakId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "cart", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<CartItem> items = new ArrayList<>();

    protected Cart() {}

    public Cart(String keycloakId) {
        this.id = UUID.randomUUID();
        this.keycloakId = keycloakId;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    public UUID id() { return id; }
    public String keycloakId() { return keycloakId; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public List<CartItem> items() { return Collections.unmodifiableList(items); }

    /**
     * Adds an item or, if the variant is already in the cart, bumps its
     * quantity by {@code quantity}. Returns the resulting line item.
     * The unit price + title are NOT updated on a bump — the cart holds
     * the price snapshot from when the customer first added the item.
     */
    public CartItem addOrIncrement(UUID variantId, int quantity, BigDecimal unitPrice, String title) {
        if (quantity <= 0) throw new IllegalArgumentException("quantity must be > 0");
        Optional<CartItem> existing = items.stream()
                .filter(i -> i.variantId().equals(variantId))
                .findFirst();
        CartItem item = existing
                .map(i -> { i.bump(quantity); return i; })
                .orElseGet(() -> {
                    CartItem fresh = new CartItem(this, variantId, quantity, unitPrice, title);
                    items.add(fresh);
                    return fresh;
                });
        this.updatedAt = Instant.now();
        return item;
    }

    public Optional<CartItem> updateQuantity(UUID variantId, int newQuantity) {
        if (newQuantity <= 0) throw new IllegalArgumentException("quantity must be > 0");
        Optional<CartItem> item = items.stream().filter(i -> i.variantId().equals(variantId)).findFirst();
        item.ifPresent(i -> { i.setQuantity(newQuantity); this.updatedAt = Instant.now(); });
        return item;
    }

    public boolean removeVariant(UUID variantId) {
        boolean removed = items.removeIf(i -> i.variantId().equals(variantId));
        if (removed) this.updatedAt = Instant.now();
        return removed;
    }

    public void clear() {
        items.clear();
        this.updatedAt = Instant.now();
    }

    /** Subtotal — sum of (qty * unitPrice) across all line items. */
    public BigDecimal subtotal() {
        return items.stream()
                .map(i -> i.unitPrice().multiply(BigDecimal.valueOf(i.quantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
