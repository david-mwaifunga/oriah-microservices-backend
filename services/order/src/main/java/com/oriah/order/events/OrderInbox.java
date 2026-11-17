package com.oriah.order.events;

import java.util.function.Consumer;

import org.springframework.messaging.Message;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.oriah.events.EventHeaders;
import com.oriah.events.Inbox;
import com.oriah.events.InboxConsumer;

/**
 * Order as a consumer of the event log (Phase 7). Order owns the sale, so it listens to the two services that tell it what happened to the money and to
 * the hold: payment ({@code oriah.payment}: money confirmed, voided, a claim rejected) and inventory ({@code oriah.inventory}: a hold lapsed).
 *
 * <p>ONE declaration, {@code order}, covers both topics. These are FACTS, not snapshots, so each is applied once and in any order
 * ({@link Inbox#once}); what applying one changes is itself idempotent. Every other type on those topics is declared and ignored, so a replay of
 * payment's or inventory's log never stalls on a type order has no use for; a type nobody declared is refused on its first delivery, so a new one is
 * noticed. Payment and inventory may add types: declare them here first.
 */
public final class OrderInbox {

    /** The aggregate types as the producers write them into their outboxes. */
    static final String CONFIRMATION = "payment_confirmation";
    static final String CLAIM = "payment_claim";
    static final String RESERVATION = "reservation";

    static final String CONFIRMED = "payment.confirmed";
    static final String REJECTED = "payment.rejected";
    static final String VOIDED = "payment.voided";
    static final String EXPIRED = "reservation.expired";

    /** The one declaration of order as a consumer of the event log. */
    public static final InboxConsumer ORDER = InboxConsumer.named("order")
            .handles(CONFIRMATION, CONFIRMED, 1)
            .handles(CLAIM, REJECTED, 1)
            .handles(CONFIRMATION, VOIDED, 1)
            .handles(RESERVATION, EXPIRED, 1)
            // payment's card events (until Phase 13) and held money, and inventory's sale and box swap: facts order never acts on;
            // and inventory's cost of each stock movement (Phase 10: finance's feed, declared here BEFORE inventory emits it)
            .ignores("payment.succeeded", "payment.failed", "payment.held", "reservation.consumed", "box.swapped", "stock.cost_recorded")
            // Phase 12, declared here BEFORE payment emits them: money going back out (a refund owed, a debt closed, a payout, a payout
            // cancelled). Order announces what is owed and never acts on what payment then does with it.
            .ignores("payment.refund_due_recorded", "payment.refund_closed", "payment.paid_out", "payment.payout_voided");

    private OrderInbox() {
    }

    /**
     * Applies each event once, in a transaction of its own (the Inbox records it in that same transaction), and THEN, outside it, runs {@code drive}
     * with the confirmation the event asked to be driven: inventory is asked to sell the pieces only after the money is safely recorded, and no
     * database connection is held while it answers.
     */
    public static Consumer<Message<byte[]>> handler(Inbox inbox, PlatformTransactionManager transactions, Applier applier,
                                                    java.util.function.Consumer<java.util.UUID> drive) {
        TransactionTemplate tx = new TransactionTemplate(transactions);
        return message -> {
            java.util.concurrent.atomic.AtomicReference<java.util.UUID> toDrive = new java.util.concurrent.atomic.AtomicReference<>();
            tx.executeWithoutResult(status -> inbox.once(ORDER, message.getHeaders(), () -> toDrive.set(applier.apply(
                    String.valueOf(message.getHeaders().get(EventHeaders.EVENT_TYPE)), message.getPayload()))));
            if (toDrive.get() != null) {
                drive.accept(toDrive.get());
            }
        };
    }

    /** Applies one handled event; returns the confirmation to drive once it has committed, or null. */
    @FunctionalInterface
    public interface Applier {
        java.util.UUID apply(String type, byte[] payload);
    }
}
