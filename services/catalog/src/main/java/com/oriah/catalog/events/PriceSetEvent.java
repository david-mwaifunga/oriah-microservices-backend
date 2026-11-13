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
 * <p>Phase 7: {@code unitPrice} is the LIST price saved; {@code discountPerPiece}, {@code promotionId} and {@code netPrice} say what the promotion
 * in force when it was saved made of it, and {@code assessment} is about the NET price (what a customer pays per piece). {@code discountPerPiece}
 * is 0.00 and {@code promotionId} null when nothing applied. These three come last, so a reader of the first eleven fields reads on unchanged.
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
        Map<String, Object> cost,
        BigDecimal discountPerPiece,
        UUID promotionId,
        BigDecimal netPrice) {

    public static final String TYPE = "price.set";
}
