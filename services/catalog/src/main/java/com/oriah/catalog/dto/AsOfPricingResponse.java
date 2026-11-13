package com.oriah.catalog.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * What each asked-for variant cost at a past instant, and what promotion applied then.
 *
 * <p>Unlike the storefront's read, this ignores whether a piece is on sale TODAY: a ring withdrawn since was still sold at its price on the
 * day, and a paper sale for it must be recordable. Only packaging is refused (it is never sold on its own). A piece with no price at that
 * date is {@code NO_PRICE_AT_DATE} and carries no price: it is never priced at zero and never at today's price.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AsOfPricingResponse(Instant at, List<Entry> results) {

    public enum Status {

        /** A price was in force at that instant; the price fields are populated. */
        PRICED,

        /** The variant exists but had no price yet at that instant (it was first priced later, or never). No price fields. */
        NO_PRICE_AT_DATE,

        /** No such variant, or a packaging entry. */
        UNKNOWN
    }

    /**
     * @param unitPrice        the LIST price in force at the instant
     * @param priceId          the price-history row that gave it, so a recorded sale can say which price it used
     * @param discountPerPiece Kwacha off each piece by the promotion in force at the instant; 0.00 when none
     * @param promotionId      that promotion; absent when none
     * @param netUnitPrice     {@code unitPrice - discountPerPiece}
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Entry(
            UUID variantId,
            Status status,
            UUID productId,
            String productTitle,
            String variantTitle,
            BigDecimal unitPrice,
            String currency,
            UUID priceId,
            BigDecimal discountPerPiece,
            UUID promotionId,
            BigDecimal netUnitPrice
    ) {

        public static Entry unknown(UUID variantId) {
            return new Entry(variantId, Status.UNKNOWN, null, null, null, null, null, null, null, null, null);
        }

        public static Entry noPriceAtDate(UUID variantId, UUID productId, String productTitle, String variantTitle) {
            return new Entry(variantId, Status.NO_PRICE_AT_DATE, productId, productTitle, variantTitle, null, null, null, null, null, null);
        }
    }
}
