package com.oriah.notification.model;

/**
 * Which message to a customer an order event is. One event type can be two different messages: "fulfilled" is "ready to collect" for an order the
 * customer fetches and "packed" for one that is delivered; "expired" reads differently when the customer had told us they paid.
 *
 * <p>The names are also the values of {@code oriah.notification.email.emailed}: the kinds that are emailed as well as shown on the website.
 */
public enum MessageKind {

    /** order.created: the pieces are held, nothing is paid. */
    PLACED,
    /** order.paid. */
    PAID,
    /** order.fulfilled of an order the customer collects. */
    READY_TO_COLLECT,
    /** order.fulfilled of an order that is delivered. */
    PACKED,
    /** order.shipped. */
    ON_ITS_WAY,
    /** order.delivered of an order that was delivered. */
    DELIVERED,
    /** order.delivered of an order the customer collected. */
    COLLECTED,
    /** order.cancelled. */
    CANCELLED,
    /** order.expired with no payment claimed: it was simply not paid in time. */
    EXPIRED_UNPAID,
    /** order.expired after the customer said they had paid: the claim was rejected, time ran out, or the money came too late to pay it. */
    PAYMENT_NOT_CONFIRMED,
    /** order.sale_cancelled (Phase 12): a PAID order cancelled before it left the shop; money is owed back. */
    PAID_ORDER_CANCELLED,
    /** order.return_recorded (Phase 12): pieces came back after the parcel had gone. */
    RETURN_RECEIVED,
    /** payment.paid_out for a refund (Phase 12): the money has gone back to the customer. */
    REFUND_PAID;

    /**
     * The three kinds about money going back. Their words carry figures of the correction or the payout (what is owed, what was withheld), which
     * an order's snapshot does not have, so they are written by their own methods of {@code MessageTexts}, never from a snapshot.
     */
    public boolean aboutARefund() {
        return this == PAID_ORDER_CANCELLED || this == RETURN_RECEIVED || this == REFUND_PAID;
    }

    private static final String COLLECTED_BY_THE_CUSTOMER = "SELF_COLLECT";
    private static final String A_PAYMENT_WAS_CLAIMED = "PAYMENT_SUBMITTED";

    /**
     * @param deliveryOption how the order reaches the customer; null on an old order, which gets the delivery wording (never a guess that it was
     *                       collected)
     * @param previousStatus the status the order left; null on order.created
     * @throws IllegalArgumentException for an event type that has no message: a type the consumer declared without giving it words
     */
    public static MessageKind of(String eventType, String deliveryOption, String previousStatus) {
        boolean collects = COLLECTED_BY_THE_CUSTOMER.equals(deliveryOption);
        return switch (eventType) {
            case "order.created" -> PLACED;
            case "order.paid" -> PAID;
            case "order.fulfilled" -> collects ? READY_TO_COLLECT : PACKED;
            case "order.shipped" -> ON_ITS_WAY;
            case "order.delivered" -> collects ? COLLECTED : DELIVERED;
            case "order.cancelled" -> CANCELLED;
            case "order.expired" -> A_PAYMENT_WAS_CLAIMED.equals(previousStatus) ? PAYMENT_NOT_CONFIRMED : EXPIRED_UNPAID;
            default -> throw new IllegalArgumentException("there are no words for an event of type '" + eventType + "'");
        };
    }
}
