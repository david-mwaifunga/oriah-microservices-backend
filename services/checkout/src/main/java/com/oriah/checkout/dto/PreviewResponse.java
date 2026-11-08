package com.oriah.checkout.dto;

import java.math.BigDecimal;
import java.util.List;

public record PreviewResponse(
        BigDecimal subtotal,
        BigDecimal discount,
        BigDecimal shippingCost,
        BigDecimal total,
        String currency,
        String appliedPromotion,
        List<ShippingOptionDto> shippingOptions,

        /**
         * The prices this quote was built from, as an opaque token.
         *
         * <p>Echo it back on confirm and the server can tell whether the total
         * you agreed to is still the total it would charge. Additive to the
         * contract -- but a client that DISCARDS it cannot confirm at all,
         * because confirm requires it. Zod strips undeclared keys silently, so
         * this field reaching the storefront is a schema change there, not a
         * courtesy.
         */
        String pricingVersion
) {
    /** {@code code} is what the shopper picks and sends back, e.g. SELF_COLLECT. */
    public record ShippingOptionDto(String code, String carrier, String service, BigDecimal cost,
                                    Integer etaDaysMin, Integer etaDaysMax) {}
}
