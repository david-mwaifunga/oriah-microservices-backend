package com.oriah.events;

import java.util.UUID;
import java.util.function.BooleanSupplier;

import org.springframework.cloud.stream.function.StreamOperations;
import org.springframework.messaging.Message;
import org.springframework.util.MimeType;

/**
 * The real StreamBridge, except that the send of one chosen event fails --
 * by answering false (the binder refused it) or by throwing (the broker is
 * unreachable) -- until {@link #heal()} is called. Stands in for a broker
 * hiccup without mocking the relay itself.
 */
final class FlakyStreams implements StreamOperations {

    enum Failure { ANSWER_FALSE, THROW }

    private final StreamOperations real;
    private final String failingEventId;
    private final Failure failure;
    private volatile boolean healed;

    FlakyStreams(StreamOperations real, UUID failingEventId, Failure failure) {
        this.real = real;
        this.failingEventId = failingEventId.toString();
        this.failure = failure;
    }

    void heal() {
        healed = true;
    }

    @Override
    public boolean send(String bindingName, Object data) {
        return attempt(data, () -> real.send(bindingName, data));
    }

    @Override
    public boolean send(String bindingName, Object data, MimeType outputContentType) {
        return attempt(data, () -> real.send(bindingName, data, outputContentType));
    }

    @Override
    public boolean send(String bindingName, String binderName, Object data) {
        return attempt(data, () -> real.send(bindingName, binderName, data));
    }

    @Override
    public boolean send(String bindingName, String binderName, Object data, MimeType outputContentType) {
        return attempt(data, () -> real.send(bindingName, binderName, data, outputContentType));
    }

    private boolean attempt(Object data, BooleanSupplier realSend) {
        if (!healed && data instanceof Message<?> message
                && failingEventId.equals(String.valueOf(message.getHeaders().get("event-id")))) {
            if (failure == Failure.THROW) {
                throw new IllegalStateException("simulated broker failure");
            }
            return false;
        }
        return realSend.getAsBoolean();
    }
}
