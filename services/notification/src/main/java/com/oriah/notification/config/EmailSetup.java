package com.oriah.notification.config;

import java.util.List;

import com.oriah.notification.delivery.SmtpSettings;

/**
 * How this service sends email, decided ONCE when it starts ({@link EmailModeGuard}) and never changed while it runs: nothing that refreshes
 * settings can move a running service from the catcher to a real mail server, or change who is told.
 *
 * @param smtp            the one mail server; null when the mode is OFF
 * @param moneyRecipients the mailboxes told about payments and sales
 * @param costRecipients  the mailboxes told about prices below cost (people the owner allows to see costs)
 * @param supportLine     who to turn to when an alert says the system needs attention; null when the owner has not said
 * @param paused          the owner's switch that stops all email
 */
public record EmailSetup(Mode mode, SmtpSettings smtp, List<String> moneyRecipients, List<String> costRecipients, String supportLine,
                         boolean paused) {

    public enum Mode {
        /** No mail server at all: every message that would be emailed is marked "not sent, sending off". */
        OFF,
        /** Not production: the local mail catcher only, and only addresses that can never be a real mailbox. */
        CATCHER,
        /** Production: the shop's real mail account. */
        LIVE
    }

    public EmailSetup {
        moneyRecipients = List.copyOf(moneyRecipients);
        costRecipients = List.copyOf(costRecipients);
    }

    /** True when a message may be handed to the mail server right now. */
    public boolean canSend() {
        return mode != Mode.OFF && !paused;
    }

    /** Never an address or a password: this is what a log line would print. */
    @Override
    public String toString() {
        return "EmailSetup[" + mode + (paused ? ", paused" : "") + ", " + moneyRecipients.size() + " money and " + costRecipients.size()
                + " cost alert mailboxes]";
    }
}
