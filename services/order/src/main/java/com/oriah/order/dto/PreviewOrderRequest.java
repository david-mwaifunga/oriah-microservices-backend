package com.oriah.order.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.oriah.money.NgweeAmount;
import com.oriah.order.model.SaleKind;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Null;
import jakarta.validation.constraints.Size;

/**
 * What staff send to read the total of a sale to the caller before recording it (Phase 9): the sale's kind, what is bought and how it goes, and (for a paper sale)
 * when. It carries no caller, no recipient, no address and no expected total: a price-only call holds no personal data and has nothing to confirm yet. As when recording,
 * a typed price, discount, shipping amount or total is refused (400).
 *
 * @param kind           {@code LIVE_PHONE} (priced now) or {@code LATE_PAPER} (priced as of {@code occurredAt})
 * @param lines          what is bought; the box defaults to the product's own unless a line names another
 * @param delivery       how it goes: a pickup, the shop's published town price, or a delivery agreed by phone
 * @param occurredAt     a late paper sale's true date; refused on a live sale
 * @param backdateReason why a late paper sale is recorded late (needed to preview a sale older than 72 hours by someone who may back-date)
 */
public record PreviewOrderRequest(
        @NotNull SaleKind kind,
        @NotEmpty @Size(min = 1, max = 100) @Valid List<RecordOrderRequest.Line> lines,
        @NotNull @Valid RecordOrderRequest.Delivery delivery,
        Instant occurredAt,
        @Size(max = 200) String backdateReason,
        @Null(message = "a total cannot be typed: the server works it out") @NgweeAmount @Schema(hidden = true) BigDecimal total,
        @Null(message = "a discount cannot be typed: promotions apply by themselves") @NgweeAmount @Schema(hidden = true) BigDecimal discount,
        @Null(message = "a shipping amount cannot be typed: an agreed delivery goes in delivery.agreedAmount") @NgweeAmount @Schema(hidden = true) BigDecimal shippingAmount) {

    /** The same sale as a recording request with nothing personal and nothing to confirm, which is all pricing reads. */
    public RecordOrderRequest asSale() {
        return new RecordOrderRequest(kind, null, lines, delivery, null, null, occurredAt, backdateReason, null, null, null, null, null);
    }
}
