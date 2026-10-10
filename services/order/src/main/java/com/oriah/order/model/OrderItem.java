package com.oriah.order.model;

import java.math.BigDecimal;
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
@Table(name = "order_items")
public class OrderItem extends BaseEntity {

    @Id
    @Column(nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Column(name = "variant_id", nullable = false)
    private UUID variantId;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private Integer quantity;

    @Column(name = "unit_price", nullable = false)
    private BigDecimal unitPrice;

    protected OrderItem() {}

    OrderItem(Order order, UUID variantId, String title, int quantity, BigDecimal unitPrice) {
        this.id = UUID.randomUUID();
        this.order = order;
        this.variantId = variantId;
        this.title = title;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
    }

    public UUID id() { return id; }
    public UUID variantId() { return variantId; }
    public String title() { return title; }
    public Integer quantity() { return quantity; }
    public BigDecimal unitPrice() { return unitPrice; }
}
