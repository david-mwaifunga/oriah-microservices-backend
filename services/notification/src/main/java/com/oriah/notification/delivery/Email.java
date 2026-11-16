package com.oriah.notification.delivery;

import java.util.UUID;

/**
 * One email to hand to a mail server: who it is for and what it says. The sender's own address is the transport's, not the message's.
 *
 * @param rowId the message row this email is for; its Message-ID is built from it, so two tries of one row carry the same id
 */
public record Email(UUID rowId, String to, String subject, String body) {

    /** Never the address or the words: this is what a log line or an exception would print. */
    @Override
    public String toString() {
        return "Email[row " + rowId + "]";
    }
}
