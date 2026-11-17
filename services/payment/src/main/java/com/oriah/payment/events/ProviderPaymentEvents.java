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
 * Writes where an attempt to collect through ZynlePay stands to payment's outbox, on {@value PaymentEvents#TOPIC}, in the transaction that
 * changed it: the fact and the change commit together or not at all.
 */
@Component
public class ProviderPaymentEvents {

    static final String AGGREGATE = "provider_payment";
    static final int SCHEMA_VERSION = 1;

    private final OutboxWriter outbox;

    public ProviderPaymentEvents(OutboxWriter outbox) {
        this.outbox = outbox;
    }

    /** @param status FAILED, UNRESOLVED or VERIFIED: the states others act on */
    @Transactional(propagation = Propagation.MANDATORY)
    public void updated(UUID providerPaymentId, UUID orderId, String keycloakId, String channel, String method, String status, String failureCode,
                        String unresolvedReason, BigDecimal amount, String currency, UUID confirmationId, Instant startedAt, Instant at, String actor) {
        outbox.append(new EventDraft(AGGREGATE, providerPaymentId.toString(), ProviderPaymentUpdatedEvent.TYPE, SCHEMA_VERSION, PaymentEvents.TOPIC, at, actor,
                new ProviderPaymentUpdatedEvent(providerPaymentId, orderId, keycloakId, channel, method, status, failureCode, unresolvedReason, amount, currency,
                        confirmationId, startedAt, at)));
    }
}
