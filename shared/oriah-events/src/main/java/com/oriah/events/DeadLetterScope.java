package com.oriah.events;

import java.util.LinkedHashSet;
import java.util.Set;

import org.springframework.util.StringUtils;

/**
 * Which Kafka bindings get the module's dead-letter handling: only those
 * whose topic is one of the module's own. Every other binding of the
 * service keeps the Kafka binder's behaviour, untouched (one with both
 * batch-mode and enable-dlq is refused instead: see
 * {@link DeadLetterStartupCheck#BATCH_DEAD_LETTERING}).
 *
 * <p>The module's topics are:
 * <ul>
 *   <li>the topics the service reads through the {@link Inbox}, listed in
 *       {@code oriah.events.inbox.topics} -- when the Inbox is on;</li>
 *   <li>the consumer's own replay topic, {@code oriah.replay.<consumer>} --
 *       when {@link CatchUp} is on;</li>
 *   <li>{@value ReplayRequest#TOPIC} -- when replay is on, because the
 *       module binds that listener itself.</li>
 * </ul>
 *
 * <p>Why not every binding: a service may have a binding of its own that
 * reads something else, in some other way (say, with its own decoding); the
 * module's handler re-publishes raw bytes and must not be forced on it.
 *
 * @param inboxTopics        the Inbox's topics (empty when the Inbox is off)
 * @param catchUpTopic       {@code oriah.replay.<consumer>}, or null when CatchUp is off
 * @param replayRequestTopic {@value ReplayRequest#TOPIC}, or null when replay is off
 */
record DeadLetterScope(Set<String> inboxTopics, String catchUpTopic, String replayRequestTopic) {

    static DeadLetterScope of(EventsProperties properties) {
        EventsProperties.InboxSwitch inbox = properties.inbox();
        boolean catchUp = inbox.enabled() && StringUtils.hasText(inbox.catchUp().consumer());
        return new DeadLetterScope(
                inbox.enabled() ? Set.copyOf(inbox.topics()) : Set.of(),
                catchUp ? "oriah.replay." + inbox.catchUp().consumer() : null,
                properties.replay().enabled() ? ReplayRequest.TOPIC : null);
    }

    /** Every topic whose binding gets the module's dead-letter handling. */
    Set<String> topics() {
        Set<String> topics = new LinkedHashSet<>(inboxTopics);
        if (catchUpTopic != null) {
            topics.add(catchUpTopic);
        }
        if (replayRequestTopic != null) {
            topics.add(replayRequestTopic);
        }
        return topics;
    }

    boolean covers(String destination) {
        return topics().contains(destination);
    }
}
