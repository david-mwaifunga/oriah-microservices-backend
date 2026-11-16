package com.oriah.notification.delivery;

/**
 * The one door out of the service for a message. An implementation never throws and never holds its caller longer than a fixed time.
 */
public interface MessageTransport {

    /** Hands one email to the mail server and says what became of it. */
    SendResult send(Email email);

    /** Connects, signs in and leaves without sending anything: is the mail server usable right now? */
    SendResult probe();
}
