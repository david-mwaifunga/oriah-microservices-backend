package com.oriah.quote;

import java.math.BigDecimal;
import java.util.UUID;

import com.oriah.money.Ngwee;

/**
 * One line of a payable quote.
 *
 * @param discountPerPiece the promotion's discount on ONE piece, stored per
 *                         line (CLIENT_DECISIONS.md section 1.1: a discount is
 *                         worked out per piece, never split across an order).
 *                         Zero when no promotion applies.
 * @param promotionId      the promotion that took that discount off, or null
 *                         when none did. It is part of what the shopper agreed
 *                         to (oriah-quote/2): the same K10 off from another
 *                         promotion is another quote. A promotion is named only
 *                         where it discounts, so a promotion beside a zero
 *                         discount is refused.
 */
public record QuoteLine(UUID variantId, int quantity, BigDecimal unitPrice, BigDecimal discountPerPiece, UUID promotionId) {

    public QuoteLine {
        if (variantId == null) {
            throw new IllegalArgumentException("a quote line needs its variant");
        }
        if (quantity < 1) {
            throw new IllegalArgumentException("quantity must be at least 1, got " + quantity);
        }
        Ngwee.require(unitPrice, "unitPrice");
        Ngwee.require(discountPerPiece, "discountPerPiece");
        if (unitPrice.signum() < 0) {
            throw new IllegalArgumentException("unitPrice must not be negative");
        }
        if (discountPerPiece.signum() < 0 || discountPerPiece.compareTo(unitPrice) > 0) {
            throw new IllegalArgumentException("discountPerPiece must be between 0 and the unit price");
        }
        if (promotionId != null && discountPerPiece.signum() == 0) {
            throw new IllegalArgumentException("a promotion is named only on a line it takes something off");
        }
    }

    /** A line no promotion touched, or one built before promotions named themselves. */
    public QuoteLine(UUID variantId, int quantity, BigDecimal unitPrice, BigDecimal discountPerPiece) {
        this(variantId, quantity, unitPrice, discountPerPiece, null);
    }
}
