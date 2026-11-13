package com.oriah.catalog.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * What the catalogue says each requested variant costs.
 *
 * <h2>The status is the point of this shape</h2>
 *
 * Returning a price or a 404 would be enough for a buy button, but not for a
 * shopping bag. A bag has to render a line that can no longer be bought, and
 * the right wording depends on why: a variant that was withdrawn is "no longer
 * available", while an id that does not resolve at all is a line the customer
 * should simply be rid of. One 404 cannot express both.
 *
 * <p>So every requested id comes back with a {@link Status}, and callers that
 * only care about price check for {@code PRICED}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record VariantPricingResponse(List<Entry> results) {

    public enum Status {

        /** Live and purchasable; price fields are populated. */
        PRICED,

        /**
         * The variant exists but cannot be sold right now -- it is inactive,
         * or its product is DRAFT or ARCHIVED. Price fields are omitted, so
         * that a stale price cannot be read off this response by accident.
         */
        UNPUBLISHED,

        /** No such variant. */
        UNKNOWN
    }

    /**
     * @param variantTitle     the product title plus a summary of this variant's
     *                         options, e.g. "Aurelia Solitaire Ring - Ring Size M",
     *                         so cart can snapshot a label a human recognises
     *                         instead of a bare SKU
     * @param unitPrice        the LIST price of one piece (what the price list says), before any promotion
     * @param discountPerPiece the Kwacha a promotion takes off EACH piece, never more than the price: 0.00 when none applies
     * @param promotionId      the promotion that gives it; absent when none applies
     * @param netUnitPrice     what one piece costs after the discount: {@code unitPrice - discountPerPiece}. This is what a customer pays per piece.
     */
    public record Entry(
            UUID variantId,
            Status status,
            UUID productId,
            String productTitle,
            String variantTitle,
            BigDecimal unitPrice,
            String currency,
            BigDecimal discountPerPiece,
            UUID promotionId,
            BigDecimal netUnitPrice
    ) {

        public static Entry unknown(UUID variantId) {
            return new Entry(variantId, Status.UNKNOWN, null, null, null, null, null, null, null, null);
        }

        public static Entry unpublished(UUID variantId, UUID productId, String productTitle) {
            return new Entry(variantId, Status.UNPUBLISHED, productId, productTitle, null, null, null, null, null, null);
        }
    }
}
