package com.oriah.notification.model;

/** Who a message is for. */
public enum RecipientKind {
    /** A customer with an account: the row has its owner and shows in their bell. */
    SHOPPER,
    /** A customer who ordered by phone and has no account: words for staff to send by hand. */
    CALLER,
    /** A shop mailbox: an alert. Never in anyone's bell. */
    STAFF
}
