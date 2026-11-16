package com.oriah.notification.delivery;

/**
 * What became of one try to hand a message to a mail server. The outcome says what to do next; the code says, in a word, where it went wrong
 * (kept on the row as {@code last_error}). A code is one of the fixed words below, never text a server sent: a server's reply can quote the address.
 */
public record SendResult(Outcome outcome, String code) {

    public enum Outcome {
        /** The mail server took the message (250 after the body). */
        ACCEPTED,
        /** This address is refused for good. */
        REFUSED,
        /** This mailbox cannot take mail just now; the server itself is fine. */
        RECIPIENT_TRY_LATER,
        /** The server could not be used. Nothing is wrong with this message, and the next one would fail the same way. */
        SERVER_TRY_LATER,
        /** The body was handed over and no answer came: it may have arrived. */
        UNCERTAIN
    }

    public static final String CONNECT = "CONNECT";
    public static final String TIMEOUT = "TIMEOUT";
    public static final String TLS = "TLS";
    public static final String AUTH = "AUTH";
    public static final String SMTP_4XX = "SMTP_4XX";
    public static final String SMTP_5XX = "SMTP_5XX";
    public static final String RCPT_4XX = "RCPT_4XX";
    public static final String AFTER_DATA = "AFTER_DATA";
    public static final String OTHER = "OTHER";
    public static final String ADDRESS_REFUSED = "ADDRESS_REFUSED";
    public static final String SANDBOX = "SANDBOX";

    public static SendResult accepted() {
        return new SendResult(Outcome.ACCEPTED, null);
    }

    public static SendResult refused(String code) {
        return new SendResult(Outcome.REFUSED, code);
    }

    public static SendResult recipientTryLater() {
        return new SendResult(Outcome.RECIPIENT_TRY_LATER, RCPT_4XX);
    }

    public static SendResult serverTryLater(String code) {
        return new SendResult(Outcome.SERVER_TRY_LATER, code);
    }

    public static SendResult uncertain() {
        return new SendResult(Outcome.UNCERTAIN, AFTER_DATA);
    }
}
