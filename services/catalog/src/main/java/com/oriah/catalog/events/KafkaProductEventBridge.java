package com.oriah.catalog.events;

import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import lombok.extern.slf4j.Slf4j;

/**
 * Forwards in-process {@link ProductCreatedEvent} / {@link ProductUpdatedEvent}
 * / {@link ProductDeletedEvent} to the Kafka topic configured for the
 * {@code productEvents-out-0} binding.
 *
 * <p>Each handler is annotated with
 * {@link TransactionalEventListener}{@code (phase = AFTER_COMMIT)}, which
 * means:
 * <ul>
 *   <li>If the transaction in {@link com.oriah.catalog.service.ProductService}
 *       <b>commits</b>, the handler runs and Kafka receives the event.</li>
 *   <li>If the transaction <b>rolls back</b>, the handler does NOT run —
 *       no stale event leaks to consumers.</li>
 * </ul>
 *
 * <p>This is the simplest correct pattern for "publish after commit." It
 * still has the dual-write hole: the JVM can crash <i>between</i> commit
 * and Kafka send, so we lose the event in that window. A future polish
 * cycle adds a transactional outbox + relay for true at-least-once
 * delivery.
 *
 * <p>An {@code event-type} message header is attached to each message so
 * a single consumer subscribing to {@code catalog.product} can route by
 * type without parsing the payload.
 */
@Component
@Slf4j
public class KafkaProductEventBridge {

    /**
     * Binding name from {@code application.yaml} → maps to the
     * {@code catalog.product} topic via
     * {@code spring.cloud.stream.bindings.productEvents-out-0.destination}.
     */
    private static final String BINDING = "productEvents-out-0";
    private static final String HEADER_EVENT_TYPE = "event-type";

    private final StreamBridge streamBridge;

    public KafkaProductEventBridge(StreamBridge streamBridge) {
        this.streamBridge = streamBridge;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCreated(ProductCreatedEvent event) {
        forward(event, ProductCreatedEvent.TYPE);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onUpdated(ProductUpdatedEvent event) {
        forward(event, ProductUpdatedEvent.TYPE);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDeleted(ProductDeletedEvent event) {
        forward(event, ProductDeletedEvent.TYPE);
    }

    private void forward(Object payload, String type) {
        Message<Object> message = MessageBuilder.withPayload(payload)
                .setHeader(HEADER_EVENT_TYPE, type)
                .build();
        boolean sent = streamBridge.send(BINDING, message);
        if (sent) {
            log.info("Published {} payload={}", type, payload);
        } else {
            // StreamBridge returns false when the binder couldn't accept the
            // message (broker down, throttled, etc.). We log at WARN so it
            // shows up in dashboards but don't throw — the @TransactionalEventListener
            // contract is fire-and-forget; throwing here would only kill the
            // listener thread and not affect the already-committed DB write.
            log.warn("StreamBridge rejected {} payload={}", type, payload);
        }
    }
}
