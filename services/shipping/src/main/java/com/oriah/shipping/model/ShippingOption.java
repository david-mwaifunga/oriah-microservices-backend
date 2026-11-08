package com.oriah.shipping.model;

import java.math.BigDecimal;

/**
 * One way the shopper can receive the order, and what it costs.
 *
 * @param code       stable identifier the shopper's choice is carried by, from
 *                   preview through confirm to the order (item 2). Until Phase
 *                   7 the only code is {@link #SELF_COLLECT}; Phase 7 adds a
 *                   code per town and delivery option.
 * @param etaDaysMin null when nobody has promised a time -- self-collect has none
 * @param etaDaysMax null likewise
 */
public record ShippingOption(
        String code,
        String carrier,
        String service,
        BigDecimal cost,
        String currency,
        Integer etaDaysMin,
        Integer etaDaysMax
) {

    /** The customer collects the piece from Oriah, at no charge. */
    public static final String SELF_COLLECT = "SELF_COLLECT";
}