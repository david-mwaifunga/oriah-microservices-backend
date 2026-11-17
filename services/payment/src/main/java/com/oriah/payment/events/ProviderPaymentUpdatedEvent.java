package com.oriah.payment.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * {@code payment.provider_payment_updated}: an attempt to collect money through ZynlePay reached a state others act on. Aggregate
 * {@code provider_payment} (the attempt id), topic {@code oriah.payment}, occurred at the moment of the change.
 *
 * <p>{@code status} is {@code FAILED} (order lets go of the attempt; the shopper is told), {@code UNRESOLVED} (ZynlePay has not given an answer
 * the system may act on by itself: staff are alerted, the owner decides) or {@code VERIFIED} (the money is recorded as a confirmation, which is
 * announced by its own {@code payment.confirmed}). An attempt can be announced more than once; a reader takes the latest.
 *
 * <p>It never carries the payer's number, a reference, a provider id, the card page's address or what the owner wrote: none of them is a member.
 * The amount is absent for an attempt refused before the order answered what is owed.
 */
public record ProviderPaymentUpdatedEvent(
        UUID providerPaymentId,
        UUID orderId,
        String keycloakId,
        String channel,
        String method,
        String status,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        String failureCode,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        String unresolvedReason,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        BigDecimal amount,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        String currency,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        UUID confirmationId,
        Instant startedAt,
        Instant at) {

    public static final String TYPE = "payment.provider_payment_updated";
}
