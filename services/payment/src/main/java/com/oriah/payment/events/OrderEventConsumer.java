package com.oriah.payment.events;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import java.util.function.Consumer;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oriah.events.EventHeaders;
import com.oriah.events.Inbox;
import com.oriah.events.InboxConsumer;
import com.oriah.payment.claim.ClaimService;
import com.oriah.payment.confirmation.SettlementOutcomes;

import lombok.extern.slf4j.Slf4j;

/**
 * Payment learns what became of confirmed money from order's events (Phase 7); it never reads an order.
 *
 * <ul>
 *   <li>{@code order.payment_submitted}: a shopper's claim to have paid ({@code claimId}, method, reference), which a rejection is bound to;
 *   <li>{@code order.paid}, version 2: the order was paid by the confirmation it names ({@code confirmationId}): that money SETTLED. Version 1
 *       (before confirmations) names none and is read and ignored;
 *   <li>{@code order.payment_unsettled}: the money could not pay the sale ({@code reason}): it is HELD for the customer.
 * </ul>
 *
 * <p>These are facts, not snapshots, so each is applied once, in any order ({@link Inbox#once}), and what they change is itself idempotent. A broken
 * body (a missing id, a method that is cash, a reason nobody knows) is an {@link IllegalArgumentException}: parked on the dead-letter topic on its
 * first delivery, never retried. Every other order event type is declared and ignored, so a replay of the order log never stalls on one payment has
 * no use for; a type nobody declared is refused so a new one is noticed. Order may add types: declare them here first.
 */
@Configuration
@Slf4j
public class OrderEventConsumer {

    static final String ORDER = "order";
    static final String SUBMITTED = "order.payment_submitted";
    static final String PAID = "order.paid";
    static final String UNSETTLED = "order.payment_unsettled";
    static final String SALE_CANCELLED = "order.sale_cancelled";
    static final String RETURN_RECORDED = "order.return_recorded";

    /** The one declaration of payment as a consumer of the event log. */
    static final InboxConsumer PAYMENT = InboxConsumer.named("payment")
            .handles(ORDER, SUBMITTED, 1)
            .handles(ORDER, PAID, 1, 2)
            .handles(ORDER, UNSETTLED, 1)
            // Phase 12: a paid order cancelled before it left, and pieces that came back. Each says what is owed; payment keeps the debt.
            .handles(ORDER, SALE_CANCELLED, 1)
            .handles(ORDER, RETURN_RECORDED, 1)
            .ignores("order.created", "order.expired", "order.cancelled", "order.fulfilled", "order.shipped", "order.delivered");

    @Bean
    public Consumer<Message<byte[]>> orderEvents(Inbox inbox, ClaimService claims, SettlementOutcomes outcomes, com.oriah.payment.refund.RefundDues refunds,
                                                 ObjectMapper objectMapper) {
        return new Handler(inbox, claims, outcomes, refunds, objectMapper);
    }

    /** Inner handler, so {@code @Transactional} goes through the proxy (a {@code @Bean} method on this class does not). */
    static class Handler implements Consumer<Message<byte[]>> {
        private final Inbox inbox;
        private final ClaimService claims;
        private final SettlementOutcomes outcomes;
        private final com.oriah.payment.refund.RefundDues refunds;
        private final ObjectMapper objectMapper;

        Handler(Inbox inbox, ClaimService claims, SettlementOutcomes outcomes, com.oriah.payment.refund.RefundDues refunds, ObjectMapper objectMapper) {
            this.refunds = refunds;
            this.inbox = inbox;
            this.claims = claims;
            this.outcomes = outcomes;
            this.objectMapper = objectMapper;
        }

        @Override
        @Transactional
        public void accept(Message<byte[]> message) {
            inbox.once(PAYMENT, message.getHeaders(), () -> apply(message));
        }

        /** Runs only for a handled type the Inbox has not seen before. */
        private void apply(Message<byte[]> message) {
            String type = header(message, EventHeaders.EVENT_TYPE);
            int version = Integer.parseInt(header(message, EventHeaders.SCHEMA_VERSION));
            JsonNode body = read(message);
            switch (type) {
                case SUBMITTED -> claims.record(id(body, "claimId"), id(body, "orderId"), text(body, "method"), text(body, "referenceNormalised"),
                        instant(body, "submittedAt", true), instant(body, "holdExpiresAt", false));
                case PAID -> {
                    if (version >= 2) {
                        outcomes.settled(id(body, "confirmationId"));
                    } else {
                        log.info("order.paid version {} for order {} names no confirmation: nothing to settle", version, header(message, EventHeaders.AGGREGATE_ID));
                    }
                }
                case UNSETTLED -> outcomes.unsettled(id(body, "confirmationId"), text(body, "reason"));
                case SALE_CANCELLED, RETURN_RECORDED -> {
                    java.util.UUID order = id(body, "orderId");
                    if (!order.toString().equals(header(message, EventHeaders.AGGREGATE_ID))) {
                        throw new IllegalArgumentException("a sale correction names order " + order + " but arrived in an envelope for order "
                                + header(message, EventHeaders.AGGREGATE_ID));
                    }
                    // Only ids, a code, an amount and a date are read: who the customer is, the receipt number and the reason are left alone.
                    refunds.owe(id(body, "correctionId"), SALE_CANCELLED.equals(type) ? "CANCELLATION" : "RETURN", order, id(body, "confirmationId"),
                            amount(body, "owedGross"), text(body, "currency"), text(body, "feeBearer"), instant(body, "occurredAt", true),
                            id(message, EventHeaders.EVENT_ID), header(message, EventHeaders.ACTOR));
                }
                default -> throw new IllegalStateException("declared as handled but not applied: " + type);
            }
        }

        private JsonNode read(Message<byte[]> message) {
            try {
                return objectMapper.readTree(message.getPayload());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        private static String header(Message<byte[]> message, String name) {
            Object value = message.getHeaders().get(name);
            return value == null ? null : value.toString();
        }

        private static String text(JsonNode body, String field) {
            JsonNode node = body.get(field);
            if (node == null || node.isNull() || node.asText().isBlank()) {
                throw new IllegalArgumentException("the event has no " + field);
            }
            return node.asText();
        }

        private static UUID id(Message<byte[]> message, String name) {
            String value = header(message, name);
            try {
                return UUID.fromString(value);
            } catch (IllegalArgumentException | NullPointerException e) {
                throw new IllegalArgumentException("the event's " + name + " header '" + value + "' is not an id", e);
            }
        }

        private static java.math.BigDecimal amount(JsonNode body, String field) {
            JsonNode node = body.get(field);
            if (node == null || !node.isNumber()) {
                throw new IllegalArgumentException("the event has no amount in " + field);
            }
            return node.decimalValue();
        }

        private static UUID id(JsonNode body, String field) {
            String text = text(body, field);
            try {
                return UUID.fromString(text);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(field + " '" + text + "' is not an id", e);
            }
        }

        private static Instant instant(JsonNode body, String field, boolean required) {
            JsonNode node = body.get(field);
            if (node == null || node.isNull() || node.asText().isBlank()) {
                if (required) {
                    throw new IllegalArgumentException("the event has no " + field);
                }
                return null;
            }
            try {
                return Instant.parse(node.asText());
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException(field + " '" + node.asText() + "' is not a time", e);
            }
        }
    }
}
