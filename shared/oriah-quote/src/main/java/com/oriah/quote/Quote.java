package com.oriah.quote;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

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
 * @param deliveryAmount what that option costs ON THIS ORDER; K0 for self-collect
 *                       and for a courier the recipient pays on arrival
 * @param deliveryTownId the town the delivery is priced for (Phase 7: prices are
 *                       per town), or null when none applies -- self-collect, or
 *                       a price staff agreed by phone
 * @param payer          who pays the courier: {@link #ORIAH_COLLECTS} (it is in
 *                       the total) or {@link #RECIPIENT_PAYS} (paid on arrival:
 *                       K0 on this order), or null when none applies
 */
public record Quote(List<QuoteLine> lines, String currency, String deliveryOption, BigDecimal deliveryAmount,
                    UUID deliveryTownId, String payer) {

    /** An option code: SELF_COLLECT, or the code of an option such as OWN_DELIVERY, YANGO. */
    public static final String DELIVERY_OPTION_PATTERN = "^[A-Z][A-Z0-9_]{0,63}$";

    /** The one option that needs no delivery address: the shopper collects. */
    public static final String SELF_COLLECT = "SELF_COLLECT";

    /** The delivery charge is in the total and Oriah pays the courier. */
    public static final String ORIAH_COLLECTS = "ORIAH_COLLECTS";

    /** The recipient pays the courier directly, so the money never reaches Oriah: K0 on this order. */
    public static final String RECIPIENT_PAYS = "RECIPIENT_PAYS";

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
        if (payer != null && !ORIAH_COLLECTS.equals(payer) && !RECIPIENT_PAYS.equals(payer)) {
            throw new IllegalArgumentException("payer must be " + ORIAH_COLLECTS + " or " + RECIPIENT_PAYS);
        }
        if (SELF_COLLECT.equals(deliveryOption) && (deliveryTownId != null || payer != null)) {
            throw new IllegalArgumentException("self-collect has no delivery town and no courier to pay");
        }
        if (RECIPIENT_PAYS.equals(payer) && deliveryAmount.signum() != 0) {
            throw new IllegalArgumentException("a courier the recipient pays costs nothing on this order");
        }
    }

    /** A quote with no delivery town and no payer: self-collect, or a price agreed by phone. */
    public Quote(List<QuoteLine> lines, String currency, String deliveryOption, BigDecimal deliveryAmount) {
        this(lines, currency, deliveryOption, deliveryAmount, null, null);
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
