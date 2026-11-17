package com.oriah.order.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * What order reads from the events of other services (Phase 7). Each is a FACT, read for the fields order uses; fields it does not use are ignored, so
 * a field added by the producer cannot break the order. A body that cannot be read, or lacks a field order cannot do without, is a broken event: an
 * {@link IllegalArgumentException}, which the Inbox parks on the dead-letter topic on its first delivery rather than retrying something that can never
 * work.
 */
public final class InboundEvents {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private InboundEvents() {
    }

    /** {@code payment.confirmed}: staff recorded money that reached Oriah for an order. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PaymentConfirmed(UUID confirmationId, UUID orderId, String method, String reference, BigDecimal gross, BigDecimal fee, BigDecimal net,
                                   String currency, Instant arrivedAt, String confirmedBy, UUID providerPaymentId) {
        public PaymentConfirmed {
            need(confirmationId, "confirmationId");
            need(orderId, "orderId");
            need(method, "method");
            need(gross, "gross");
            need(fee, "fee");
            need(net, "net");
            need(currency, "currency");
            need(arrivedAt, "arrivedAt");
            if (gross.signum() <= 0) {
                throw new IllegalArgumentException("gross must be above zero, was " + gross);
            }
        }
    }

    /**
     * {@code payment.provider_payment_updated} (Phase 13): where an attempt to collect through ZynlePay stands. Order reads which attempt, for which
     * order, and whether it FAILED: that alone lets the order go. Its money, if any, arrives as its own {@code payment.confirmed}.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProviderPaymentUpdated(UUID providerPaymentId, UUID orderId, String status) {
        public ProviderPaymentUpdated {
            need(providerPaymentId, "providerPaymentId");
            need(orderId, "orderId");
            need(status, "status");
        }
    }

    /**
     * {@code payment.paid_out} (Phase 12): money went back to a customer. Order reads only which debt it paid ({@code debtId} is the correction's id
     * for a REFUND; for HELD_MONEY it is not a correction at all) and when: never an amount, a method or a reference.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PaidOut(UUID payoutId, String purpose, UUID debtId, Instant paidAt) {
        public PaidOut {
            need(payoutId, "payoutId");
            need(purpose, "purpose");
            need(debtId, "debtId");
            need(paidAt, "paidAt");
        }

        public boolean isRefund() {
            return "REFUND".equals(purpose);
        }
    }

    /** {@code payment.payout_voided} (Phase 12): a payout was keyed by mistake and cancelled; what it paid is owed again. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PayoutVoided(UUID payoutId, String purpose, UUID debtId, Instant voidedAt) {
        public PayoutVoided {
            need(payoutId, "payoutId");
            need(purpose, "purpose");
            need(debtId, "debtId");
            need(voidedAt, "voidedAt");
        }

        public boolean isRefund() {
            return "REFUND".equals(purpose);
        }
    }

    /** {@code payment.refund_closed} (Phase 12): the owner said the money for the sale never arrived; nothing is owed. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RefundClosed(UUID dueId, Instant closedAt) {
        public RefundClosed {
            need(dueId, "dueId");
            need(closedAt, "closedAt");
        }
    }

    /** {@code payment.rejected}: staff could not find the money a shopper's claim named. Bound to the CLAIM. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PaymentRejected(UUID claimId, UUID orderId) {
        public PaymentRejected {
            need(claimId, "claimId");
            need(orderId, "orderId");
        }
    }

    /** {@code payment.voided}: staff cancelled a confirmation that had no outcome yet (or whose money was held). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PaymentVoided(UUID confirmationId, UUID orderId, String method, String reference, BigDecimal gross, BigDecimal fee, BigDecimal net,
                                String currency, Instant voidedAt) {
        public PaymentVoided {
            need(confirmationId, "confirmationId");
            need(orderId, "orderId");
            need(method, "method");
            need(gross, "gross");
            need(fee, "fee");
            need(net, "net");
            need(currency, "currency");
            need(voidedAt, "voidedAt");
            if (gross.signum() <= 0) {
                throw new IllegalArgumentException("gross must be above zero, was " + gross);
            }
        }
    }

    /** {@code reservation.expired}: inventory's sweep ended a hold whose time had passed. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ReservationExpired(UUID reservationId, UUID orderId) {
        public ReservationExpired {
            need(reservationId, "reservationId");
            need(orderId, "orderId");
        }
    }

    /** Reads {@code payload} as {@code type}; whatever is wrong with it is an {@link IllegalArgumentException}. */
    public static <T> T read(byte[] payload, Class<T> type) {
        try {
            T read = JSON.readValue(payload, type);
            if (read == null) {
                throw new IllegalArgumentException("the event has no body");
            }
            return read;
        } catch (JacksonException e) {
            throw new IllegalArgumentException("the event body cannot be read as " + type.getSimpleName() + ": " + e.getOriginalMessage(), e);
        }
    }

    private static void need(Object value, String field) {
        if (value == null || (value instanceof String text && text.isBlank())) {
            throw new IllegalArgumentException("the event has no " + field);
        }
    }
}
