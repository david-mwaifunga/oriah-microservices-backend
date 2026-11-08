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
 *                         Zero until Phase 7 brings scoped promotions.
 */
public record QuoteLine(UUID variantId, int quantity, BigDecimal unitPrice, BigDecimal discountPerPiece) {

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
    }
}