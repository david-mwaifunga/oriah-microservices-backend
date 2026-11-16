package com.oriah.notification.contact;

import java.util.Locale;
import java.util.Optional;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;

/**
 * What counts as an address the shop may write to: exactly ONE, bare ({@code name@domain.tld}). Used wherever an address enters the service (a
 * shopper's sign-in token, an alert list, the From address) and once more by the mail transport just before it hands a message over.
 *
 * <p>Stricter than the mail standard on purpose. A display name, angle brackets, a comma, a semicolon, a space or a line break are all refused,
 * because each is a way for one "address" to become two recipients or to add a header to the message. A domain needs a dot and may not end in one.
 */
public final class EmailAddressRule {

    /** The longest address a mail server must accept (RFC 5321). */
    private static final int LONGEST = 254;
    private static final String NEVER_IN_A_BARE_ADDRESS = ",;<>()\"\\ \t\r\n";

    private EmailAddressRule() {
    }

    /** @return the address with the spaces around it taken off, when the text is exactly one bare address; empty otherwise */
    public static Optional<String> oneAddress(String text) {
        if (text == null) {
            return Optional.empty();
        }
        String address = text.trim();
        if (address.isEmpty() || address.length() > LONGEST || address.chars().anyMatch(c -> NEVER_IN_A_BARE_ADDRESS.indexOf(c) >= 0)) {
            return Optional.empty();
        }
        int at = address.indexOf('@');
        if (at <= 0 || at != address.lastIndexOf('@')) {
            return Optional.empty();
        }
        String domain = address.substring(at + 1);
        if (!domain.contains(".") || domain.startsWith(".") || domain.endsWith(".")) {
            return Optional.empty();
        }
        try {
            new InternetAddress(address, true);
        } catch (AddressException e) {
            return Optional.empty();
        }
        return Optional.of(address);
    }

    /** The part after the {@code @}, in small letters: domains do not tell capitals apart. */
    public static String domainOf(String address) {
        return address.substring(address.lastIndexOf('@') + 1).toLowerCase(Locale.ROOT);
    }
}
