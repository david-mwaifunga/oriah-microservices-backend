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
    /** Money that went out to a customer, and a debt to one, exactly as payment writes their aggregate types (Phase 12). */
    static final String MONEY_OUT = "money_out";
    static final String REFUND_DUE = "refund_due";
    static final String PROVIDER_PAYMENT = "provider_payment";

    /**
     * The same consumer name as for order's events: one service, one name in {@code processed_event}. A rejected claim is about a claim, not
     * about money (order ends the order and says so on its own topic); the card path's two types appear only if the card path is switched on.
     */
    static final InboxConsumer NOTIFICATION = InboxConsumer.named("notification")
            .handles(CONFIRMATION, "payment.confirmed", 1)
            .handles(CONFIRMATION, "payment.voided", 1)
            .handles(CONFIRMATION, "payment.held", 1)
            // Phase 12. Money that went out and a payout cancelled are about a payout; a debt closed is about the debt. What payment says is
            // owed is declared and ignored: notification acts on order's own fact of the correction.
            .handles(MONEY_OUT, "payment.paid_out", 1)
            .handles(MONEY_OUT, "payment.payout_voided", 1)
            .handles(REFUND_DUE, "payment.refund_closed", 1)
            .ignores("payment.rejected", "payment.succeeded", "payment.failed", "payment.refund_due_recorded")
            // Phase 13, declared BEFORE payment emits it: where an attempt to collect through ZynlePay stands. Staff are told of one ZynlePay did
            // not settle (ProviderPaymentWatch).
            .handles(PROVIDER_PAYMENT, "payment.provider_payment_updated", 1);

    @Bean
    public Consumer<Message<byte[]>> paymentEvents(Inbox inbox, ConfirmationWatch watch, com.oriah.notification.alerts.RefundWatch refunds, Clock clock,
                                                   ObjectMapper objectMapper, com.oriah.notification.alerts.ProviderPaymentWatch providerPayments) {
        return new Handler(inbox, watch, refunds, clock, objectMapper, providerPayments);
    }

    static class Handler implements Consumer<Message<byte[]>> {
        private final Inbox inbox;
        private final ConfirmationWatch watch;
        private final com.oriah.notification.alerts.RefundWatch refunds;
        private final Clock clock;
        private final ObjectMapper objectMapper;

        private final com.oriah.notification.alerts.ProviderPaymentWatch providerPayments;

        Handler(Inbox inbox, ConfirmationWatch watch, com.oriah.notification.alerts.RefundWatch refunds, Clock clock, ObjectMapper objectMapper,
                com.oriah.notification.alerts.ProviderPaymentWatch providerPayments) {
            this.providerPayments = providerPayments;
            this.refunds = refunds;
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
                case "payment.paid_out" -> {
                    PaidOut fact = read(message.getPayload(), PaidOut.class);
                    requireAboutTheEnvelopesPayout(facts, fact.payoutId(), fact.debtId());
                    if ("REFUND".equals(fact.purpose())) {
                        if (fact.gross() == null || fact.customerFee() == null || fact.net() == null || fact.currency() == null) {
                            throw new IllegalArgumentException("a refund paid out (payout " + fact.payoutId() + ") does not say how much");
                        }
                        refunds.paidOut(fact.payoutId(), fact.debtId(), fact.gross(), fact.customerFee(), fact.net(), fact.currency(), facts.eventId(),
                                facts.recordedAt(), clock.instant());
                    }
                    // Held money returned tells nobody: the order named on held money was typed by a person, and staff told the customer themselves.
                }
                case "payment.payout_voided" -> {
                    PayoutVoided fact = read(message.getPayload(), PayoutVoided.class);
                    requireAboutTheEnvelopesPayout(facts, fact.payoutId(), fact.debtId());
                    if ("REFUND".equals(fact.purpose())) {
                        refunds.payoutVoided(fact.payoutId(), fact.debtId(), clock.instant());
                    }
                }
                case "payment.refund_closed" -> {
                    RefundClosed fact = read(message.getPayload(), RefundClosed.class);
                    if (fact.dueId() == null || !fact.dueId().equals(facts.aggregateId())) {
                        throw new IllegalArgumentException("a closed refund names debt " + fact.dueId() + " but arrived in an envelope for " + facts.aggregateId());
                    }
                    refunds.closed(fact.dueId(), facts.recordedAt(), clock.instant());
                }
                case "payment.provider_payment_updated" -> {
                    ProviderPaymentUpdated fact = read(message.getPayload(), ProviderPaymentUpdated.class);
                    if (fact.providerPaymentId() == null || !fact.providerPaymentId().equals(facts.aggregateId()) || fact.orderId() == null
                            || fact.status() == null) {
                        throw new IllegalArgumentException("a provider payment update names payment " + fact.providerPaymentId() + " but arrived in an "
                                + "envelope for " + facts.aggregateId());
                    }
                    providerPayments.updated(fact.providerPaymentId(), fact.orderId(), fact.method(), fact.status(), fact.unresolvedReason(), fact.amount(),
                            fact.currency(), facts.recordedAt(), clock.instant());
                }
                default -> throw new IllegalArgumentException("there is nothing to do for a declared payment event of type '" + facts.eventType() + "'");
            }
            log.info("{} for {} noted", facts.eventType(), facts.aggregateId());
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

        /** A payout's event is about the payout its envelope names (never about the envelope's confirmation: its aggregate is the payout). */
        private static void requireAboutTheEnvelopesPayout(EnvelopeFacts facts, UUID payoutId, UUID debtId) {
            if (payoutId == null || !payoutId.equals(facts.aggregateId())) {
                throw new IllegalArgumentException("a payout event names payout " + payoutId + " but arrived in an envelope for " + facts.aggregateId()
                        + "; refused, so nobody is told about another payout");
            }
            if (debtId == null) {
                throw new IllegalArgumentException("a payout event for payout " + payoutId + " names no debt");
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

    /**
     * {@code payment.paid_out} (Phase 12): money went out to a customer. {@code purpose} is REFUND or HELD_MONEY; {@code debtId} is the
     * correction's id for a refund. The reference, the payee and who paid are not declared.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record PaidOut(UUID payoutId, String purpose, UUID debtId, BigDecimal gross, BigDecimal customerFee, BigDecimal net, String currency) {
    }

    /** {@code payment.payout_voided} (Phase 12): a payout was cancelled in payment. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record PayoutVoided(UUID payoutId, String purpose, UUID debtId) {
    }

    /** {@code payment.refund_closed} (Phase 12): a debt closed without a payout, because the money never arrived. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record RefundClosed(UUID dueId) {
    }

    /**
     * {@code payment.provider_payment_updated} (Phase 13): where an attempt to collect through ZynlePay stands. It carries no number, no
     * reference and nothing a person typed, so none is declared.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ProviderPaymentUpdated(UUID providerPaymentId, UUID orderId, String method, String status, String unresolvedReason, BigDecimal amount,
                                  String currency) {
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
