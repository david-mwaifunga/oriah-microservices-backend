package com.oriah.events.latecomer;

import java.nio.charset.StandardCharsets;

import org.springframework.messaging.Message;

/** One late consumer: the same effect, reached from the live topic or from its replay. */
public interface ProbeConsumer {

    /** A delivery from the producer's live topic: straight through the Inbox. */
    void live(Message<byte[]> message);

    /** A delivery from {@code oriah.replay.<consumer>}: through CatchUp, which uses the same Inbox method. */
    void replay(Message<byte[]> message);

    static String header(Message<?> message, String name) {
        Object value = message.getHeaders().get(name);
        return value instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8)
                : value == null ? null : value.toString();
    }
}
