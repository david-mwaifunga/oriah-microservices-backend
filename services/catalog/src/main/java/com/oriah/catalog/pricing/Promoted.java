package com.oriah.catalog.pricing;

import java.math.BigDecimal;
import java.util.UUID;

import com.oriah.catalog.promotion.Discount;

/**
 * A list price and what a promotion makes of it: the Kwacha off each piece, the promotion that gives it and the NET price (what a customer pays
 * per piece). The below-cost rule judges the NET price, because that is what comes in; the list price is what the price list says.
 * {@code discountPerPiece} is 0.00 and {@code promotionId} null when nothing applies, and then the net price is the list price.
 */
public record Promoted(BigDecimal listPrice, BigDecimal discountPerPiece, UUID promotionId, BigDecimal netPrice) {

    /** No promotion applies: the customer pays the list price. */
    public static Promoted none(BigDecimal listPrice) {
        return new Promoted(listPrice, BigDecimal.ZERO.setScale(2), null, listPrice);
    }

    /** @param discount what promotion gives this piece, or null if nothing applies */
    public static Promoted of(BigDecimal listPrice, Discount discount) {
        if (discount == null) {
            return none(listPrice);
        }
        return new Promoted(listPrice, discount.perPiece(), discount.promotionId(), listPrice.subtract(discount.perPiece()));
    }
}
