package com.oriah.quote;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;

import com.oriah.money.Ngwee;

/**
 * The whole payable quote: what the shopper is shown at preview and agrees to
 * pay at confirm (MVP_PLAN Phase 0, item 4.4).
 *
 * <p>{@link #total()} is THE formula -- subtotal, less the per-piece
 * discounts, plus delivery -- and the order aggregate is tested against it,
 * so the digest can never bind a total other than the one charged.
 *
 * @param currency       ISO code; read without regard to case or padding
 * @param deliveryOption the option code the shopper chose, e.g. SELF_COLLECT
 * @param deliveryAmount what that option costs; K0 for self-collect
 */
public record Quote(List<QuoteLine> lines, String currency, String deliveryOption, BigDecimal deliveryAmount) {

    /** An option code: SELF_COLLECT now, a code per town and option from Phase 7. */
    public static final String DELIVERY_OPTION_PATTERN = "^[A-Z][A-Z0-9_]{0,63}$";

    /** The one option that needs no delivery address: the shopper collects. */
    public static final String SELF_COLLECT = "SELF_COLLECT";

    public Quote {
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("a quote needs at least one line");
        }
        lines = List.copyOf(lines);
        currency = currency == null ? "" : currency.trim().toUpperCase(Locale.ROOT);
        if (!currency.matches("^[A-Z]{3}$")) {
            throw new IllegalArgumentException("currency must be a three-letter code");
        }
        if (deliveryOption == null || !deliveryOption.matches(DELIVERY_OPTION_PATTERN)) {
            throw new IllegalArgumentException("deliveryOption must be an option code such as SELF_COLLECT");
        }
        Ngwee.require(deliveryAmount, "deliveryAmount");
        if (deliveryAmount.signum() < 0) {
            throw new IllegalArgumentException("deliveryAmount must not be negative");
        }
    }

    /** The lines at their unit prices, before any discount. */
    public BigDecimal subtotal() {
        return lines.stream()
                .map(l -> l.unitPrice().multiply(BigDecimal.valueOf(l.quantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.UNNECESSARY);
    }

    /** The per-piece discounts, summed over every piece. */
    public BigDecimal discount() {
        return lines.stream()
                .map(l -> l.discountPerPiece().multiply(BigDecimal.valueOf(l.quantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.UNNECESSARY);
    }

    /** What the shopper pays: subtotal - discount + delivery. */
    public BigDecimal total() {
        return subtotal().subtract(discount()).add(deliveryAmount).setScale(2, RoundingMode.UNNECESSARY);
    }
}