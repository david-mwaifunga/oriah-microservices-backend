package com.oriah.checkout.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * A priced quote. {@code total = subtotal - discount + shippingCost}, and each line shows its own part of that.
 *
 * @param subtotal     the pieces at their LIST prices
 * @param discount     what promotions take off, worked out per piece and shown per line (zero when none apply)
 * @param shippingCost what the chosen delivery adds to THIS order: zero when the recipient pays the courier, or the goods reach the free-delivery
 *                     threshold; absent until a delivery is chosen
 * @param total        absent until a delivery is chosen -- there is no total to agree to before then
 * @param deliveryOption the option this quote is for; absent when none was asked for
 * @param deliveryTownId the town it is priced for; absent for self-collect
 * @param payer          who pays the courier; absent for self-collect
 * @param quoteDigest  the digest of this whole quote: echo it on confirm, which places exactly this quote or refuses with 409 pricing-changed. Absent
 *                     whenever {@code total} is.
 * @param lines        what each piece costs, and what took something off it
 */
public record PreviewResponse(
        BigDecimal subtotal,
        BigDecimal discount,
        BigDecimal shippingCost,
        BigDecimal total,
        String currency,
        String deliveryOption,
        UUID deliveryTownId,
        String payer,
        String quoteDigest,
        List<LineDto> lines
) {
    /** {@code unitPrice} is the LIST price; {@code netUnitPrice} what one piece costs after {@code discountPerPiece}. */
    public record LineDto(UUID variantId, String title, int quantity, BigDecimal unitPrice, BigDecimal discountPerPiece, UUID promotionId,
                          BigDecimal netUnitPrice) {}
}
