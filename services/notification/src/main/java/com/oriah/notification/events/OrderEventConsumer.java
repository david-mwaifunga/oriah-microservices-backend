package com.oriah.notification.events;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oriah.events.EventHeaders;
import com.oriah.events.Inbox;
import com.oriah.events.InboxConsumer;
import com.oriah.notification.delivery.NotificationSender;
import com.oriah.notification.model.NotificationLog;
import com.oriah.notification.repository.NotificationLogRepository;

/**
 * Subscribes to the {@code oriah.order} Kafka topic. Every state
 * change publishes one event there (CREATED, PAID, SHIPPED, etc.); we
 * translate it to a customer-facing notification, record it (a
 * notification_log row) and, once that is committed, deliver it
 * ({@link NotificationSender}: today a log line).
 *
 * <p>One consumer dispatches by the {@code event-type} message header.
 *
 * <p>group=notification ensures all notification instances share one
 * consumer group: each event is delivered to exactly one instance.
 *
 * <h2>Each event is sent once</h2>
 *
 * Kafka delivers at least once, so the same event can arrive twice. Each
 * event is one message to a person -- a fact, not a copy of the order's
 * state -- so it goes through {@link Inbox#once}: the record that it was sent
 * (processed_event) commits in the same transaction as its notification_log
 * row, and a second delivery finds the record and sends nothing. The message
 * itself goes out only after that transaction commits (see
 * {@code Handler.sendAfterCommit}), so a transaction that rolls back never
 * sends.
 *
 * <h2>Nothing is swallowed</h2>
 *
 * An event that cannot be applied -- a type or payload version this consumer
 * does not declare, an aggregate that is not an order, a missing or garbled
 * envelope header, a body that breaks the contract -- throws. The transaction
 * rolls back with its record, and the oriah-events module's error handler
 * publishes it to {@code oriah.order.dlq.notification}: on its first delivery
 * for a refusal like these, which would fail the same way again, and after
 * three deliveries in all for anything else, such as the database being away
 * (application.yaml). It commits the event only once that topic has it, so a
 * failed dead-letter publish keeps the event instead of dropping it. Logging
 * and carrying on, as this consumer once did, lost such an event for good.
 */
@Configuration
@lombok.extern.slf4j.Slf4j
public class OrderEventConsumer {

    /**
     * What every order event is about, spelled exactly as order writes it in
     * the aggregate-type header (OrderEvent.AGGREGATE_TYPE, "order", lower
     * case). The Inbox refuses an order event that says it is about anything
     * else -- "Order" included -- before anything is recorded: notification
     * reads its aggregate-id as an order's id, so it must be one.
     */
    static final String ORDER = "order";

    /**
     * Every type order publishes on oriah.order. Each status change is {@code order.<status>} with the full order snapshot ({@link OrderEventPayload})
     * as its payload and has a message to send; the two facts order announces about a sale in progress are not for the customer and are ignored.
     *
     * <ul>
     *   <li>{@code order.paid} has two versions (Phase 7): the second keeps every field of the first, in the same order, and adds what the sale was
     *       paid with. Both are read as the snapshot they start with and say the same thing to the customer.
     *   <li>{@code order.expired}: an unpaid order lapsed; its hold was let go. A plain message that reads no figure.
     *   <li>{@code order.payment_submitted} and {@code order.payment_unsettled} are FACTS for staff (a shopper's claim to have paid; money that could
     *       not settle a sale): declared and ignored, so they are marked applied and nothing is sent.
     * </ul>
     *
     * A type nobody declared (a refund, say) is refused on its first delivery so that a new one is noticed. Declare a new type HERE before order
     * publishes it.
     */
    static final InboxConsumer NOTIFICATION = InboxConsumer.named("notification")
            .handles(ORDER, "order.created", 1)
            .handles(ORDER, "order.paid", 1, 2)
            .handles(ORDER, "order.fulfilled", 1)
            .handles(ORDER, "order.shipped", 1)
            .handles(ORDER, "order.delivered", 1)
            .handles(ORDER, "order.cancelled", 1)
            .handles(ORDER, "order.expired", 1)
            .ignores("order.payment_submitted", "order.payment_unsettled");

    @Bean
    public Consumer<Message<byte[]>> orderEvents(
            Inbox inbox,
            NotificationLogRepository repository,
            NotificationSender sender,
            ObjectMapper objectMapper) {
        return new Handler(inbox, repository, sender, objectMapper);
    }

    static class Handler implements Consumer<Message<byte[]>> {
        private final Inbox inbox;
        private final NotificationLogRepository repository;
        private final NotificationSender sender;
        private final ObjectMapper objectMapper;

        Handler(Inbox inbox, NotificationLogRepository repository, NotificationSender sender,
                ObjectMapper objectMapper) {
            this.inbox = inbox;
            this.repository = repository;
            this.sender = sender;
            this.objectMapper = objectMapper;
        }

        @Override
        @Transactional
        public void accept(Message<byte[]> message) {
            inbox.once(NOTIFICATION, message.getHeaders(), () -> notify(message));
        }

        /** Runs only for a handled type the Inbox has not seen; the Inbox checked the headers. */
        private void notify(Message<byte[]> message) {
            String type = header(message, EventHeaders.EVENT_TYPE);
            OrderEventPayload payload = read(message.getPayload());
            requireAboutTheEnvelopesOrder(message, payload);

            if (Boolean.TRUE.equals(payload.republished())) {
                // The same sale announced again so payment can hear its answer: the customer was told when it was first announced. The Inbox records
                // this event as applied all the same, so it is never delivered again.
                log.info("{} for order {} is a repeat of one already announced: the customer is not told twice", type, payload.orderId());
                return;
            }
            var template = renderTemplate(type, payload);
            NotificationLog notification = repository.save(new NotificationLog(
                    payload.orderId(), payload.keycloakId(), type, "email", template.subject(), template.body()));
            sendAfterCommit(notification);
        }

        /**
         * Arranges for the notification to be sent only after this
         * transaction has committed -- when its notification_log row and the
         * Inbox's "applied" marker are saved for good. If the transaction
         * rolls back, the arrangement is dropped with it.
         *
         * <p>Why wait for the commit: a message that has gone out cannot be
         * called back. Sent inside the transaction, a commit that then failed
         * (the database went away, the transaction timed out) would leave a
         * customer with a message and nothing recorded; Kafka would deliver the
         * event again, the Inbox would find no marker, and the customer would
         * get it twice. Sent after the commit, a transaction that rolls back
         * sends nothing, and the redelivery that follows sends it once.
         *
         * <p>What this does not cover: the service dying between the commit and
         * the send. The row and the marker are saved, so a redelivery of the
         * event finds it applied and sends nothing -- that one message is lost.
         * The same if the send itself throws: the exception leaves the consumer
         * after the commit, the event is delivered again, the Inbox finds it
         * applied, and that message is lost too. Today's sender only writes a
         * log line, so this is narrow. Phase 11, which brings real email,
         * WhatsApp and SMS, adds a delivery status to each notification and a
         * sweeper that retries the ones never marked delivered.
         *
         * <p>The send runs on the listener's thread before it returns, so its
         * time counts in the poll budget (OrderEventBindingTest): a real
         * sender in Phase 11 needs a timeout of its own.
         */
        private void sendAfterCommit(NotificationLog notification) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    sender.send(notification);
                }
            });
        }

        private OrderEventPayload read(byte[] body) {
            try {
                return objectMapper.readValue(body, OrderEventPayload.class);
            } catch (IOException e) {
                throw new UncheckedIOException("order event body does not match the contract", e);
            }
        }

        /**
         * The body must be a snapshot of the order the envelope names
         * (aggregate-id). A body about another order -- or about no order at
         * all -- would send one customer news of somebody else's order, so it
         * is refused (an IllegalArgumentException, which the binding never
         * retries: it goes to the dead-letter topic on its first delivery).
         *
         * <p>This is the first thing the effect does, before anything of
         * notification's own is written or any delivery is arranged. The
         * Inbox inserted its "applied" marker a moment earlier, in this same
         * transaction, so the refusal rolls the marker back with it: nothing
         * is recorded and nothing is sent. (It runs after the Inbox's own
         * checks on purpose: an event of a type or payload version
         * notification does not read is refused for that reason, rather than
         * for a body this record cannot make sense of.) Search and inventory
         * check their product snapshots the same way.
         */
        private static void requireAboutTheEnvelopesOrder(Message<byte[]> message, OrderEventPayload payload) {
            String aggregateId = header(message, EventHeaders.AGGREGATE_ID);
            if (payload.orderId() == null || !payload.orderId().toString().equals(aggregateId)) {
                throw new IllegalArgumentException("order event body has orderId " + payload.orderId()
                        + " but arrived in an envelope for order " + aggregateId
                        + "; refused, so no customer is told about another order");
            }
        }

        /** A header as text: a String as the binder hands it over, or raw UTF-8 bytes as Kafka carries it. */
        private static String header(Message<?> message, String name) {
            Object value = message.getHeaders().get(name);
            return value instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : String.valueOf(value);
        }

        private Template renderTemplate(String type, OrderEventPayload payload) {
            // Keep templates inline for the stub. Real impl picks them
            // from a CMS / Mustache-like engine.
            return switch (type) {
                case "order.created"   -> new Template("Your Oriah order is on its way to confirmation",
                        "Thank you. We received your order " + payload.orderId() +
                        " for " + payload.total().toPlainString() + " " + payload.currency());
                case "order.paid"      -> new Template("Payment received — thank you!",
                        "Your payment for order " + payload.orderId() + " was successful.");
                case "order.fulfilled" -> new Template("Your order is being prepared",
                        "Order " + payload.orderId() + " has been fulfilled and is awaiting shipment.");
                case "order.shipped"   -> new Template("Your order has shipped",
                        "Order " + payload.orderId() + " is on its way to you.");
                case "order.delivered" -> new Template("Your order has been delivered",
                        "Order " + payload.orderId() + " was delivered. We hope you love it.");
                case "order.cancelled" -> new Template("Your order was cancelled",
                        "Order " + payload.orderId() + " has been cancelled. Any payment will be refunded.");
                // Reads no figure on purpose: an expiry's snapshot is not guaranteed to carry totals.
                case "order.expired"   -> new Template("Your order has expired",
                        "Order " + payload.orderId() + " was not paid in time, so the pieces we were holding for you have been let go. "
                                + "If you have already paid, please contact us.");
                // Unreachable while NOTIFICATION and this switch list the same
                // types: the Inbox refuses any other before this runs. If one
                // is ever declared without a template, fail (retry, then the
                // dead-letter topic) rather than send a vague message.
                default -> throw new IllegalStateException("no template for declared event type " + type);
            };
        }

        record Template(String subject, String body) {}
    }
}
