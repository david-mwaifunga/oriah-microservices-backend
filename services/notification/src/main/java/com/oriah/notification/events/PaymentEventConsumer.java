package com.oriah.notification.events;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.UUID;
import java.util.function.Consumer;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oriah.events.Inbox;
import com.oriah.events.InboxConsumer;
import com.oriah.notification.alerts.ConfirmationWatch;

/**
 * Subscribes to the {@code oriah.payment} Kafka topic (Phase 11): what staff did with money, as payment announces it.
 *
 * <p>Notification reads it for ONE reason: to tell staff when recorded money has not completed its sale. Payment announces a confirmation and
 * announces it again until order answers; a second announcement with no answer known is the alarm. The answer travels on order's topic
 * ({@code order.paid}, {@code order.payment_unsettled}), so both consumers note their facts on one row per confirmation, which each locks first
 * ({@link ConfirmationWatch}).
 *
 * <p>Like the order consumer it is a fact consumer on the Inbox ({@code once}), under the same consumer name: the "applied" marker and whatever
 * the fact changes commit together, a redelivery changes nothing, and an event that cannot be applied goes to
 * {@code oriah.payment.dlq.notification}.
 *
 * <p><b>Nothing personal is read.</b> The records below declare only what an alert uses: ids, the method, the amount, the announcement
 * number, a reason CODE. The payment reference, who confirmed or cancelled it, and any reason a person typed are not declared, so they never
 * reach a row or a log line here.
 *
 * <p>Declare a new payment event type HERE (handled or ignored) before payment emits it, or it is dead-lettered on its first delivery.
 */
@Configuration
@lombok.extern.slf4j.Slf4j
public class PaymentEventConsumer {

    /** What a staff-confirmed payment's events are about, exactly as payment writes the aggregate-type header. */
    static final String CONFIRMATION = "payment_confirmation";

    /**
     * The same consumer name as for order's events: one service, one name in {@code processed_event}. A rejected claim is about a claim, not
     * about money (order ends the order and says so on its own topic); the card path's two types appear only if the card path is switched on.
     */
    static final InboxConsumer NOTIFICATION = InboxConsumer.named("notification")
            .handles(CONFIRMATION, "payment.confirmed", 1)
            .handles(CONFIRMATION, "payment.voided", 1)
            .handles(CONFIRMATION, "payment.held", 1)
            .ignores("payment.rejected", "payment.succeeded", "payment.failed");

    @Bean
    public Consumer<Message<byte[]>> paymentEvents(Inbox inbox, ConfirmationWatch watch, Clock clock, ObjectMapper objectMapper) {
        return new Handler(inbox, watch, clock, objectMapper);
    }

    static class Handler implements Consumer<Message<byte[]>> {
        private final Inbox inbox;
        private final ConfirmationWatch watch;
        private final Clock clock;
        private final ObjectMapper objectMapper;

        Handler(Inbox inbox, ConfirmationWatch watch, Clock clock, ObjectMapper objectMapper) {
            this.inbox = inbox;
            this.watch = watch;
            this.clock = clock;
            this.objectMapper = objectMapper;
        }

        @Override
        @Transactional
        public void accept(Message<byte[]> message) {
            inbox.once(NOTIFICATION, message.getHeaders(), () -> note(message));
        }

        private void note(Message<byte[]> message) {
            EnvelopeFacts facts = EnvelopeFacts.from(message);
            switch (facts.eventType()) {
                case "payment.confirmed" -> {
                    Confirmed fact = read(message.getPayload(), Confirmed.class);
                    requireAboutTheEnvelopesConfirmation(facts, fact.confirmationId(), fact.orderId());
                    watch.confirmed(fact.confirmationId(), fact.orderId(), fact.method(), fact.gross(), fact.currency(), fact.attempt(),
                            facts.recordedAt(), clock.instant());
                }
                case "payment.voided" -> {
                    Voided fact = read(message.getPayload(), Voided.class);
                    requireAboutTheEnvelopesConfirmation(facts, fact.confirmationId(), fact.orderId());
                    watch.voided(fact.confirmationId(), fact.orderId(), fact.method(), fact.gross(), fact.currency(), facts.recordedAt(),
                            clock.instant());
                }
                case "payment.held" -> {
                    Held fact = read(message.getPayload(), Held.class);
                    requireAboutTheEnvelopesConfirmation(facts, fact.confirmationId(), fact.orderId());
                    watch.unsettled(fact.confirmationId(), fact.orderId(), fact.reason(), facts.recordedAt(), clock.instant());
                }
                default -> throw new IllegalArgumentException("there is nothing to do for a declared payment event of type '" + facts.eventType() + "'");
            }
            log.info("{} for confirmation {} noted", facts.eventType(), facts.aggregateId());
        }

        private static void requireAboutTheEnvelopesConfirmation(EnvelopeFacts facts, UUID confirmationId, UUID orderId) {
            if (confirmationId == null || !confirmationId.equals(facts.aggregateId())) {
                throw new IllegalArgumentException("a payment event names confirmation " + confirmationId + " but arrived in an envelope for "
                        + facts.aggregateId() + "; refused, so staff are not told about another payment");
            }
            if (orderId == null) {
                throw new IllegalArgumentException("a payment event for confirmation " + confirmationId + " names no order");
            }
        }

        private <T> T read(byte[] body, Class<T> as) {
            try {
                return objectMapper.readValue(body, as);
            } catch (IOException e) {
                throw new UncheckedIOException("payment event body does not match the contract", e);
            }
        }
    }

    /** {@code payment.confirmed}: staff recorded money for an order. {@code attempt} counts payment's announcements of it: 1, then 2, 3 ... */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Confirmed(UUID confirmationId, UUID orderId, String method, BigDecimal gross, String currency, int attempt) {
    }

    /** {@code payment.voided}: staff cancelled a payment record. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Voided(UUID confirmationId, UUID orderId, String method, BigDecimal gross, String currency) {
    }

    /** {@code payment.held}: the money could not pay the sale and is kept for the customer. {@code reason} is a code, never typed text. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Held(UUID confirmationId, UUID orderId, String reason) {
    }
}
