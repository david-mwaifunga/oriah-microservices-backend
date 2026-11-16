package com.oriah.notification.model;

/** What became of a message. Kept on the row as text; the database holds the same list. */
public enum MessageStatus {
    /** Written down and waiting to be sent. */
    RECORDED,
    /** A mail server took it. Whether it reached the mailbox is never known. */
    SENT,
    /** Tried and given up, the address refused, or handed over with no answer (it may have arrived). */
    FAILED,
    /** Deliberately not sent; the reason says why. It is still shown on the website. */
    NOT_SENT,
    /** Words for a person to send to a caller; the system sends nothing. */
    BY_HAND
}
