package com.oriah.events;

import java.util.regex.Pattern;

/**
 * The one rule for a consumer's name: 1 to 64 lower-case letters, digits or
 * dashes. The name is stored in processed_event.consumer (64 characters)
 * and is the suffix of the consumer's replay topic,
 * {@code oriah.replay.<name>} -- where a dot or a capital would make a
 * different topic, and a slash or a space an invalid one.
 */
final class ConsumerName {

    private static final Pattern NAME = Pattern.compile("[a-z0-9-]{1,64}");

    private ConsumerName() {
    }

    static String require(String name) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("consumer name must be 1 to 64 lower-case letters, digits or dashes, "
                    + "was '" + name + "'");
        }
        return name;
    }
}
