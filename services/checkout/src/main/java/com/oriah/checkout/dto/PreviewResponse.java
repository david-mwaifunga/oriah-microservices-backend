package com.oriah.checkout.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * A priced quote. {@code total = subtotal - discount + shippingCost}.
 *
 * @param discount        zero until Phase 7's scoped promotions
 * @param shippingCost    the chosen option's charge; absent until an offered
 *                        option is chosen
 * @param total           absent until an offered option is chosen -- there is
 *                        no total to agree to before then
 * @param shippingOptions what can be chosen for this destination
 * @param deliveryOption  the option this quote is for; absent when none (or
 *                        one no longer offered) was asked for
 * @param quoteDigest     the digest of this whole quote: echo it on confirm,
 *                        which charges exactly this quote or refuses with 409
 *                        pricing-changed. Absent whenever {@code total} is.
 */
public record PreviewResponse(
        BigDecimal subtotal,
        BigDecimal discount,
        BigDecimal shippingCost,
        BigDecimal total,
        String currency,
        List<ShippingOptionDto> shippingOptions,
        String deliveryOption,
        String quoteDigest
) {
    /** {@code code} is what the shopper picks and sends back, e.g. SELF_COLLECT. */
    public record ShippingOptionDto(String code, String carrier, String service, BigDecimal cost,
                                    Integer etaDaysMin, Integer etaDaysMax) {}
}
