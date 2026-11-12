package com.oriah.procurement.model;

import java.math.BigDecimal;
import java.util.UUID;

import com.oriah.jpa.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * One item on a purchase order: which variant, how many, and the all-in total that
 * left the bank for them.
 *
 * <p>The total is NOT a unit price times a quantity. The bank app shows one figure for
 * the line, and that is what is recorded (CLIENT_DECISIONS 3.1); a unit price would be
 * a division nobody made, and one that does not divide evenly. How the total becomes
 * unit costs is the allocator's job, at the unit level, once the goods land.
 *
 * <p>A line is only ever created and replaced through its {@link PurchaseOrder}: the
 * order owns the rules (an order's lines are numbered, capped and distinct). The names
 * are what the catalogue called the variant when it was ordered.
 */
@Entity
@Table(name = "purchase_order_line")
public class PurchaseOrderLine extends BaseEntity {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "purchase_order_id", nullable = false, updatable = false)
    private PurchaseOrder purchaseOrder;

    @Column(nullable = false)
    private int position;

    @Column(name = "variant_id", nullable = false, updatable = false)
    private UUID variantId;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "total_paid", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalPaid;

    @Column(nullable = false, length = 64)
    private String sku;

    @Column(name = "product_title", length = 200)
    private String productTitle;

    @Column(name = "variant_title", nullable = false, length = 300)
    private String variantTitle;

    @Column(nullable = false, length = 10)
    private String kind;

    protected PurchaseOrderLine() {
        // JPA
    }

    PurchaseOrderLine(PurchaseOrder purchaseOrder, int position, PurchaseOrder.NewLine line) {
        this.id = UUID.randomUUID();
        this.purchaseOrder = purchaseOrder;
        this.position = position;
        this.variantId = line.variantId();
        this.quantity = line.quantity();
        this.totalPaid = line.totalPaid();
        this.sku = line.sku();
        this.productTitle = line.productTitle();
        this.variantTitle = line.variantTitle();
        this.kind = line.kind();
    }

    public UUID id() {
        return id;
    }

    public int position() {
        return position;
    }

    public UUID variantId() {
        return variantId;
    }

    public int quantity() {
        return quantity;
    }

    public BigDecimal totalPaid() {
        return totalPaid;
    }

    public String sku() {
        return sku;
    }

    public String productTitle() {
        return productTitle;
    }

    public String variantTitle() {
        return variantTitle;
    }

    /** SELLABLE or PACKAGING, as the catalogue said when it was ordered. */
    public String kind() {
        return kind;
    }
}
