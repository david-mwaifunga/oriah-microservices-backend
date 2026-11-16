package com.oriah.notification.delivery;

import java.time.Duration;

/**
 * Everything the SMTP transport needs to reach one mail server, fixed when the service starts.
 *
 * @param security       how the connection is protected; NONE is the local mail catcher only
 * @param username       the sending account; null for the catcher, which has no sign-in
 * @param fromAddress    one bare address
 * @param fromName       the name shown beside it
 * @param replyTo        where a reply should go; null when replies go to the From address
 * @param connectTimeout how long to wait for the connection
 * @param readTimeout    how long to wait for EACH reply (not for the whole dialogue)
 * @param writeTimeout   how long one write may block
 * @param maxSend        the one wall-clock limit on a whole send, whatever the server does
 */
public record SmtpSettings(String host, int port, Security security, String username, String password, String fromAddress,
                           String fromName, String replyTo, Duration connectTimeout, Duration readTimeout, Duration writeTimeout,
                           Duration maxSend) {

    public enum Security {
        /** No encryption: the local mail catcher only. */
        NONE,
        /** Plain connection upgraded with STARTTLS (port 587); refused if the server does not offer it. */
        STARTTLS,
        /** Encrypted from the first byte (port 465). */
        TLS
    }

    /** Never the password. */
    @Override
    public String toString() {
        return "SmtpSettings[" + host + ":" + port + " " + security + "]";
    }
}
