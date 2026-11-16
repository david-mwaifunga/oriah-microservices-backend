package com.oriah.notification.events;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
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
import com.oriah.notification.alerts.StaffAlerts;
import com.oriah.notification.config.NotificationSettings;
import com.oriah.notification.model.MessageKind;
import com.oriah.notification.model.NotificationLog;
import com.oriah.notification.model.StatusReason;
import com.oriah.notification.orders.OrderRefs;
import com.oriah.notification.repository.NotificationLogRepository;
import com.oriah.notification.text.MessageTexts;
import com.oriah.notification.text.MessageTexts.OrderWords;
import com.oriah.notification.text.MessageTexts.ShopLines;
import com.oriah.notification.text.MessageTexts.Text;

/**
 * Subscribes to the {@code oriah.order} Kafka topic. Every change to an order publishes one event there (created, paid, shipped and so on);
 * this consumer turns each into a message for the customer and WRITES IT DOWN (a notification_log row). It sends nothing.
 *
 * <p>group=notification ensures all notification instances share one consumer group: each event is delivered to exactly one instance.
 *
 * <h2>Recorded here, sent by somebody else</h2>
 *
 * Kafka delivers at least once, so the same event can arrive twice. Each event is one message to a person, a fact and not a copy of the
 * order's state, so it goes through {@link Inbox#once}: the record that it was applied (processed_event) commits in the same transaction as its
 * notification_log row, and a second delivery finds the record and writes nothing.
 *
 * <p>The row says what is to become of the message: it waits to be emailed (RECORDED), or it is final at once with the reason (this kind is
 * shown on the website only; the event is already too old; it announces a dispatch step keyed long after it happened). A scheduled dispatcher
 * sends the rows that wait. Until Phase 11 the send happened here, on the thread that reads Kafka, after the commit: a slow mail server would
 * have held up every order behind it, and a message whose send failed after the commit was lost for good. Now a mail server cannot hold up,
 * fail or dead-letter an event, and a message that could not be sent is still in its row to be tried again.
 *
 * <h2>Nothing is swallowed</h2>
 *
 * An event that cannot be applied (a type or payload version this consumer does not declare, an aggregate that is not an order, a missing or
 * garbled envelope header, a body that breaks the contract) throws. The transaction rolls back with its record, and the oriah-events module's
 * error handler publishes it to {@code oriah.order.dlq.notification}: on its first delivery for a refusal like these, which would fail the same
 * way again, and after three deliveries in all for anything else, such as the database being away (application.yaml). It commits the event
 * only once that topic has it, so a failed dead-letter publish keeps the event instead of dropping it.
 */
@Configuration
@lombok.extern.slf4j.Slf4j
public class OrderEventConsumer {

    /**
     * What every order event is about, spelled exactly as order writes it in the aggregate-type header (OrderEvent.AGGREGATE_TYPE, "order", lower
     * case). The Inbox refuses an order event that says it is about anything else, "Order" included, before anything is recorded: notification
     * reads its aggregate-id as an order's id, so it must be one.
     */
    static final String ORDER = "order";

    /**
     * Every type order publishes on oriah.order. Each status change is {@code order.<status>} with the full order snapshot ({@link OrderEventPayload})
     * as its payload and has a message to write; the two facts order announces about a sale in progress are not for the customer and are ignored.
     *
     * <ul>
     *   <li>{@code order.paid} has two versions (Phase 7): the second keeps every field of the first, in the same order, and adds what the sale was
     *       paid with. The customer is told the receipt number from the second.
     *   <li>{@code order.expired}: an order's hold was let go. It reads differently when the customer had told us they paid.
     *   <li>{@code order.payment_submitted} is a FACT for staff, not a snapshot of the order: a shopper says they have paid. It raises the alert
     *       "a customer says they have paid" ({@link StaffAlerts#claimReceived}); nothing is written for the customer.
     *   <li>{@code order.payment_unsettled} is a FACT for staff too: recorded money could not pay the sale and is kept for the customer. It is
     *       an answer for that payment ({@link ConfirmationWatch#unsettled}); nothing is written for the customer.
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
            .handles(ORDER, "order.payment_submitted", 1)
            .handles(ORDER, "order.payment_unsettled", 1);

    /** The steps of a dispatch. One keyed long after it happened is not announced: the customer already has the parcel. */
    private static final Set<String> DISPATCH_STEPS = Set.of("order.fulfilled", "order.shipped", "order.delivered");
    private static final String THE_CUSTOMER_PAYS_THE_COURIER = "RECIPIENT_PAYS";

    @Bean
    public Consumer<Message<byte[]>> orderEvents(Inbox inbox, NotificationLogRepository repository, OrderRefs orderRefs, StaffAlerts alerts,
                                                 ConfirmationWatch watch, NotificationSettings settings, Clock clock, ObjectMapper objectMapper) {
        return new Handler(inbox, repository, orderRefs, alerts, watch, settings, clock, objectMapper);
    }

    static class Handler implements Consumer<Message<byte[]>> {
        private final Inbox inbox;
        private final NotificationLogRepository repository;
        private final OrderRefs orderRefs;
        private final StaffAlerts alerts;
        private final ConfirmationWatch watch;
        private final NotificationSettings settings;
        private final Clock clock;
        private final ObjectMapper objectMapper;

        Handler(Inbox inbox, NotificationLogRepository repository, OrderRefs orderRefs, StaffAlerts alerts, ConfirmationWatch watch,
                NotificationSettings settings, Clock clock, ObjectMapper objectMapper) {
            this.inbox = inbox;
            this.repository = repository;
            this.orderRefs = orderRefs;
            this.alerts = alerts;
            this.watch = watch;
            this.settings = settings;
            this.clock = clock;
            this.objectMapper = objectMapper;
        }

        @Override
        @Transactional
        public void accept(Message<byte[]> message) {
            inbox.once(NOTIFICATION, message.getHeaders(), () -> record(message));
        }

        /** Runs only for a handled type the Inbox has not seen; the Inbox checked the headers. Everything here is one transaction with its marker. */
        private void record(Message<byte[]> message) {
            EnvelopeFacts facts = EnvelopeFacts.from(message);
            String type = facts.eventType();
            if ("order.payment_submitted".equals(type)) {
                // Branch FIRST: a claim is a fact for staff with a shape of its own, never read as a snapshot of the order.
                claimed(message, facts);
                return;
            }
            if ("order.payment_unsettled".equals(type)) {
                unsettled(message, facts);
                return;
            }
            OrderEventPayload payload = read(message.getPayload(), OrderEventPayload.class);
            requireAboutTheEnvelopesOrder(facts, payload);
            boolean hasOwner = payload.keycloakId() != null && !payload.keycloakId().isBlank();
            if (!hasOwner && !payload.isPhone()) {
                // A web order always has an owner: one without is a defect upstream, refused so it is noticed (the dead-letter topic on Kafka).
                throw new IllegalArgumentException("order event " + type + " for order " + payload.orderId() + " has no owner and is not a phone order");
            }
            Instant now = clock.instant();

            // What notification remembers about the order, from EVERY event of it (a repeated payment too): whose it is, and, once it is paid,
            // that it is paid and its receipt number. Later messages name the order by that number, and a "we could not complete your order"
            // that is still waiting is held back once the order turned out to be paid after all.
            boolean paid = "order.paid".equals(type);
            PaidExtras paidWith = paid ? read(message.getPayload(), PaidExtras.class) : null;
            orderRefs.note(payload.orderId(), payload.isPhone() ? null : payload.keycloakId(), payload.isPhone(),
                    payload.isPhone() ? read(message.getPayload(), PhoneExtras.class).takenLive() : null,
                    paid ? paidWith.receiptNumber() : null, paid ? facts.recordedAt() : null, now);

            // What this event answers is closed before anything else can return. The ORDER of the two steps is a rule: the confirmation's row
            // is taken first and the claim alert after it, exactly as payment's facts do (ConfirmationWatch), so the two consumers, which run
            // side by side on two topics, can never wait for each other.
            if (paid && paidWith.confirmationId() != null) {
                // The answer to "did this money pay its sale?", for every order.paid that names its confirmation.
                watch.paid(paidWith.confirmationId(), payload.orderId(), facts.recordedAt(), now);
            }
            // An order that is paid, expired or cancelled no longer has a payment waiting to be confirmed. (A repeated payment and a phone
            // order's payment close it too.)
            String ended = switch (type) {
                case "order.paid" -> "PAID";
                case "order.expired" -> "EXPIRED";
                case "order.cancelled" -> "CANCELLED";
                default -> null;
            };
            if (ended != null) {
                alerts.resolve(StaffAlerts.Kind.PAYMENT_TO_CONFIRM, payload.orderId().toString(), ended, now);
            }

            if (Boolean.TRUE.equals(payload.republished())) {
                // The same sale announced again so payment can hear its answer: the customer was told when it was first announced. The Inbox records
                // this event as applied all the same, so it is never delivered again.
                log.info("{} for order {} is a repeat of one already announced: the customer is not told twice", type, payload.orderId());
                return;
            }

            MessageKind kind = MessageKind.of(type, payload.deliveryOption(), payload.previousStatus());
            OrderRefs.OrderRef known = orderRefs.find(payload.orderId()).orElseThrow();
            boolean recipientPays = "order.shipped".equals(type)
                    && THE_CUSTOMER_PAYS_THE_COURIER.equals(read(message.getPayload(), ShippedExtras.class).payer());
            OrderWords words = new OrderWords(payload.orderId(), known.receiptNumber(), payload.total(), payload.currency(), payload.deliveryOption(),
                    recipientPays);

            if (payload.isPhone()) {
                recordForACaller(type, kind, words, known, facts, now);
                return;
            }
            Text text = MessageTexts.forShopper(kind, words, shopLines());

            Instant sendBefore = facts.recordedAt().plus(settings.send().window());
            StatusReason notSentBecause = whyNotEmailed(kind, type, facts, now, sendBefore);
            repository.save(NotificationLog.forShopper(payload.orderId(), payload.keycloakId(), type, text.subject(), text.body(), facts.eventId(),
                    facts.seq(), facts.recordedAt(), now, notSentBecause, sendBefore));
            // Which event, which order, what became of it. Never the words: they are in the row.
            log.info("{} for order {} written down as a message: {}", type, payload.orderId(),
                    notSentBecause == null ? "waiting to be emailed" : "not emailed (" + notSentBecause + ")");
        }

        /**
         * {@code order.payment_submitted}: a customer told the shop they paid. Only the order, the method and the two times are read: the
         * customer's reference is not declared, so it never reaches a row or a log line here.
         */
        private void claimed(Message<byte[]> message, EnvelopeFacts facts) {
            ClaimFact claim = read(message.getPayload(), ClaimFact.class);
            if (claim.orderId() == null || !claim.orderId().equals(facts.aggregateId())) {
                throw new IllegalArgumentException("a payment claim names order " + claim.orderId() + " but arrived in an envelope for order "
                        + facts.aggregateId() + "; refused, so staff are not sent to the wrong order");
            }
            if (claim.holdExpiresAt() == null) {
                throw new IllegalArgumentException("a payment claim for order " + claim.orderId() + " carries no holdExpiresAt");
            }
            alerts.claimReceived(claim.orderId(), claim.method(), claim.holdExpiresAt(), facts.recordedAt(), clock.instant());
            log.info("order.payment_submitted for order {} noted: staff are told a customer says they have paid", claim.orderId());
        }

        /**
         * {@code order.payment_unsettled}: recorded money could not pay the sale. Only ids and the reason code are read. An order notification
         * has never heard of is not refused: the fact is about the payment.
         */
        private void unsettled(Message<byte[]> message, EnvelopeFacts facts) {
            UnsettledFact fact = read(message.getPayload(), UnsettledFact.class);
            if (fact.orderId() == null || !fact.orderId().equals(facts.aggregateId())) {
                throw new IllegalArgumentException("unsettled money names order " + fact.orderId() + " but arrived in an envelope for order "
                        + facts.aggregateId());
            }
            if (fact.confirmationId() == null) {
                throw new IllegalArgumentException("unsettled money for order " + fact.orderId() + " names no confirmation");
            }
            watch.unsettled(fact.confirmationId(), fact.orderId(), fact.reason(), facts.recordedAt(), clock.instant());
            log.info("order.payment_unsettled for order {} noted", fact.orderId());
        }

        /**
         * A customer who ordered by phone has no account and no email, and no machine holds or uses their phone number. For a sale taken LIVE on
         * the phone the words are written down for sales staff to send from the shop's own WhatsApp (a BY_HAND row, in nobody's bell). Nothing is
         * written for a sale typed in later from paper, however soon it was typed (that customer already has their goods), nor for a phone order
         * whose events never said which kind it was (written before order said so): nothing is guessed. A dispatch step keyed long after it
         * happened is not announced either. Whatever is decided, the event is recorded as applied, so it is never delivered again.
         */
        private void recordForACaller(String type, MessageKind kind, OrderWords words, OrderRefs.OrderRef known, EnvelopeFacts facts, Instant now) {
            if (!Boolean.TRUE.equals(known.livePhoneSale()) || !MessageTexts.writtenForACaller(kind) || keyedLongAfterItHappened(type, facts)) {
                log.info("{} for order {} was recorded by staff for a caller with no account: no words are written for it", type, known.orderId());
                return;
            }
            Text text = MessageTexts.forCaller(kind, words, shopLines());
            repository.save(NotificationLog.byHandForCaller(known.orderId(), type, text.subject(), text.body(), facts.eventId(), facts.seq(),
                    facts.recordedAt(), now));
            log.info("{} for order {} written down as words for staff to send to the caller", type, known.orderId());
        }

        /** A dispatch step (packed, on its way, delivered) keyed more than a day after it happened: the customer already knows. */
        private boolean keyedLongAfterItHappened(String type, EnvelopeFacts facts) {
            return DISPATCH_STEPS.contains(type)
                    && Duration.between(facts.occurredAt(), facts.recordedAt()).compareTo(settings.send().backdatedStepAfter()) > 0;
        }

        /**
         * Why a message is NOT going to be emailed, decided when it is written down; null when it waits to be sent.
         *
         * <p>Both "too late" rules read the producer's own times from the envelope, never the moment Kafka delivered the event: history read
         * late (after an outage, or by a database that starts empty) looks as old as it is. A payment is never "keyed late": staff confirm
         * money when they see it, which can be a day after it arrived, and "payment received" is news to the customer whenever that is.
         */
        private StatusReason whyNotEmailed(MessageKind kind, String type, EnvelopeFacts facts, Instant now, Instant sendBefore) {
            if (!settings.email().emailed().contains(kind)) {
                return StatusReason.BELL_ONLY;
            }
            if (keyedLongAfterItHappened(type, facts)) {
                return StatusReason.BACKDATED_STEP;
            }
            if (!now.isBefore(sendBefore)) {
                return StatusReason.TOO_LATE;
            }
            return null;
        }

        private ShopLines shopLines() {
            NotificationSettings.Shop shop = settings.shop();
            return new ShopLines(shop.payLine(), shop.collectLine(), shop.contactLine());
        }

        private <T> T read(byte[] body, Class<T> as) {
            try {
                return objectMapper.readValue(body, as);
            } catch (IOException e) {
                throw new UncheckedIOException("order event body does not match the contract", e);
            }
        }

        /**
         * The body must be a snapshot of the order the envelope names (aggregate-id). A body about another order, or about no order at all,
         * would tell one customer news of somebody else's order, so it is refused (an IllegalArgumentException, which the binding never retries:
         * it goes to the dead-letter topic on its first delivery).
         *
         * <p>This is the first thing the effect does, before anything of notification's own is written. The Inbox inserted its "applied" marker a
         * moment earlier, in this same transaction, so the refusal rolls the marker back with it: nothing is recorded. (It runs after the Inbox's
         * own checks on purpose: an event of a type or payload version notification does not read is refused for that reason, rather than for a
         * body this record cannot make sense of.) Search and inventory check their product snapshots the same way.
         */
        private static void requireAboutTheEnvelopesOrder(EnvelopeFacts facts, OrderEventPayload payload) {
            if (payload.orderId() == null || !payload.orderId().equals(facts.aggregateId())) {
                throw new IllegalArgumentException("order event body has orderId " + payload.orderId()
                        + " but arrived in an envelope for order " + facts.aggregateId()
                        + "; refused, so no customer is told about another order");
            }
        }
    }

    /** What a payment claim says that an alert uses. The claim id and the customer's reference are deliberately not declared. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ClaimFact(java.util.UUID orderId, String method, Instant submittedAt, Instant holdExpiresAt) {
    }

    /** What "money could not pay the sale" says that an alert uses: which payment, which order, and a reason CODE. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record UnsettledFact(java.util.UUID confirmationId, java.util.UUID orderId, String reason) {
    }

    /** What order.paid's second version adds that a message uses; absent (null) in the first version. Nothing else of the payment is read. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record PaidExtras(java.util.UUID confirmationId, String receiptNumber) {
    }

    /**
     * What a phone order's events say beyond the snapshot (Phase 11): which kind of sale staff recorded. It is what the person chose on the
     * form, never a guess from dates. Absent on events written before order said it, and then nothing is known.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record PhoneExtras(String saleKind) {

        /** True for a sale taken live on the phone, false for one typed in later from paper, null when the event does not say (or says a kind unknown here). */
        Boolean takenLive() {
            if ("LIVE_PHONE".equals(saleKind)) {
                return Boolean.TRUE;
            }
            return "LATE_PAPER".equals(saleKind) ? Boolean.FALSE : null;
        }
    }

    /** What order.shipped says about the dispatch that a message uses: who pays the courier. The fare is never read. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ShippedExtras(Dispatch dispatch) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        record Dispatch(String payer) {
        }

        String payer() {
            return dispatch == null ? null : dispatch.payer();
        }
    }
}
