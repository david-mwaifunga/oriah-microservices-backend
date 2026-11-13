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

    /** The LIST price of one piece; what a promotion took off it is {@link #discountPerPiece}, so the sale is never edited to show a discount. */
    @Column(name = "unit_price", nullable = false)
    private BigDecimal unitPrice;

    /** The product the variant belongs to: inventory's hold needs it (a box is set aside per product). Null on a line placed before Phase 7. */
    @Column(name = "product_id")
    private UUID productId;

    /** What a promotion took off EACH piece (CLIENT_DECISIONS 1.1: worked out per piece, stored per line); null before Phase 7, which is no discount. */
    @Column(name = "discount_per_piece")
    private BigDecimal discountPerPiece;

    /** The promotion that gave it; null when none did. */
    @Column(name = "promotion_id")
    private UUID promotionId;

    /** The box that goes with each piece, as inventory chose it when it held them; null until then. */
    @Column(name = "box_variant_id")
    private UUID boxVariantId;

    /** The id the box is sold under (the line's own id is the goods'); minted with the line so a repeated hold names the same ones. */
    @Column(name = "box_line_id")
    private UUID boxLineId;

    protected OrderItem() {}

    OrderItem(Order order, UUID variantId, String title, int quantity, BigDecimal unitPrice) {
        this.id = UUID.randomUUID();
        this.order = order;
        this.variantId = variantId;
        this.title = title;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
    }

    /** A line of the sale as Phase 7 places it: with its product, the promotion's discount per piece, and the id its box will be sold under. */
    OrderItem(Order order, UUID variantId, UUID productId, String title, int quantity, BigDecimal unitPrice,
              BigDecimal discountPerPiece, UUID promotionId) {
        this(order, variantId, title, quantity, unitPrice);
        this.productId = productId;
        this.discountPerPiece = discountPerPiece;
        this.promotionId = promotionId;
        this.boxLineId = UUID.randomUUID();
    }

    /** Remembers the box inventory chose for this line when it held the pieces. */
    void holdBox(UUID boxVariantId) {
        this.boxVariantId = boxVariantId;
    }

    public UUID id() { return id; }
    public UUID variantId() { return variantId; }
    public String title() { return title; }
    public Integer quantity() { return quantity; }
    public BigDecimal unitPrice() { return unitPrice; }
    public UUID productId() { return productId; }
    public UUID promotionId() { return promotionId; }
    public UUID boxVariantId() { return boxVariantId; }
    public UUID boxLineId() { return boxLineId; }

    /** What a promotion took off each piece; zero when none did (a line placed before Phase 7 carries none). */
    public BigDecimal discountPerPiece() { return discountPerPiece == null ? BigDecimal.ZERO : discountPerPiece; }
}
