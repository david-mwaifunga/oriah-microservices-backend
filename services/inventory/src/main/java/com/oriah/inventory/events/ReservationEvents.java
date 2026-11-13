package com.oriah.inventory.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What inventory says about a hold on {@code oriah.inventory} (Phase 7). One aggregate, {@code reservation}, whose history is one event:
 * a reservation ends once, either sold or lapsed. Releasing a hold is not announced (whoever released it knows). Every amount states its
 * currency.
 *
 * <p>Version 1 of each payload. Consumers: order reads {@code reservation.expired} and moves the order to EXPIRED; finance (Phase 10) reads
 * {@code reservation.consumed} for the cost of every sale.
 */
public final class ReservationEvents {

    /** The kind of thing these events are about, as written to the outbox. */
    public static final String AGGREGATE = "reservation";
    public static final String TOPIC = "oriah.inventory";
    public static final String CONSUMED = "reservation.consumed";
    public static final String EXPIRED = "reservation.expired";
    public static final String BOX_SWAPPED = "box.swapped";
    public static final int VERSION = 1;

    private ReservationEvents() {
    }

    /**
     * The reservation was sold: every line, with what it cost (the oldest lots it drained, at the cost they left at). The envelope's
     * {@code occurredAt} is the day the money arrived.
     *
     * @param currency the one currency of every {@code cost} on the event
     */
    public record Consumed(UUID reservationId, UUID orderId, String currency, List<Line> lines) {

        /**
         * One line sold: a piece or a box, under the order's own sale-line id (the stock action's id). A box line names the product it went
         * with; {@code cost} is the cost of the units drained, at the time of the sale.
         */
        public record Line(UUID saleLineId, UUID variantId, String kind, UUID productId, int quantity, BigDecimal cost) {
        }
    }

    /** The aggregate a box swap is about (the amendment the order system minted). */
    public static final String SWAP_AGGREGATE = "box_swap";

    /**
     * A box was changed after the order was paid: the original box's sale was reversed and the replacement sold, both dated to the sale (the
     * envelope's {@code occurredAt}) and recorded now ({@code recordedAt}). {@code original.cost} is what the box handed back had cost; so the
     * packaging cost of the sale's month moves by exactly {@code replacement.cost - original.cost}.
     */
    public record BoxSwapped(UUID amendmentId, UUID orderId, UUID productId, int quantity, String currency, Side original, Side replacement,
                             String reason, Instant recordedAt) {

        /** One side of a swap: the action that moved the boxes, which box, and what those boxes cost. */
        public record Side(UUID actionId, UUID variantId, BigDecimal cost) {
        }
    }

    /** The hold lapsed and was released; the order it was for can now be moved to EXPIRED. */
    public record Expired(UUID reservationId, UUID orderId, Instant heldAt, Instant expiresAt, Instant expiredAt) {
    }
}
