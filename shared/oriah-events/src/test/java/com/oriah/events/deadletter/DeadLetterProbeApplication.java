package com.oriah.events.deadletter;

import java.util.function.Consumer;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.messaging.Message;

/**
 * A stand-in consumer service for {@code DeadLetterKafkaTest}, run on the
 * real Kafka binder. Two bindings, as a real service might have:
 * <ul>
 *   <li>{@code inboxEvents} reads its topic through the Inbox -- one of the
 *       module's bindings, so it gets the module's dead-letter handling;</li>
 *   <li>{@code plainEvents} is a binding of the service's own, not through
 *       the Inbox, which must keep the binder's behaviour untouched. It
 *       fails every message.</li>
 * </ul>
 * Its own package, so its component scan sees only the probe's beans and
 * everything of the module arrives through auto-configuration.
 */
@SpringBootApplication
public class DeadLetterProbeApplication {

    @Bean
    Consumer<Message<byte[]>> inboxEvents(InboxProbe probe) {
        return probe::receive;
    }

    @Bean
    Consumer<Message<byte[]>> plainEvents(Deliveries deliveries) {
        return message -> {
            deliveries.record(message);
            throw new IllegalStateException("the plain probe fails every message");
        };
    }
}
