package com.oriah.notification.events;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Notification's own copy of what an order event says about the order
 * (topic oriah.order, aggregate (order, orderId), schema version 1): a full
 * snapshot of the order after the change the event type names.
 *
 * <p>Deliberately not shared with order: the two services meet only on the
 * topic, so the contract is the JSON, and this record is what notification
 * expects to find in it. Fields it does not declare are skipped, so order may
 * add one without breaking this service; a change that would break it gets a
 * new schema version, which notification refuses until it declares it.
 *
 * <p>The components are in the contract's property order: the six fields
 * the event has always carried, then the ones the full snapshot added.
 *
 * <p><b>Money.</b> Every amount -- total, subtotal, shippingAmount,
 * discountAmount and each line's unitPrice -- is in {@link #currency}; one
 * event never mixes currencies. Amounts arrive with their scale (150.00) and
 * stay BigDecimal, so a customer is never told 150.0. shippingAmount,
 * discountAmount and deliveryOption are null on an order placed before order's
 * V4, where they are unknown -- never a claim that they were zero.
 *
 * @param previousStatus  null on order.created: the order had no status before
 * @param paymentIntentId null until the order is paid
 * @param channel         how the order was taken (Phase 9): {@code PHONE} for an order staff recorded for a caller with no account (then
 *                        {@code keycloakId} is null and there is nobody here to tell); absent on a web order, which is how every earlier event reads
 * @param republished     only on {@code order.paid} version 2, and true only when it is the SAME answer announced again (order does that while
 *                        payment has not heard its answer, so a lost one is recovered): the customer was already told, once; absent otherwise
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderEventPayload(
        UUID orderId,
        String keycloakId,
        String previousStatus,
        String newStatus,
        BigDecimal total,
        String currency,
        BigDecimal subtotal,
        BigDecimal shippingAmount,
        BigDecimal discountAmount,
        String deliveryOption,
        UUID paymentIntentId,
        List<Line> lines,
        // Absent on every event but a repeated order.paid, and written back absent (not null) so the contract's own examples round-trip exactly.
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        Boolean republished,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        String channel
) {

    /** Whether staff recorded this order for a caller with no account. */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isPhone() {
        return "PHONE".equals(channel);
    }

    /** One line of the order: what was bought, how many, and the price of one, in the event's currency. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Line(UUID variantId, String title, int quantity, BigDecimal unitPrice) {
    }
}
