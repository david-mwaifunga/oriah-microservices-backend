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

import com.oriah.jpa.AuditableEntity;

@Entity
@Table(name = "cart_items")
public class CartItem extends AuditableEntity {

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

    @Column(nullable = false)
    private String title;

    @Column(name = "added_at", nullable = false)
    private Instant addedAt;

    protected CartItem() {}

    CartItem(Cart cart, UUID variantId, int quantity, BigDecimal unitPrice, String title) {
        this.id = UUID.randomUUID();
        this.cart = cart;
        this.variantId = variantId;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
        this.title = title;
        this.addedAt = Instant.now();
    }

    public UUID id() { return id; }
    public UUID variantId() { return variantId; }
    public Integer quantity() { return quantity; }
    public BigDecimal unitPrice() { return unitPrice; }
    public String title() { return title; }
    public Instant addedAt() { return addedAt; }

    void bump(int by) { this.quantity += by; }
    void setQuantity(int q) { this.quantity = q; }
}
