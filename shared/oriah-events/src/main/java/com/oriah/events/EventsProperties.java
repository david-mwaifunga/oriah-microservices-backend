package com.oriah.events;

import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code oriah.events.*} -- what a service switches on, and in whose name.
 *
 * <p>The relay's schedule, {@code oriah.events.relay.delay} (milliseconds,
 * default 500), is read straight by its {@code @Scheduled} annotation and so
 * is not bound here.
 *
 * @param producer this service's name ("order", "payment"); sent with every
 *                 message as the {@code producer} header
 * @param topics   the only topics this service may publish to; an append to
 *                 any other is refused, so a typo fails loudly instead of
 *                 publishing to a topic nobody reads
 * @param inbox    the consumer side: the Inbox, and the CatchUp helper for a
 *                 consumer that starts after its producers
 */
@ConfigurationProperties("oriah.events")
public record EventsProperties(
        String producer,
        @DefaultValue Set<String> topics,
        @DefaultValue Switch outbox,
        @DefaultValue Relay relay,
        @DefaultValue Replay replay,
        @DefaultValue InboxSwitch inbox) {

    public record Switch(boolean enabled) {
    }

    /**
     * @param chunkSize the most outbox rows one replay step sends
     *                  ({@code oriah.events.replay.chunk-size}, default 10,
     *                  1 or more). A replay is answered one step per Kafka
     *                  delivery, and each step must finish inside the time
     *                  Kafka allows the listener between two polls, however
     *                  slowly the broker answers -- see {@link ReplayStepTime}.
     *                  Small on purpose: a long history just takes more
     *                  steps.
     */
    public record Replay(boolean enabled, @DefaultValue("10") int chunkSize) {

        public Replay {
            // Refused at startup: a step that sends no row would hand the
            // same position on to the next step, for ever.
            if (chunkSize < 1) {
                throw new IllegalArgumentException("oriah.events.replay.chunk-size must be 1 or more, was "
                        + chunkSize);
            }
        }
    }

    /**
     * @param topics  the topics this service reads through the Inbox
     *                ({@code oriah.events.inbox.topics}). Required when the
     *                Inbox is on: the bindings that read them are the ones
     *                that get the module's dead-letter handling (see
     *                {@link DeadLetterScope}); every other binding keeps the
     *                binder's own.
     * @param catchUp {@code oriah.events.inbox.catch-up.*}; naming a consumer there switches CatchUp on
     */
    public record InboxSwitch(boolean enabled, @DefaultValue Set<String> topics,
                              @DefaultValue CatchUpSettings catchUp) {
    }

    /**
     * @param consumer  the consumer that catches up: its Inbox name, and the
     *                  suffix of the topic its replays arrive on
     *                  ({@code oriah.replay.<consumer>}). One per service:
     *                  outbox_catch_up keeps one row per producer.
     * @param producers the services it asks for a replay, by their
     *                  {@code oriah.events.producer} name
     */
    public record CatchUpSettings(String consumer, @DefaultValue Set<String> producers) {
    }

    /**
     * @param batchSize the most rows one relay round publishes; 1 or more. (A
     *                  replay steps by its own {@link Replay#chunkSize()}.)
     */
    public record Relay(boolean enabled, @DefaultValue("100") int batchSize) {

        public Relay {
            // Refused at startup: at 0 the relay would publish nothing, with
            // no error.
            if (batchSize < 1) {
                throw new IllegalArgumentException("oriah.events.relay.batch-size must be 1 or more, was "
                        + batchSize);
            }
        }
    }
}
