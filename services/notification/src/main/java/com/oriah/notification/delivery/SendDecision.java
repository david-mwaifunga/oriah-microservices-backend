package com.oriah.notification.delivery;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import com.oriah.notification.config.EmailSetup;
import com.oriah.notification.contact.ContactBook.Contact;
import com.oriah.notification.model.StatusReason;

/**
 * What to do with one waiting message, decided from facts read a moment ago. Pure: no database, no clock of its own.
 *
 * <p>The answer is one of three: hand it over (to which address, in full or as the short note), look again later (without spending a try),
 * or do not send it (with the reason, which is final).
 */
public final class SendDecision {

    private SendDecision() {
    }

    public sealed interface Decision permits Send, Wait, NotSent {
    }

    /** @param shortForm true to email the short "please sign in" note in place of the stored words */
    public record Send(String address, boolean shortForm) implements Decision {

        /** Never the address. */
        @Override
        public String toString() {
            return "Send[" + (shortForm ? "short" : "full") + "]";
        }
    }

    public record Wait(Instant until) implements Decision {
    }

    public record NotSent(StatusReason reason) implements Decision {
    }

    /**
     * What is known about a customer's message when it is its turn.
     *
     * @param contact           where to email the customer, if their token ever told us
     * @param laterDelivered    a later message of the same order says it was delivered or collected
     * @param laterShippedSent  a later "on its way" of the same order has been emailed
     * @param orderKnownPaid    notification has heard, from any order.paid, that the order is paid
     */
    public record ShopperFacts(String eventType, Optional<Contact> contact, boolean laterDelivered, boolean laterShippedSent, boolean orderKnownPaid) {
    }

    /**
     * The messages that only confirmed money stands behind: staff confirmed a payment before any of them can exist. To an address nobody
     * confirmed these go as the short note. Every other message (placed, cancelled, expired) is one a person can cause alone, for free, with
     * a made-up address, so to an unconfirmed address it is not emailed at all.
     */
    private static final Set<String> MONEY_STANDS_BEHIND_IT = Set.of("order.paid", "order.fulfilled", "order.shipped", "order.delivered",
            // Phase 12: only a PAID order is cancelled this way, only a dispatched one comes back, and a payout is the owner's own act.
            "order.sale_cancelled", "order.return_recorded", "payment.paid_out");

    /**
     * In this order:
     * <ol>
     *   <li>no mail server, or the owner paused all email: not sent (the queue drains; a paused message is never sent later);</li>
     *   <li>something later made it untrue or pointless: "ready to collect" once the order was handed over or a later "on its way" was emailed;
     *       "on its way" once it was delivered; "your order has ended" once the order turned out to be paid. "Payment received" and
     *       "delivered" are never held back;</li>
     *   <li>the customer's address is not known yet: look again later (the deadline ends the wait);</li>
     *   <li>the address was never confirmed: the short note for a money-backed message, nothing for the others;</li>
     *   <li>the stack is not production and the address could be a real mailbox: not sent.</li>
     * </ol>
     */
    public static Decision forShopper(ShopperFacts facts, EmailSetup setup, Instant now, Duration addressRecheck) {
        if (!setup.canSend()) {
            return new NotSent(StatusReason.SENDING_OFF);
        }
        if (superseded(facts)) {
            return new NotSent(StatusReason.SUPERSEDED);
        }
        if (facts.contact().isEmpty()) {
            return new Wait(now.plus(addressRecheck));
        }
        Contact contact = facts.contact().get();
        boolean shortForm = !contact.verified();
        if (shortForm && !MONEY_STANDS_BEHIND_IT.contains(facts.eventType())) {
            return new NotSent(StatusReason.UNVERIFIED_ADDRESS);
        }
        if (setup.mode() != EmailSetup.Mode.LIVE && !SandboxGate.mayLeaveASandbox(contact.email())) {
            return new NotSent(StatusReason.SANDBOX);
        }
        return new Send(contact.email(), shortForm);
    }

    /**
     * What is known about an email to a shop mailbox when it is its turn.
     *
     * @param notice        which notice of its alert it is: FIRST, AGAIN, REMINDER, SORTED, CLOSING (or DIGEST, which names several alerts)
     * @param alertResolved the alert it is about has ended
     * @param newerNotice   a newer first notice, re-telling or reminder of the same alert for the same mailbox has been written
     * @param onTheList     the row's mailbox is still on the list of its group, as configured now
     */
    public record StaffFacts(String notice, String staffTo, boolean alertResolved, boolean newerNotice, boolean onTheList) {
    }

    private static final Set<String> SAID_AFTER_THE_END = Set.of("SORTED", "CLOSING");
    private static final Set<String> ONLY_THE_NEWEST_GOES = Set.of("FIRST", "AGAIN", "REMINDER");

    /**
     * In this order: no mail server or all email paused; the alert has already ended (only "sorted" and the last note are said after the end);
     * a newer notice of the same alert has been written; no mailbox on the row, or a mailbox the owner has since taken off the list (an alert
     * written down for somebody who has left the shop must not still reach them); a mailbox that could be real on a stack that is not live.
     */
    public static Decision forStaff(StaffFacts facts, EmailSetup setup) {
        if (!setup.canSend()) {
            return new NotSent(StatusReason.SENDING_OFF);
        }
        if (facts.alertResolved() && !SAID_AFTER_THE_END.contains(facts.notice())) {
            return new NotSent(StatusReason.ALERT_RESOLVED);
        }
        if (facts.newerNotice() && ONLY_THE_NEWEST_GOES.contains(facts.notice())) {
            return new NotSent(StatusReason.SUPERSEDED);
        }
        if (facts.staffTo() == null || !facts.onTheList()) {
            return new NotSent(StatusReason.NO_RECIPIENT_CONFIGURED);
        }
        if (setup.mode() != EmailSetup.Mode.LIVE && !SandboxGate.mayLeaveASandbox(facts.staffTo())) {
            return new NotSent(StatusReason.SANDBOX);
        }
        return new Send(facts.staffTo(), false);
    }

    private static boolean superseded(ShopperFacts facts) {
        return switch (facts.eventType()) {
            case "order.fulfilled" -> facts.laterDelivered() || facts.laterShippedSent();
            case "order.shipped" -> facts.laterDelivered();
            case "order.expired" -> facts.orderKnownPaid();
            default -> false;
        };
    }
}
