package com.oriah.finance.events.inbound;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * {@code order.sale_cancelled} and {@code order.return_recorded} (Phase 12), as finance reads them: which sale, what was taken back line by line, the delivery and the
 * waiver a cancellation takes back, and what is owed. Who the customer is, the receipt number, the reason and who bears the sending charge are left unread on purpose.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SaleCorrected(UUID correctionId, UUID orderId, String kind, UUID confirmationId, Instant saleArrivedAt, Instant occurredAt, Instant recordedAt, String currency,
                            List<Line> lines, BigDecimal goodsAtList, BigDecimal discounts, BigDecimal deliveryRefunded, BigDecimal waiverTakenBack, BigDecimal owedGross) {

    /** Pieces of one sale line taken back, at the price and discount the line was sold at. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Line(UUID lineId, UUID variantId, UUID productId, int quantity, BigDecimal unitPrice, BigDecimal discountPerPiece, UUID promotionId) {
    }
}
