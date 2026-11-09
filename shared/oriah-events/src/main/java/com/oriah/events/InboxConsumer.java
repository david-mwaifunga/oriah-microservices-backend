package com.oriah.events;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Who a consumer is and which events it reads, declared once:
 *
 * <pre>{@code
 * static final InboxConsumer SEARCH = InboxConsumer.named("search")
 *         .handles("product.updated", 1)      // applies it; reads payload version 1
 *         .handles("product.deleted", 1)
 *         .ignores("product.reviewed");       // marks it applied, does nothing
 * }</pre>
 *
 * <p>An event of any other type, or of a payload version the consumer has
 * not said it can read, is refused when it arrives -- before the "applied"
 * marker is written. It is retried and then lands on the dead-letter topic,
 * so it is never lost: a later version of the consumer that does read it can
 * still apply it from a replay. Silently skipping unknown types would lose
 * them for good.
 *
 * <p>The name is what the Inbox records against each event it applies
 * (processed_event.consumer, 64 characters) and the suffix of the consumer's
 * replay topic ({@code oriah.replay.<name>}), so it follows
 * {@link ConsumerName}'s rule: lower-case letters, digits and dashes.
 *
 * <p>Immutable: each call returns a new declaration, so one can be shared
 * freely between threads.
 */
public final class InboxConsumer {

    private final String name;
    private final Map<String, Set<Integer>> handled;
    private final Set<String> ignored;

    private InboxConsumer(String name, Map<String, Set<Integer>> handled, Set<String> ignored) {
        this.name = name;
        this.handled = Map.copyOf(handled);
        this.ignored = Set.copyOf(ignored);
    }

    public static InboxConsumer named(String name) {
        return new InboxConsumer(ConsumerName.require(name), Map.of(), Set.of());
    }

    /** Applies events of this type, in any of these payload versions (at least one). */
    public InboxConsumer handles(String eventType, int... schemaVersions) {
        requireType(eventType);
        if (schemaVersions.length == 0 || Arrays.stream(schemaVersions).anyMatch(version -> version < 1)) {
            throw new IllegalArgumentException("handles(\"" + eventType + "\") needs the payload versions it can "
                    + "read, each 1 or more, was " + Arrays.toString(schemaVersions));
        }
        if (ignored.contains(eventType)) {
            throw new IllegalArgumentException(eventType + " is already declared ignored; it cannot also be handled");
        }
        Map<String, Set<Integer>> more = new HashMap<>(handled);
        Set<Integer> versions = new HashSet<>(handled.getOrDefault(eventType, Set.of()));
        Arrays.stream(schemaVersions).forEach(versions::add);
        more.put(eventType, Set.copyOf(versions));
        return new InboxConsumer(name, more, ignored);
    }

    /** Marks events of these types applied without doing anything. */
    public InboxConsumer ignores(String... eventTypes) {
        Set<String> more = new HashSet<>(ignored);
        for (String eventType : eventTypes) {
            requireType(eventType);
            if (handled.containsKey(eventType)) {
                throw new IllegalArgumentException(eventType + " is already declared handled; it cannot also be ignored");
            }
            more.add(eventType);
        }
        return new InboxConsumer(name, handled, more);
    }

    public String name() {
        return name;
    }

    /**
     * @return true to apply the event, false to mark it without an effect
     * @throws IllegalArgumentException for a type or payload version this consumer did not declare
     */
    boolean applies(Envelope envelope) {
        if (ignored.contains(envelope.eventType())) {
            return false;
        }
        Set<Integer> versions = handled.get(envelope.eventType());
        if (versions == null) {
            throw new IllegalArgumentException("consumer '" + name + "' does not declare event type '"
                    + envelope.eventType() + "' (event " + envelope.eventId() + "); declare it handled or ignored");
        }
        if (!versions.contains(envelope.schemaVersion())) {
            throw new IllegalArgumentException("consumer '" + name + "' reads " + envelope.eventType()
                    + " in payload versions " + versions + ", not " + envelope.schemaVersion()
                    + " (event " + envelope.eventId() + ")");
        }
        return true;
    }

    private static void requireType(String eventType) {
        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("an event type is required");
        }
    }
}
