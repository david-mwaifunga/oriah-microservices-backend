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
 *         .handles("product", "product.updated", 1)   // about a product; reads payload version 1
 *         .handles("product", "product.deleted", 1)
 *         .ignores("product.reviewed");               // marks it applied, does nothing
 * }</pre>
 *
 * <p>An event of any other type, of a payload version the consumer has not
 * said it can read, or about another aggregate than the one declared for its
 * type (see {@link #handles} for why that matters), is refused when it
 * arrives -- before the "applied" marker is written (an
 * IllegalArgumentException). The module's dead-letter handling then
 * publishes it to the binding's dead-letter topic -- on its
 * first delivery when the binding lists IllegalArgumentException as not
 * retryable, as the services do -- at least once, and the record is not
 * committed until that topic has it, so it is not dropped while Kafka keeps
 * it (see {@link DeadLetterCustomizer}). The event itself stays in the
 * producer's outbox for good, so a later version of the consumer that does
 * read it can still apply it from a replay. Silently skipping unknown types
 * would lose them for good.
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
    private final Map<String, Handled> handled;
    private final Set<String> ignored;

    private InboxConsumer(String name, Map<String, Handled> handled, Set<String> ignored) {
        this.name = name;
        this.handled = Map.copyOf(handled);
        this.ignored = Set.copyOf(ignored);
    }

    public static InboxConsumer named(String name) {
        return new InboxConsumer(ConsumerName.require(name), Map.of(), Set.of());
    }

    /**
     * Applies events of this type, which are about this kind of aggregate, in
     * any of these payload versions (at least one).
     *
     * <p>Why the aggregate is declared: {@link Inbox#onceInOrder} keeps the
     * newest seq it has applied per (consumer, aggregate type, aggregate id),
     * and seq counts 1, 2, 3 separately inside each aggregate. A message of a
     * type the consumer handles but with the wrong aggregate-type header would
     * have its snapshot applied while its seq was recorded under some other
     * aggregate. The real aggregate's own seq would stay behind, so a later
     * genuine snapshot with a lower seq would no longer be recognised as
     * older, and would overwrite the newer state. So an event whose
     * aggregate-type is not the one declared here is refused when it arrives,
     * before anything is recorded -- by {@link Inbox#once} too, whose effect
     * reads the aggregate-id as the id of this kind of thing.
     *
     * <p>Why it is declared rather than worked out from the event type's name:
     * the two need not match. Payment's events are {@code payment.*}, but
     * the thing they are about is a {@code payment_intent}.
     *
     * <p>The aggregate type follows the outbox's own rule (present, at most 64
     * characters; see {@link EventDraft}). One event type is about one kind of
     * aggregate: declaring the type again for the same aggregate adds payload
     * versions; declaring it for a different one is refused.
     */
    public InboxConsumer handles(String aggregateType, String eventType, int... schemaVersions) {
        requireType(eventType);
        requireAggregateType(aggregateType, eventType);
        if (schemaVersions.length == 0 || Arrays.stream(schemaVersions).anyMatch(version -> version < 1)) {
            throw new IllegalArgumentException("handles(\"" + eventType + "\") needs the payload versions it can "
                    + "read, each 1 or more, was " + Arrays.toString(schemaVersions));
        }
        if (ignored.contains(eventType)) {
            throw new IllegalArgumentException(eventType + " is already declared ignored; it cannot also be handled");
        }
        Handled before = handled.get(eventType);
        if (before != null && !before.aggregateType().equals(aggregateType)) {
            throw new IllegalArgumentException(eventType + " is already declared as about aggregate '"
                    + before.aggregateType() + "'; it cannot also be about '" + aggregateType
                    + "': one event type is about one kind of thing");
        }
        Set<Integer> versions = new HashSet<>(before == null ? Set.of() : before.versions());
        Arrays.stream(schemaVersions).forEach(versions::add);
        Map<String, Handled> more = new HashMap<>(handled);
        more.put(eventType, new Handled(aggregateType, Set.copyOf(versions)));
        return new InboxConsumer(name, more, ignored);
    }

    /**
     * Marks events of these types applied without doing anything. No
     * aggregate is declared: an ignored event is never applied, so it never
     * records a seq, under any aggregate.
     */
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
     * @throws IllegalArgumentException for a type or payload version this consumer did not declare, or an
     *                                  aggregate-type that is not the one declared for the event's type
     */
    boolean applies(Envelope envelope) {
        if (ignored.contains(envelope.eventType())) {
            return false;
        }
        Handled declared = handled.get(envelope.eventType());
        if (declared == null) {
            throw new IllegalArgumentException("consumer '" + name + "' does not declare event type '"
                    + envelope.eventType() + "' (event " + envelope.eventId() + "); declare it handled or ignored");
        }
        if (!declared.aggregateType().equals(envelope.aggregateType())) {
            throw new IllegalArgumentException("consumer '" + name + "' declares " + envelope.eventType()
                    + " as about aggregate '" + declared.aggregateType() + "', but event " + envelope.eventId()
                    + " has aggregate-type '" + envelope.aggregateType() + "'; refused before anything is recorded, "
                    + "so it cannot take a place in another aggregate's history");
        }
        if (!declared.versions().contains(envelope.schemaVersion())) {
            throw new IllegalArgumentException("consumer '" + name + "' reads " + envelope.eventType()
                    + " in payload versions " + declared.versions() + ", not " + envelope.schemaVersion()
                    + " (event " + envelope.eventId() + ")");
        }
        return true;
    }

    private static void requireType(String eventType) {
        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("an event type is required");
        }
    }

    private static void requireAggregateType(String aggregateType, String eventType) {
        try {
            EventDraft.requireAggregateType(aggregateType);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("handles(\"" + eventType + "\") needs the aggregate type its events "
                    + "are about, exactly as the producer writes it (e.g. \"product\"): " + e.getMessage(), e);
        }
    }

    /** What the consumer declared for one handled event type: the aggregate it is about, and the versions it reads. */
    private record Handled(String aggregateType, Set<Integer> versions) {
    }
}
