package com.oriah.notification.delivery;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.oriah.notification.config.EmailSetup;
import com.oriah.notification.config.NotificationSettings;
import com.oriah.notification.contact.ContactBook;
import com.oriah.notification.delivery.MessageStore.Due;
import com.oriah.notification.delivery.SendDecision.Decision;
import com.oriah.notification.delivery.SendDecision.NotSent;
import com.oriah.notification.delivery.SendDecision.Send;
import com.oriah.notification.delivery.SendDecision.ShopperFacts;
import com.oriah.notification.delivery.SendDecision.Wait;
import com.oriah.notification.model.RecipientKind;
import com.oriah.notification.model.StatusReason;
import com.oriah.notification.orders.OrderRefs;
import com.oriah.notification.text.MessageTexts;

/**
 * The one job that sends. The consumers only write messages down; this picks up the ones that wait and takes each to its end.
 *
 * <h2>One pass</h2>
 * <ol>
 *   <li>Write down any message a mail server took earlier whose record could not be written then (see "at least once").</li>
 *   <li>End every waiting message whose deadline has passed.</li>
 *   <li>For each message that may go now, four separate steps:
 *     <ol>
 *       <li><b>decide</b> (reads only): hand it over, look again later, or do not send it ({@link SendDecision});</li>
 *       <li><b>take it</b>: one statement that counts the try and puts the row out of reach for the lease;</li>
 *       <li><b>send</b>: with NO database transaction and no connection held. A mail server may take a minute to answer, and a connection
 *           held meanwhile is one the consumers cannot use;</li>
 *       <li><b>write the outcome</b>: one statement that changes the row only if this try still holds it.</li>
 *     </ol>
 *   </li>
 * </ol>
 * Each message is handled inside its own try/catch: a message that cannot be handled is logged by its id and the pass goes on.
 *
 * <h2>What a failure means</h2>
 * A refused address ends that message. A mailbox that cannot take mail just now puts that message back on the ladder (1, 5, 15, 60 minutes,
 * then hourly) and nothing else changes. A failure of the mail server ITSELF fails no message: the message goes back on the ladder, the pass
 * ENDS (the next message would fail the same way), and nothing is handed over for a quiet time that doubles with each failure in a row. A send
 * whose answer never came is tried at most once more, not before the longest wait, and then ends as "outcome unknown".
 *
 * <h2>At least once</h2>
 * A message is never marked sent before a mail server took it, so a stop at the wrong moment sends a second copy rather than none. A copy is
 * possible each time a server's acceptance could not be recorded (the process stopped, or the database was away longer than the three tries
 * made on the spot), and from the one retry of an unanswered send; copies stop at the message's deadline. To keep that to the unavoidable
 * case, a message whose acceptance could not be written is remembered here, in memory, and written at the start of the next passes instead
 * of being sent again. Only a process stop loses that memory.
 *
 * <p>No log line carries an address or a message's words: an id, a type, an outcome and a fixed code.
 */
public class MessageDispatcher {

    private static final Logger log = LoggerFactory.getLogger(MessageDispatcher.class);
    private static final int TRIES_TO_RECORD_A_SEND = 3;
    private static final Duration BETWEEN_TRIES_TO_RECORD = Duration.ofMillis(200);
    /** A second send with no answer in one pass ends the pass: something is wrong, though nobody can say what. */
    private static final int UNANSWERED_SENDS_THAT_END_A_PASS = 2;

    private final MessageStore store;
    private final ContactBook contacts;
    private final OrderRefs orderRefs;
    private final DispatchHealth health;
    private final MessageTransport transport;
    private final EmailSetup setup;
    private final NotificationSettings settings;
    private final Clock clock;
    /** Messages a mail server took whose record could not be written yet. */
    private final Map<UUID, Accepted> acceptedNotRecorded = new ConcurrentHashMap<>();

    private record Accepted(int claimed, Instant at, boolean shortForm) {
    }

    public MessageDispatcher(MessageStore store, ContactBook contacts, OrderRefs orderRefs, DispatchHealth health, MessageTransport transport,
                             EmailSetup setup, NotificationSettings settings, Clock clock) {
        this.store = store;
        this.contacts = contacts;
        this.orderRefs = orderRefs;
        this.health = health;
        this.transport = transport;
        this.setup = setup;
        this.settings = settings;
        this.clock = clock;
    }

    /** One pass. Never throws: a scheduled job that throws is not run again by some schedulers, and this one must keep running. */
    public void run() {
        Instant now = clock.instant();
        NotificationSettings.Send send = settings.send();
        step("writing down what was already sent", this::recordWhatWasAccepted);
        step("ending the messages whose time has passed", () -> store.sweep(now, send.lease()));

        List<Due> due;
        try {
            due = store.due(now, send.batchSize());
        } catch (RuntimeException failure) {
            log.error("The messages waiting to be sent could not be read ({})", failure.getClass().getSimpleName());
            return;
        }
        int unanswered = 0;
        for (Due row : due) {
            if (acceptedNotRecorded.containsKey(row.id())) {
                continue;
            }
            try {
                Pass pass = one(row, now);
                if (pass == Pass.UNANSWERED) {
                    unanswered++;
                }
                if (pass == Pass.END || unanswered >= UNANSWERED_SENDS_THAT_END_A_PASS) {
                    return;
                }
            } catch (RuntimeException failure) {
                // The kind of failure only: its message could quote a row.
                log.error("Message {} could not be handled in this pass ({}); it stays as it is", row.id(), failure.getClass().getSimpleName());
            }
        }
    }

    private enum Pass {
        GO_ON, UNANSWERED, END
    }

    private Pass one(Due row, Instant now) {
        Decision decision = decide(row, now);
        switch (decision) {
            case NotSent notSent -> {
                store.notSent(row.id(), notSent.reason(), now);
                log.info("Message {} ({}) is not sent: {}", row.id(), row.eventType(), notSent.reason());
                return Pass.GO_ON;
            }
            case Wait wait -> {
                store.waitUntil(row.id(), wait.until(), now);
                return Pass.GO_ON;
            }
            case Send send -> {
                return handOver(row, send, now);
            }
        }
    }

    private Decision decide(Due row, Instant now) {
        if (row.recipientKind() != RecipientKind.SHOPPER) {
            throw new IllegalStateException("only a customer's message can wait to be sent yet");
        }
        boolean hasSeq = row.eventSeq() != null;
        boolean laterDelivered = hasSeq && store.laterMessage(row.orderId(), row.eventSeq(), "order.delivered", false);
        boolean laterShippedSent = hasSeq && store.laterMessage(row.orderId(), row.eventSeq(), "order.shipped", true);
        boolean orderKnownPaid = row.orderId() != null && orderRefs.find(row.orderId()).map(ref -> ref.paidAt() != null).orElse(false);
        return SendDecision.forShopper(new ShopperFacts(row.eventType(), contacts.find(row.keycloakId()), laterDelivered, laterShippedSent,
                orderKnownPaid), setup, now, settings.send().addressRecheck());
    }

    private Pass handOver(Due row, Send send, Instant now) {
        NotificationSettings.Send timing = settings.send();
        if (health.state().quietAt(now)) {
            // The mail server failed a moment ago. The message stays due and is tried once the quiet time is over.
            return Pass.GO_ON;
        }
        OptionalInt claim = store.claim(row.id(), now, timing.lease());
        if (claim.isEmpty()) {
            // Another dispatcher has it, or it is no longer waiting.
            return Pass.GO_ON;
        }
        int claimed = claim.getAsInt();

        SendResult result;
        try {
            result = transport.send(email(row, send));
        } catch (RuntimeException unexpected) {
            // A transport never throws. If one does, nothing is known about the message: treat it as the server's trouble.
            result = SendResult.serverTryLater(SendResult.OTHER);
        }

        switch (result.outcome()) {
            case ACCEPTED -> {
                recordSent(row.id(), new Accepted(claimed, now, send.shortForm()));
                step("noting that the mail server works", health::serverWorked);
                return Pass.GO_ON;
            }
            case REFUSED -> {
                if (SendResult.SANDBOX.equals(result.code())) {
                    store.notSentAfterClaim(row.id(), claimed, StatusReason.SANDBOX, now);
                } else {
                    store.fail(row.id(), claimed, StatusReason.ADDRESS_REFUSED, result.code(), now);
                }
                log.warn("Message {} ({}) was refused: {}", row.id(), row.eventType(), result.code());
                return Pass.GO_ON;
            }
            case RECIPIENT_TRY_LATER -> {
                store.retryLater(row.id(), claimed, now.plus(waitAfter(claimed)), result.code());
                return Pass.GO_ON;
            }
            case SERVER_TRY_LATER -> {
                store.retryLater(row.id(), claimed, now.plus(waitAfter(claimed)), result.code());
                health.serverFailed(now, timing.serverQuiet(), timing.serverQuietMax());
                log.warn("The mail server could not be used ({}); nothing more is sent in this pass", result.code());
                return Pass.END;
            }
            case UNCERTAIN -> {
                Instant oneMoreTry = now.plus(timing.backoff().getLast());
                if (row.maybeHandedOver() || !oneMoreTry.isBefore(row.sendBefore())) {
                    store.fail(row.id(), claimed, StatusReason.OUTCOME_UNKNOWN, result.code(), now);
                } else {
                    store.uncertain(row.id(), claimed, oneMoreTry);
                }
                log.warn("Message {} ({}) was handed over and no answer came: it may have arrived", row.id(), row.eventType());
                return Pass.UNANSWERED;
            }
        }
        return Pass.GO_ON;
    }

    /** The wait after the n-th failed try: the ladder, its last rung repeating. */
    private Duration waitAfter(int attempts) {
        List<Duration> ladder = settings.send().backoff();
        return ladder.get(Math.min(Math.max(attempts, 1), ladder.size()) - 1);
    }

    /**
     * The email for one row. A customer with a confirmed address gets the stored words; an address nobody confirmed gets the short note in
     * their place. The foot (the shop's name, and a link when the storefront's address is configured) is added here and is not stored.
     */
    private Email email(Due row, Send send) {
        String subject = row.subject();
        String body = row.body();
        if (send.shortForm()) {
            MessageTexts.Text shortNote = MessageTexts.shortForm();
            subject = shortNote.subject();
            body = shortNote.body();
        }
        return new Email(row.id(), send.address(), subject, body + foot(row, send.shortForm()));
    }

    private String foot(Due row, boolean shortForm) {
        StringBuilder foot = new StringBuilder("\n\nOriah Jewelry");
        String site = settings.siteUrl();
        if (site != null && !site.isBlank()) {
            String base = site.strip().replaceAll("/+$", "");
            // The short note names no order: an address nobody confirmed learns only that there is news.
            foot.append('\n').append(shortForm || row.orderId() == null ? base : "See your order: " + base + "/orders/" + row.orderId());
        }
        return foot.toString();
    }

    /** Marks a message sent, trying a few times on the spot. If that cannot be done now, it is remembered and never handed over again. */
    private void recordSent(UUID id, Accepted accepted) {
        for (int attempt = 1; attempt <= TRIES_TO_RECORD_A_SEND; attempt++) {
            try {
                store.markSent(id, accepted.claimed(), accepted.at(), accepted.shortForm());
                acceptedNotRecorded.remove(id);
                return;
            } catch (RuntimeException failure) {
                if (attempt < TRIES_TO_RECORD_A_SEND) {
                    pause();
                }
            }
        }
        acceptedNotRecorded.put(id, accepted);
        log.error("Message {} was taken by the mail server but could not be marked sent; it will be marked on a later pass, not sent again", id);
    }

    private void recordWhatWasAccepted() {
        acceptedNotRecorded.forEach((id, accepted) -> {
            // If another dispatcher took the row meanwhile this changes nothing, and there is nothing more this one can do about it.
            store.markSent(id, accepted.claimed(), accepted.at(), accepted.shortForm());
            acceptedNotRecorded.remove(id);
        });
    }

    private static void pause() {
        try {
            Thread.sleep(BETWEEN_TRIES_TO_RECORD.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** One part of a pass, on its own: a failure is logged and the pass goes on to the next part. */
    private void step(String what, Runnable part) {
        try {
            part.run();
        } catch (RuntimeException failure) {
            log.error("A dispatcher pass failed while {} ({}); the rest of the pass goes on", what, failure.getClass().getSimpleName());
        }
    }
}
