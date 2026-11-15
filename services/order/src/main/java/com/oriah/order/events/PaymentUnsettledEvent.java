package com.oriah.order.events;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * {@code order.payment_unsettled}, schema version 1: confirmed money could not pay the sale, so payment HOLDS it for the customer. Aggregate
 * {@code order} (the order id the money named, whether or not such an order exists), topic {@code oriah.order}. A fact, not a snapshot: payment reads
 * exactly these four fields.
 *
 * @param reason WRONG_AMOUNT, ORDER_UNKNOWN, ORDER_CANCELLED, ALREADY_PAID, PIECE_SOLD or WRONG_DATE (a paper sale paid on another day than it happened; a closed list, payment refuses any other)
 */
@JsonPropertyOrder({"confirmationId", "orderId", "reason", "currency"})
public record PaymentUnsettledEvent(UUID confirmationId, UUID orderId, String reason, String currency) {

    public static final String TYPE = "order.payment_unsettled";
    public static final int SCHEMA_VERSION = 1;
}
