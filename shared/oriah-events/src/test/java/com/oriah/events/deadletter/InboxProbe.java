package com.oriah.events.deadletter;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.oriah.events.Inbox;
import com.oriah.events.InboxConsumer;

/**
 * The probe's Inbox consumer. It applies {@code probe.happened}; its header
 * {@code probe-behaviour} says how the effect behaves:
 * <ul>
 *   <li>none -- applied;</li>
 *   <li>{@code fail-always} -- the effect throws an IllegalStateException
 *       every time (retried, then dead-lettered);</li>
 *   <li>{@code fail-once} -- throws on the first delivery only, then is
 *       applied.</li>
 * </ul>
 * An event of any other type is refused by the Inbox itself, with an
 * IllegalArgumentException, before anything is recorded.
 */
@Component
public class InboxProbe {

    static final InboxConsumer PROBE = InboxConsumer.named("dead-letter-probe").handles("probe", "probe.happened", 1);

    private final Inbox inbox;
    private final Deliveries deliveries;
    private final Set<String> failedOnce = ConcurrentHashMap.newKeySet();
    private final List<String> applied = new CopyOnWriteArrayList<>();

    InboxProbe(Inbox inbox, Deliveries deliveries) {
        this.inbox = inbox;
        this.deliveries = deliveries;
    }

    @Transactional
    public void receive(Message<byte[]> message) {
        deliveries.record(message);
        inbox.once(PROBE, message.getHeaders(), () -> apply(message));
    }

    private void apply(Message<byte[]> message) {
        String eventId = header(message, "event-id");
        String behaviour = header(message, "probe-behaviour");
        if ("fail-always".equals(behaviour)) {
            throw new IllegalStateException("the probe can never apply event " + eventId);
        }
        if ("fail-once".equals(behaviour) && failedOnce.add(eventId)) {
            throw new IllegalStateException("the probe fails event " + eventId + " this once");
        }
        applied.add(eventId);
    }

    /** The event ids whose effect ran and committed, one entry per run. */
    public List<String> applied() {
        return List.copyOf(applied);
    }

    private static String header(Message<byte[]> message, String name) {
        Object value = message.getHeaders().get(name);
        return value instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8)
                : value == null ? null : value.toString();
    }
}
