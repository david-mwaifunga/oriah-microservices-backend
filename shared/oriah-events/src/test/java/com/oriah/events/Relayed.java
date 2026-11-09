package com.oriah.events;

import java.util.ArrayList;
import java.util.List;

import org.springframework.cloud.stream.binder.test.OutputDestination;
import org.springframework.messaging.Message;

/** Helpers for reading what the relay published to the test binder. */
final class Relayed {

    private Relayed() {
    }

    /**
     * Publishes whatever earlier tests left behind and throws it away, so a
     * test sees only its own messages. (The outbox is append-only, so it
     * cannot simply be emptied.)
     */
    static void drain(OutboxRelay relay, OutputDestination output) {
        while (relay.relayBatch() > 0) {
            // keep going until the backlog is published
        }
        output.clear();
    }

    /** Every message waiting on one destination, in the order it was sent. */
    static List<Message<byte[]>> all(OutputDestination output, String topic) {
        List<Message<byte[]>> messages = new ArrayList<>();
        Message<byte[]> message;
        while ((message = output.receive(200, topic)) != null) {
            messages.add(message);
        }
        return messages;
    }

    static String header(Message<?> message, String name) {
        Object value = message.getHeaders().get(name);
        return value == null ? null : value.toString();
    }

    static List<String> headers(List<Message<byte[]>> messages, String name) {
        return messages.stream().map(message -> header(message, name)).toList();
    }
}
