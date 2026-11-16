package com.oriah.notification.model;

/** Why a message was not sent, or why sending it failed. Kept on the row as text; the database holds the same list. */
public enum StatusReason {
    /** Written before Phase 11, when sending was a log line. Never emailed. */
    RECORDED_BEFORE_SENDING,
    /** This kind of message is shown on the website only. */
    BELL_ONLY,
    /** No mail server is configured, or the owner has paused all email. */
    SENDING_OFF,
    /** Too old to be worth sending by the time it could have gone. */
    TOO_LATE,
    /** A dispatch step keyed long after it happened: the customer already knows. */
    BACKDATED_STEP,
    /** Something later made it untrue or pointless (an order that was paid after all, a parcel already delivered). */
    SUPERSEDED,
    /** The customer's address was never learnt. */
    NO_ADDRESS,
    /** The customer's address was never confirmed, and this message is one a stranger could cause. */
    UNVERIFIED_ADDRESS,
    /** The stack is not production and the address could be a real mailbox. */
    SANDBOX,
    /** An alert with no mailbox configured for its group. */
    NO_RECIPIENT_CONFIGURED,
    /** The alert was resolved before its email went. */
    ALERT_RESOLVED,
    /** Too many payment claims in a day to email each one. */
    DAILY_LIMIT,
    /** FAILED: tried until its deadline. */
    GAVE_UP,
    /** FAILED: the mail server refused the address for good. */
    ADDRESS_REFUSED,
    /** FAILED: handed over and no answer came. It may have arrived. */
    OUTCOME_UNKNOWN
}
