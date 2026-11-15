package com.oriah.payment.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * {@code payment.held}: money that came in could not pay a sale and is owed back to the customer (WRONG_AMOUNT, ORDER_CANCELLED, PIECE_SOLD,
 * ALREADY_PAID or ORDER_UNKNOWN), never revenue. Aggregate {@code payment_confirmation}, topic {@code oriah.payment}, occurred when payment
 * learned it. The fee posts once, from the confirmation: this event posts none.
 *
 * <p>{@code arrivedAt} (last, Phase 10) is the day the money ARRIVED, which is the day it is owed from: money that came in at 23:59 on the 31st and was found
 * unable to pay a sale at 00:02 on the 1st is a March liability. Absent on an event written before the field existed.
 */
public record PaymentHeldEvent(
        UUID confirmationId,
        UUID orderId,
        String reason,
        BigDecimal owedGross,
        String currency,
        Instant heldAt,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        Instant arrivedAt) {

    public static final String TYPE = "payment.held";
}
