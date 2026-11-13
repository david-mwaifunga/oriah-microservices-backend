package com.oriah.catalog.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * {@code price.set}: a price was saved. One per saved price, on the topic {@code catalog.price}, aggregate {@code variant_price} (the
 * id of the history row), occurred when the price took effect.
 *
 * <p>The person who saved it is the envelope's actor ({@code user:<subject>}); {@code setByName} is the name the token carried, if
 * any, and may be null. {@code assessment} is what the below-cost rule observed (OK, BELOW, NO_COST_KNOWN, CURRENCY_MISMATCH) and
 * {@code reason} why a price below cost was accepted. {@code cost} summarises what the price was compared with (the floor and its
 * parts, the lots by id and revision, the default box, the currencies, when it was read): the figures stay reproducible from
 * inventory's lots, so the units held and each lot's cost are not copied here.
 *
 * <p>Nothing consumes this topic yet.
 */
public record PriceSetEvent(
        UUID variantId,
        UUID productId,
        BigDecimal unitPrice,
        BigDecimal previousUnitPrice,
        String currency,
        Instant effectiveFrom,
        Instant recordedAt,
        String assessment,
        String reason,
        String setByName,
        Map<String, Object> cost) {

    public static final String TYPE = "price.set";
}
