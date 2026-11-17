package com.oriah.order.client;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Inventory, as the ORDER system asks it: hold the pieces (and the box that goes with each) while the shopper pays.
 *
 * <p>Order owns the sale, so order is the caller (Phase 7, design FINAL section B): it mints the reservation id and the order's own line
 * ids, so a repeated ask is answered with the same hold and a line can never be sold twice. These endpoints are for the order system only:
 * they need INVENTORY_ADMIN and the oriah-admin service account, and the gateway does not route them. So order signs in as itself
 * ({@link com.oriah.order.config.InventoryClientIdentity}), never with the shopper's token.
 *
 * <p>No fallback and no circuit breaker (order sets {@code spring.cloud.openfeign.circuitbreaker.enabled: false}): a failed call throws,
 * because "held or not" has no safe invented answer. Its timeouts are in {@code application.yaml} under the client's name and are part of
 * the placement budget {@code WritePathBudgetTest} adds up.
 */
@FeignClient(name = "inventory", configuration = com.oriah.order.config.InventoryClientIdentity.class)
public interface InventoryClient {

    /** Holds every line and its box, all or nothing; asking again with the same reservation id answers the same hold. */
    @PostMapping("/api/v1/admin/inventory/reservations")
    Reservation reserve(@RequestBody ReserveAsk body);

    /**
     * Stretches the hold (a payment reference was submitted): never beyond a day from when the hold was made, never shorter than it is, and
     * asking again changes nothing. 409 {@code reservation-expired} / {@code reservation-not-held} when the hold is already gone.
     */
    /** What inventory says one hold is now (404 when it has none by that id). Used to learn whether a sale whose reply was lost actually happened. */
    @GetMapping("/api/v1/admin/inventory/reservations/{reservationId}")
    Reservation reservation(@PathVariable("reservationId") UUID reservationId);

    @PostMapping("/api/v1/admin/inventory/reservations/{reservationId}/extend")
    Reservation extend(@PathVariable("reservationId") UUID reservationId, @RequestBody ExtendAsk body);

    /** Gives the hold back; releasing one that is not held (sold, released, lapsed) changes nothing, and one inventory never heard of is 404. */
    @PostMapping("/api/v1/admin/inventory/reservations/{reservationId}/release")
    Reservation release(@PathVariable("reservationId") UUID reservationId);

    /**
     * The order has been paid: sells every line of the hold (pieces and boxes drain their oldest lots in one transaction, each under the order's own
     * sale-line id), dated to {@code occurredAt}, the day the money arrived. Asking again answers the same sale and sells nothing more.
     * 409 {@code reservation-not-held} / {@code reservation-expired} when the hold is gone (a lapsed hold cannot be sold even before the sweep ends it).
     */
    @PostMapping("/api/v1/admin/inventory/reservations/{reservationId}/consume")
    Reservation consume(@PathVariable("reservationId") UUID reservationId, @RequestBody ConsumeAsk body);

    record ConsumeAsk(Instant occurredAt) {
    }

    /**
     * A box changed after the order was paid: reverses the original box's sale and sells the replacement, both dated to the day of the SALE, in one
     * transaction keyed by the amendment id. A repeat of the same swap answers it ({@code replay: true}) and changes nothing. 404
     * {@code sale-line-not-found}; 409 {@code amendment-id-used}, {@code not-a-box-sale}, {@code same-box}, {@code already-swapped}, {@code not-packaging},
     * {@code insufficient-stock}.
     */
    @PostMapping("/api/v1/admin/inventory/box-swaps")
    BoxSwap swapBox(@RequestBody SwapAsk body);

    record SwapAsk(UUID amendmentId, UUID orderId, UUID productId, UUID originalSaleLineId, UUID replacementSaleLineId, UUID replacementBoxVariantId,
                   String reason) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record BoxSwap(UUID amendmentId, UUID orderId, UUID productId, UUID originalSaleLineId, UUID replacementSaleLineId, UUID replacementBoxVariantId,
                   int quantity, Instant occurredAt, Instant recordedAt, String reason, boolean replay) {
    }

    record ExtendAsk(Instant until) {
    }

    // ------------------------------------------------------------------ Phase 12: a paid sale undone

    /**
     * Puts a paid sale's stock back, in one transaction keyed by {@code reversalId} (order's correction id): a CANCELLATION returns every piece to its
     * lot, on sale again, and every box to its lot (and to the product's set-aside when it still stands); a RETURN puts each piece back into its lot
     * and holds it there, not for sale, until it is assessed. A repeat of the same request answers it ({@code replay: true}) and moves nothing more.
     * What each refusal means to order is {@code CorrectionReplies}'s to say: only some of them prove that nothing moved.
     */
    @PostMapping("/api/v1/admin/inventory/sale-reversals")
    Reversal reverseSale(@RequestBody ReversalAsk body);

    /**
     * Whether inventory holds this reversal. It takes the reversal's own lock before it looks, so it waits out one that is being written; 404
     * {@code reversal-not-found} is the ONLY answer that says nothing moved.
     */
    @GetMapping("/api/v1/admin/inventory/sale-reversals/{reversalId}")
    Reversal reversal(@PathVariable("reversalId") UUID reversalId);

    /**
     * What became of a piece that came back: {@code RELEASED} (fine: on sale again), {@code WRITTEN_OFF} (not sellable) or {@code RELISTED} (moved
     * to its own marked-down listing, {@code childVariantId}). Keyed by the piece: the same outcome again is answered once.
     */
    @PostMapping("/api/v1/admin/inventory/returned-pieces/{pieceId}/outcome")
    PieceDecision decidePiece(@PathVariable("pieceId") UUID pieceId, @RequestBody PieceOutcomeAsk body);

    /**
     * @param reservationId a cancellation only: the hold the sale consumed
     * @param goods         a cancellation only: every sale line, whole
     * @param boxes         a cancellation only: the box sale that stands on each line (after any swap)
     * @param pieces        a return only: one entry per piece that came back, each with the id order minted for it
     */
    record ReversalAsk(UUID reversalId, String kind, UUID orderId, UUID reservationId, Instant occurredAt, List<GoodsAsk> goods, List<BoxAsk> boxes,
                       List<PieceAsk> pieces) {
    }

    record GoodsAsk(UUID saleLineId, UUID variantId, int quantity) {
    }

    record BoxAsk(UUID boxLineId, UUID boxVariantId, UUID productId, int quantity) {
    }

    record PieceAsk(UUID pieceId, UUID saleLineId, UUID variantId) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Reversal(UUID reversalId, String kind, UUID orderId, Instant occurredAt, Instant recordedAt, boolean replay) {
    }

    record PieceOutcomeAsk(String outcome, UUID childVariantId, Instant occurredAt) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PieceDecision(UUID pieceId, String outcome, UUID variantId, UUID childVariantId, Instant occurredAt, boolean replay) {
    }

    /**
     * @param holdSeconds how long to hold; null takes inventory's default (ten minutes), which is inventory's to know
     */
    record ReserveAsk(UUID reservationId, UUID orderId, List<LineAsk> lines, Long holdSeconds) {
    }

    /**
     * One piece the shopper is buying. {@code saleLineId} is the ORDER line's own id (the id the line is sold under), {@code boxLineId} the id
     * of the box that goes with it; the box is the product's default unless {@code boxVariantId} names another.
     */
    record LineAsk(UUID saleLineId, UUID variantId, UUID productId, int quantity, UUID boxLineId, UUID boxVariantId) {
    }

    /** A hold as inventory reports it. Unknown fields are ignored so a field added there cannot break a placement. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Reservation(UUID reservationId, UUID orderId, String status, Instant heldAt, Instant expiresAt, Instant endedAt, List<HeldLine> lines) {
    }

    /** {@code kind} is GOODS or BOX; a BOX line's {@code saleLineId} is the order line's {@code boxLineId} and its {@code variantId} the box. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record HeldLine(UUID saleLineId, UUID variantId, String kind, UUID productId, int quantity, int fromCommitted, int fromFree) {
    }
}
