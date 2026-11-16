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

import com.oriah.notification.alerts.ConfirmationWatch;
import com.oriah.notification.alerts.StaffAlerts;
import com.oriah.notification.config.EmailSetup;
import com.oriah.notification.config.NotificationSettings;
import com.oriah.notification.contact.ContactBook;
import com.oriah.notification.delivery.MessageStore.Due;
import com.oriah.notification.delivery.SendDecision.Decision;
import com.oriah.notification.delivery.SendDecision.NotSent;
import com.oriah.notification.delivery.SendDecision.Send;
import com.oriah.notification.delivery.SendDecision.ShopperFacts;
import com.oriah.notification.delivery.SendDecision.StaffFacts;
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
 * whose answer never came is tried at most once more, not before the longest wait, and then ends as "outcome unknown"; two of those in one
 * pass count as the server's trouble. When the last three messages tried were refused for three different people, the server is refusing
 * EVERYBODY (a sending account that may not send as the shop's address, say): that is listed for staff, the pass ends, and until a message
 * is accepted again a refusal is not believed: the message waits and is tried again.
 *
 * <h2>Every message at its own time</h2>
 * A slow mail server can make one pass last many minutes. So the clock is read again for every message, when it is taken and when its
 * answer has come: the lease runs from the moment the send begins, and "sent at", the wait before the next try and the quiet time are counted
 * from the answer. A message is looked at twice: before it is taken (so nothing is taken needlessly) and again once it is ours, because
 * whoever ended its alert in between saw no try and will say nothing more.
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
    private static final String DIGEST = "DIGEST";
    private static final int TRIES_TO_RECORD_A_SEND = 3;
    private static final Duration BETWEEN_TRIES_TO_RECORD = Duration.ofMillis(200);
    /** A second send with no answer in one pass ends the pass: something is wrong, though nobody can say what. */
    private static final int UNANSWERED_SENDS_THAT_END_A_PASS = 2;

    private final MessageStore store;
    private final ContactBook contacts;
    private final OrderRefs orderRefs;
    private final DispatchHealth health;
    private final StaffAlerts alerts;
    private final ConfirmationWatch watch;
    private final MessageTransport transport;
    private final EmailSetup setup;
    private final NotificationSettings settings;
    private final Clock clock;
    /** How many messages this pass handed to the mail server; only the pass's own thread touches it. */
    private int handedOverThisPass;
    /** Messages a mail server took whose record could not be written yet. */
    private final Map<UUID, Accepted> acceptedNotRecorded = new ConcurrentHashMap<>();

    /**
     * @param alertId the alert a staff row is about, which this acceptance makes "told"; null for other rows
     * @param digest  whether it is a below-cost digest, whose pieces this acceptance makes "told"
     */
    private record Accepted(int claimed, Instant at, boolean shortForm, UUID alertId, boolean digest) {
    }

    public MessageDispatcher(MessageStore store, ContactBook contacts, OrderRefs orderRefs, DispatchHealth health, StaffAlerts alerts,
                             ConfirmationWatch watch, MessageTransport transport, EmailSetup setup, NotificationSettings settings, Clock clock) {
        this.store = store;
        this.contacts = contacts;
        this.orderRefs = orderRefs;
        this.health = health;
        this.alerts = alerts;
        this.watch = watch;
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
        // The alert clock first: a reminder written now is sent in this same pass, and an alert that just closed is not emailed.
        step("reminding of, closing and re-telling alerts", () -> alerts.clock(now));
        step("saying the last note of alerts that closed by the clock", () -> watch.sayLastNotes(now));
        step("ending the messages whose time has passed", () -> store.sweep(now, send.lease()));
        handedOverThisPass = 0;
        step("sending the messages that wait", () -> sendWhatWaits(now));
        step("judging whether email works", () -> judgeMailHealth(clock.instant()));
    }

    private void sendWhatWaits(Instant passBegan) {
        int unanswered = 0;
        for (Due row : store.due(passBegan, settings.send().batchSize())) {
            if (acceptedNotRecorded.containsKey(row.id())) {
                continue;
            }
            try {
                // Each message at its own time: a slow server makes a pass last minutes, and a lease counted from the pass's start would
                // have run out before the send it is there to cover had even begun.
                Pass pass = one(row, clock.instant());
                if (pass == Pass.UNANSWERED && ++unanswered >= UNANSWERED_SENDS_THAT_END_A_PASS) {
                    // Two sends in a row with no answer: nobody can say what is wrong, but it is the server's trouble, not two bad messages.
                    health.serverFailed(clock.instant(), settings.send().serverQuiet(), settings.send().serverQuietMax());
                    return;
                }
                if (pass == Pass.END) {
                    return;
                }
            } catch (RuntimeException failure) {
                // The kind of failure only: its message could quote a row.
                log.error("Message {} could not be handled in this pass ({}); it stays as it is", row.id(), failure.getClass().getSimpleName());
            }
        }
    }

    /**
     * "Emails are not going out" is a fact staff must be able to read WITHOUT email, so it is judged on every pass from the stored state,
     * never only when a send happens to fail. It is listed and logged once; it cannot be emailed.
     *
     * <p>When the server has been failing, its quiet time is over and this pass had nothing to hand over, the server is asked whether it is
     * there (connect, sign in, leave): a shop with nothing queued must not read "emails are not going out" for days after the server came back.
     */
    private void judgeMailHealth(Instant now) {
        DispatchHealth.State state = health.state();
        if (state.failingSince() != null && !state.quietAt(now) && handedOverThisPass == 0 && setup.canSend()) {
            if (transport.probe().outcome() == SendResult.Outcome.ACCEPTED) {
                health.serverWorked();
                alerts.emailWorks(now);
            } else {
                health.serverFailed(now, settings.send().serverQuiet(), settings.send().serverQuietMax());
            }
            state = health.state();
        }
        if (state.failingSince() != null && !state.failingSince().isAfter(now.minus(settings.alerts().downAfter()))
                && alerts.emailIsDown(state.failingSince(), now)) {
            log.error("Emails are not going out: the mail server has been failing for {} minutes. Messages are kept and retried.",
                    java.time.Duration.between(state.failingSince(), now).toMinutes());
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
        if (row.recipientKind() == RecipientKind.STAFF) {
            // A digest names several alerts (the pieces newly below cost); it has "ended" when every one of them has cleared.
            boolean ended = DIGEST.equals(row.alertNotice()) ? store.everyPieceOfTheDigestHasCleared(row.id()) : store.alertResolved(row.alertId());
            // The list as it is configured NOW: a row written down for a mailbox that was since taken off the list is not sent to it.
            List<String> group = "COST".equals(row.audience()) ? setup.costRecipients() : setup.moneyRecipients();
            boolean onTheList = row.staffTo() != null && group.stream().anyMatch(row.staffTo()::equalsIgnoreCase);
            return SendDecision.forStaff(new StaffFacts(row.alertNotice(), row.staffTo(), ended,
                    row.alertId() != null && store.newerNotice(row.id()), onTheList), setup);
        }
        if (row.recipientKind() != RecipientKind.SHOPPER) {
            throw new IllegalStateException("words for a caller are sent by a person, never by the dispatcher");
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

        // The row is ours now. Look once more: whoever ended its alert (or learnt that its order was paid) before this moment saw no try of
        // it and will say nothing more, so it must not go; whoever does so from here on sees the try that was just counted.
        if (decide(row, now) instanceof NotSent after) {
            store.notSentAfterClaim(row.id(), claimed, after.reason(), now);
            log.info("Message {} ({}) is not sent: {}", row.id(), row.eventType(), after.reason());
            return Pass.GO_ON;
        }

        SendResult result;
        handedOverThisPass++;
        try {
            result = transport.send(email(row, send));
        } catch (RuntimeException unexpected) {
            // A transport never throws. If one does, nothing is known about the message: treat it as the server's trouble.
            result = SendResult.serverTryLater(SendResult.OTHER);
        }
        // The moment of the answer: a send may have taken a minute and more.
        Instant answered = clock.instant();

        switch (result.outcome()) {
            case ACCEPTED -> {
                recordSent(row.id(), new Accepted(claimed, answered, send.shortForm(), row.alertId(), DIGEST.equals(row.alertNotice())));
                step("noting that the mail server works", () -> {
                    health.serverWorked();
                    alerts.emailWorks(answered);
                });
                return Pass.GO_ON;
            }
            case REFUSED -> {
                log.warn("Message {} ({}) was refused: {}", row.id(), row.eventType(), result.code());
                if (SendResult.SANDBOX.equals(result.code())) {
                    store.notSentAfterClaim(row.id(), claimed, StatusReason.SANDBOX, answered);
                    return Pass.GO_ON;
                }
                if (alerts.emailIsListedAsDown()) {
                    // The server has been refusing everybody (or has only just come back): this refusal says nothing about this address.
                    // The message waits and is tried again; the next one would fare the same, so the pass ends.
                    store.retryLater(row.id(), claimed, answered.plus(waitAfter(claimed)), result.code());
                    return Pass.END;
                }
                store.fail(row.id(), claimed, StatusReason.ADDRESS_REFUSED, result.code(), answered);
                return everybodyIsBeingRefused(row, answered) ? Pass.END : Pass.GO_ON;
            }
            case RECIPIENT_TRY_LATER -> {
                store.retryLater(row.id(), claimed, answered.plus(waitAfter(claimed)), result.code());
                return Pass.GO_ON;
            }
            case SERVER_TRY_LATER -> {
                store.retryLater(row.id(), claimed, answered.plus(waitAfter(claimed)), result.code());
                health.serverFailed(answered, timing.serverQuiet(), timing.serverQuietMax());
                log.warn("The mail server could not be used ({}); nothing more is sent in this pass", result.code());
                return Pass.END;
            }
            case UNCERTAIN -> {
                Instant oneMoreTry = answered.plus(timing.backoff().getLast());
                if (row.maybeHandedOver() || !oneMoreTry.isBefore(row.sendBefore())) {
                    store.fail(row.id(), claimed, StatusReason.OUTCOME_UNKNOWN, result.code(), answered);
                } else {
                    store.uncertain(row.id(), claimed, oneMoreTry);
                }
                log.warn("Message {} ({}) was handed over and no answer came: it may have arrived", row.id(), row.eventType());
                return Pass.UNANSWERED;
            }
        }
        return Pass.GO_ON;
    }

    /**
     * A refused address is usually one bad address. Two cases are more than that and are listed for staff: a refused ALERT mailbox (an alert
     * that cannot reach its reader), and three refusals in a row for three different recipients (a mail server that refuses everybody).
     *
     * @return whether the server is refusing everybody: the pass then ends, and further refusals are not believed until a message is accepted
     */
    private boolean everybodyIsBeingRefused(Due row, Instant now) {
        try {
            if (row.recipientKind() == RecipientKind.STAFF && alerts.alertMailboxRefused(now)) {
                log.error("An alert mailbox was refused by the mail server (message {}); alerts may not reach it by email until it is corrected",
                        row.id());
            }
            if (!store.theLastThreeRecipientsWereAllRefused()) {
                return false;
            }
            if (alerts.emailIsDown(now, now)) {
                log.error("Emails are not going out: the mail server refused the last three recipients, each a different one. "
                        + "Messages still waiting are kept and tried again");
            }
            return true;
        } catch (RuntimeException failure) {
            log.error("A dispatcher pass failed while noting a refused address ({}); the rest of the pass goes on", failure.getClass().getSimpleName());
            return false;
        }
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
        if (row.recipientKind() == RecipientKind.STAFF) {
            // An alert to a shop mailbox is its stored words: no link, no foot. An alert that says the system needs attention ends with who
            // to turn to. That line is the owner's to change at any time, so it is added here and never stored.
            return new Email(row.id(), send.address(), subject, needsTheSupportLine(row) ? body + " " + supportLine() : body);
        }
        return new Email(row.id(), send.address(), subject, body + foot(row, send.shortForm()));
    }

    private static boolean needsTheSupportLine(Due row) {
        return ("alert.settlement_stuck".equals(row.eventType()) && !"SORTED".equals(row.alertNotice()))
                || "alert.below_cost_check_down".equals(row.eventType());
    }

    private String supportLine() {
        return setup.supportLine() == null || setup.supportLine().isBlank() ? MessageTexts.NOBODY_NAMED_FOR_SUPPORT : setup.supportLine();
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
                writeDown(id, accepted);
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
            writeDown(id, accepted);
            acceptedNotRecorded.remove(id);
        });
    }

    /**
     * Everything that follows from "a mail server took this message", safe to repeat: the row reads sent, and the alert it is about (or the
     * pieces its digest names) counts as told, because "told" means a mail server took it. If the claim no longer holds the row (the send
     * outlasted its lease), the row still reads sent: that is what happened, and a row left waiting would go out again.
     */
    private void writeDown(UUID id, Accepted accepted) {
        if (!store.markSent(id, accepted.claimed(), accepted.at(), accepted.shortForm())
                && store.sentAfterItsClaimWasLost(id, accepted.at(), accepted.shortForm())) {
            log.warn("Message {} was taken by the mail server after its claim was lost: it is marked sent, and a second copy may have gone out", id);
        }
        if (accepted.alertId() != null) {
            alerts.accepted(accepted.alertId(), accepted.at());
        } else if (accepted.digest()) {
            store.stampThePiecesOfTheDigest(id, accepted.at());
        }
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
