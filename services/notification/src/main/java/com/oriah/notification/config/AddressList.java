package com.oriah.notification.config;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.oriah.notification.contact.EmailAddressRule;

/**
 * The one reader of a list of alert mailboxes as the owner gives it: addresses on one line with commas between
 * ({@code ORIAH_ALERT_EMAILS_MONEY}, {@code ORIAH_ALERT_EMAILS_COST}).
 *
 * <p>Read once, when the service starts. Spaces and empty places are dropped; a domain is put in small letters; an address given twice is kept
 * once (two rows for one mailbox would be one alert emailed twice). An entry that is not exactly one address stops the start, and the message says
 * which variable and which place in the list, never the text itself: a failed start is printed in a log, and a person's address does not belong
 * there.
 */
public final class AddressList {

    private AddressList() {
    }

    /**
     * @param variable       the name of the setting, for the message of a refusal
     * @param commaSeparated the list as given; null or blank is an empty list
     * @return the distinct addresses, in the order given
     * @throws IllegalArgumentException when an entry is not exactly one address
     */
    public static List<String> parse(String variable, String commaSeparated) {
        Set<String> addresses = new LinkedHashSet<>();
        if (commaSeparated == null) {
            return List.of();
        }
        String[] entries = commaSeparated.split(",", -1);
        int place = 0;
        for (String entry : entries) {
            if (entry.isBlank()) {
                continue;
            }
            place++;
            String address = EmailAddressRule.oneAddress(entry).orElse(null);
            if (address == null) {
                throw new IllegalArgumentException(variable + ": entry " + place + " is not exactly one email address");
            }
            int at = address.lastIndexOf('@');
            addresses.add(address.substring(0, at + 1) + EmailAddressRule.domainOf(address));
        }
        return List.copyOf(new ArrayList<>(addresses));
    }
}
