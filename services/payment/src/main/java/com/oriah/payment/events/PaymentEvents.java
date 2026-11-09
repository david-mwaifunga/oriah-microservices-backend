package com.oriah.payment.events;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.oriah.events.EventDraft;
import com.oriah.events.OutboxWriter;
import com.oriah.payment.model.PaymentIntent;

/**
 * Writes payment's facts to its outbox (shared/oriah-events). The relay
 * publishes them to {@value #TOPIC} in the order they were written.
 *
 * <p>Only charge outcomes are facts here: a new intent is a proposal and has
 * no event (MVP_PLAN Phase 1, adoption scope); the charge events carry the
 * intent's full snapshot.
 */
@Component
public class PaymentEvents {

    public static final String TOPIC = "oriah.payment";
    public static final String AGGREGATE = "payment_intent";
    public static final String SUCCEEDED = "payment.succeeded";
    public static final String FAILED = "payment.failed";
    static final int SCHEMA_VERSION = 1;

    private final OutboxWriter outbox;

    public PaymentEvents(OutboxWriter outbox) {
        this.outbox = outbox;
    }

    /**
     * Appends the outcome the intent has just been settled with, in the
     * caller's transaction -- the one that recorded it -- so the fact and the
     * change commit together or not at all. The caller flushes the intent
     * first; this is its last write.
     *
     * <p>occurred_at is the intent's confirmedAt: the gateway's decision time,
     * never the moment this row is written.
     *
     * @param actor {@code user:<subject>} for a shopper's confirm,
     *              {@code system:<name>} for the service settling on its own
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void outcomeRecorded(PaymentIntent intent, String actor) {
        switch (intent.status()) {
            case SUCCEEDED -> append(intent, SUCCEEDED, actor, PaymentSucceeded.of(intent));
            case FAILED -> append(intent, FAILED, actor, PaymentFailed.of(intent));
            default -> throw new IllegalArgumentException("Payment intent " + intent.id() + " is "
                    + intent.status() + ": only a SUCCEEDED or FAILED charge is a fact to record");
        }
    }

    private void append(PaymentIntent intent, String eventType, String actor, Object payload) {
        outbox.append(new EventDraft(AGGREGATE, intent.id().toString(), eventType, SCHEMA_VERSION, TOPIC,
                intent.confirmedAt(), actor, payload));
    }
}
