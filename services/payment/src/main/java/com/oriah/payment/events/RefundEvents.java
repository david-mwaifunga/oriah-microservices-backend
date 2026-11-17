package com.oriah.payment.events;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.oriah.events.EventDraft;
import com.oriah.events.OutboxWriter;

/**
 * Announces what payment owes and pays back (Phase 12), in the transaction that recorded it: a debt, a debt closed, a payout, a payout cancelled. Two aggregates: the
 * debt ({@code refund_due}) and the payout ({@code money_out}). Order, finance and notification each declare these types before payment emits them.
 */
@Component
public class RefundEvents {

    public static final String REFUND_DUE = "refund_due";
    public static final String MONEY_OUT = "money_out";
    static final int SCHEMA_VERSION = 1;

    private final OutboxWriter outbox;

    public RefundEvents(OutboxWriter outbox) {
        this.outbox = outbox;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void dueRecorded(RefundDueRecordedEvent event, String actor) {
        append(REFUND_DUE, event.dueId(), RefundDueRecordedEvent.TYPE, event.recordedAt(), actor, event);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void closed(RefundClosedEvent event, String actor) {
        append(REFUND_DUE, event.dueId(), RefundClosedEvent.TYPE, event.closedAt(), actor, event);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void paidOut(PaidOutEvent event, String actor) {
        append(MONEY_OUT, event.payoutId(), PaidOutEvent.TYPE, event.paidAt(), actor, event);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void payoutVoided(PayoutVoidedEvent event, String actor) {
        append(MONEY_OUT, event.payoutId(), PayoutVoidedEvent.TYPE, event.voidedAt(), actor, event);
    }

    private void append(String aggregate, UUID id, String type, Instant occurredAt, String actor, Object payload) {
        outbox.append(new EventDraft(aggregate, id.toString(), type, SCHEMA_VERSION, PaymentEvents.TOPIC, occurredAt, actor, payload));
    }
}
