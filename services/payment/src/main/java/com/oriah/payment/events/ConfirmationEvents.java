package com.oriah.payment.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.oriah.events.EventDraft;
import com.oriah.events.OutboxWriter;

/**
 * Writes the facts of staff-confirmed money to payment's outbox (shared/oriah-events), on {@value PaymentEvents#TOPIC}, in the transaction that
 * records them: the fact and the change commit together or not at all.
 *
 * <p>A confirmation is announced under the aggregate {@value #CONFIRMATION} (its id) with {@code payment.confirmed}, {@code payment.voided} and
 * {@code payment.held}; a rejection under {@value #CLAIM} (the claim's id). Each event is occurred-at the moment the fact is TRUE: a confirmation
 * when the money arrived (not when it was keyed), a rejection and a void when they happened.
 */
@Component
public class ConfirmationEvents {

    public static final String CONFIRMATION = "payment_confirmation";
    public static final String CLAIM = "payment_claim";
    static final int SCHEMA_VERSION = 1;

    private final OutboxWriter outbox;

    public ConfirmationEvents(OutboxWriter outbox) {
        this.outbox = outbox;
    }

    /** @param attempt which announcement of this confirmation this is: 1 when it is first recorded, then one more each time it is announced again */
    @Transactional(propagation = Propagation.MANDATORY)
    public void confirmed(UUID confirmationId, UUID orderId, String method, String reference, BigDecimal gross, BigDecimal fee, BigDecimal net,
                          String currency, Instant arrivedAt, String backdateReason, String confirmedBy, Instant confirmedAt, int attempt, String actor) {
        append(CONFIRMATION, confirmationId, PaymentConfirmedEvent.TYPE, arrivedAt, actor,
                new PaymentConfirmedEvent(confirmationId, orderId, method, reference, gross, fee, net, currency, arrivedAt, backdateReason, confirmedBy,
                        confirmedAt, attempt));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void voided(UUID confirmationId, UUID orderId, String method, String reference, BigDecimal gross, BigDecimal fee, BigDecimal net, String currency,
                       String outcome, String reason, String voidedBy, Instant voidedAt, Instant arrivedAt) {
        append(CONFIRMATION, confirmationId, PaymentVoidedEvent.TYPE, voidedAt, voidedBy,
                new PaymentVoidedEvent(confirmationId, orderId, method, reference, gross, fee, net, currency, outcome, reason, voidedBy, voidedAt, arrivedAt));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void held(UUID confirmationId, UUID orderId, String reason, BigDecimal owedGross, String currency, Instant heldAt, Instant arrivedAt, String actor) {
        append(CONFIRMATION, confirmationId, PaymentHeldEvent.TYPE, heldAt, actor,
                new PaymentHeldEvent(confirmationId, orderId, reason, owedGross, currency, heldAt, arrivedAt));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void rejected(UUID claimId, UUID orderId, String method, String reference, String proofType, String rejectedBy, Instant rejectedAt) {
        append(CLAIM, claimId, PaymentRejectedEvent.TYPE, rejectedAt, rejectedBy,
                new PaymentRejectedEvent(claimId, orderId, method, reference, proofType, rejectedBy, rejectedAt));
    }

    private void append(String aggregate, UUID id, String type, Instant occurredAt, String actor, Object payload) {
        outbox.append(new EventDraft(aggregate, id.toString(), type, SCHEMA_VERSION, PaymentEvents.TOPIC, occurredAt, actor, payload));
    }
}
