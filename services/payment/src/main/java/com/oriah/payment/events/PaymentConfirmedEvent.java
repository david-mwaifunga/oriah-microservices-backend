package com.oriah.payment.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * {@code payment.confirmed}: staff recorded money that reached Oriah for an order. Aggregate {@code payment_confirmation} (the confirmation id),
 * topic {@code oriah.payment}, occurred when the money ARRIVED (the true date; {@code confirmedAt} is when it was keyed). Order reads it and
 * decides: the money pays the sale, or it is held for the customer. {@code net} is gross less fee, worked out by payment and never accepted.
 *
 * <p>{@code attempt} says which announcement of this confirmation this is: a confirmation that has no outcome yet is announced again (the same
 * confirmation id, a new event) until order answers, and order treats a confirmation id it has already seen as a repeat.
 */
public record PaymentConfirmedEvent(
        UUID confirmationId,
        UUID orderId,
        String method,
        String reference,
        BigDecimal gross,
        BigDecimal fee,
        BigDecimal net,
        String currency,
        Instant arrivedAt,
        String backdateReason,
        String confirmedBy,
        Instant confirmedAt,
        int attempt) {

    public static final String TYPE = "payment.confirmed";
}
