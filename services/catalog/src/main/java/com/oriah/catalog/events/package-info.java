/**
 * Catalog domain events — what catalog tells the rest of the system has
 * happened.
 *
 * <p>Each event is a Java {@code record} (immutable, equals-by-value,
 * JSON-serializable by default via Jackson). Records are published via
 * {@link org.springframework.context.ApplicationEventPublisher} from
 * {@link com.oriah.catalog.service.ProductService} inside the transaction;
 * a separate {@link com.oriah.catalog.events.KafkaProductEventBridge}
 * listener picks them up on {@code AFTER_COMMIT} and forwards to Kafka via
 * Spring Cloud Stream's {@code StreamBridge}.
 *
 * <p>Why split publish (in-process) from forward (Kafka):
 * <ul>
 *   <li><b>Service tests don't need Kafka.</b> ProductService talks only to
 *       Spring's in-process event bus — assertions become trivial without
 *       a test binder.</li>
 *   <li><b>Don't publish on rollback.</b>
 *       {@code @TransactionalEventListener(phase = AFTER_COMMIT)} fires only
 *       when the surrounding transaction commits. A rolled-back create()
 *       never reaches Kafka.</li>
 *   <li><b>Honest about the dual-write problem.</b> Even with AFTER_COMMIT
 *       we can still commit-then-crash before publish; for true
 *       at-least-once delivery we'd add a transactional outbox + relay.
 *       Out of scope for this iteration — documented in
 *       {@code docs/ARCHITECTURE.md} as a future polish.</li>
 * </ul>
 *
 * <p>One topic, multiple event types: all three events land on
 * {@code catalog.product}. Each carries a {@code TYPE} string constant
 * mirroring the Kafka header {@code event-type} the bridge sets so
 * consumers can route within the topic without payload inspection.
 */
package com.oriah.catalog.events;
