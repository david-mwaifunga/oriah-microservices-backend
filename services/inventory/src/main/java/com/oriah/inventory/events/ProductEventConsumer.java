package com.oriah.inventory.events;

import java.util.function.Consumer;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oriah.inventory.model.ProductRef;
import com.oriah.inventory.repository.ProductRefRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Subscribes to {@code catalog.product} via Spring Cloud Stream's
 * functional model.
 *
 * <p>One bean handles all three event types ({@code product.created},
 * {@code product.updated}, {@code product.deleted}) because they all land
 * on the same topic. The {@code event-type} header (set by catalog's
 * {@link com.oriah.inventory.events package-info} bridge) drives the
 * switch — payload deserialization runs only after we know what shape to
 * expect.
 *
 * <p>Binding name: the bean {@code productEvents} becomes the function
 * binding {@code productEvents-in-0}; YAML wires that to the
 * {@code catalog.product} destination.
 *
 * <p>Idempotency comes from upsert semantics: created and updated both
 * call {@code save()} on the {@code ProductRef} entity keyed by
 * variantId, so a replayed event from at-least-once Kafka delivery just
 * overwrites the row with identical data.
 *
 * <p>Why we read the body manually instead of typed
 * {@code Consumer<ProductCreatedEvent>}: Spring Cloud Stream would happily
 * deserialize directly, but it needs to know the target type per binding
 * — there's only one consumer here and three payload shapes. Reading
 * the raw bytes and branching is simpler than three parallel bindings.
 */
@Configuration
@Slf4j
public class ProductEventConsumer {

    private static final String HEADER_EVENT_TYPE = "event-type";

    @Bean
    public Consumer<Message<byte[]>> productEvents(
            ProductRefRepository repository,
            ObjectMapper objectMapper) {
        return new ProductEventHandler(repository, objectMapper);
    }

    /**
     * Inner handler so we can put {@code @Transactional} on the
     * dispatch method — bean methods on a {@code @Configuration} class
     * don't go through the proxy that {@code @Transactional} relies on.
     */
    static class ProductEventHandler implements Consumer<Message<byte[]>> {
        private final ProductRefRepository repository;
        private final ObjectMapper objectMapper;

        ProductEventHandler(ProductRefRepository repository, ObjectMapper objectMapper) {
            this.repository = repository;
            this.objectMapper = objectMapper;
        }

        @Override
        @Transactional
        public void accept(Message<byte[]> message) {
            Object typeHeader = message.getHeaders().get(HEADER_EVENT_TYPE);
            String type = typeHeader == null ? null : typeHeader.toString();
            try {
                switch (type) {
                    case null -> log.warn("Skipping message with no event-type header headers={}", message.getHeaders());
                    case ProductCreatedEvent.TYPE -> handleCreated(
                            objectMapper.readValue(message.getPayload(), ProductCreatedEvent.class));
                    case ProductUpdatedEvent.TYPE -> handleUpdated(
                            objectMapper.readValue(message.getPayload(), ProductUpdatedEvent.class));
                    case ProductDeletedEvent.TYPE -> handleDeleted(
                            objectMapper.readValue(message.getPayload(), ProductDeletedEvent.class));
                    default -> log.warn("Skipping unrecognized event-type={}", type);
                }
            } catch (Exception e) {
                // Logging-and-swallowing here makes the consumer NOT retry on
                // malformed payloads — they go to a dead-letter topic in a
                // production setup. Out of scope for this iteration; we'd
                // configure spring.cloud.stream.kafka.bindings.productEvents-in-0
                // .consumer.dlqName to wire one up.
                log.error("Failed to handle event-type={} payload-length={}", type,
                        message.getPayload().length, e);
            }
        }

        private void handleCreated(ProductCreatedEvent event) {
            log.info("Consuming product.created id={} title=\"{}\"", event.id(), event.title());
            // upsert keyed by variantId — primary key collision is fine, JPA
            // SELECT-then-INSERT-or-UPDATE via save() handles it.
            ProductRef existing = repository.findById(event.id())
                    .orElse(null);
            if (existing == null) {
                repository.save(new ProductRef(event.id(), event.title()));
            } else {
                existing.updateTitle(event.title());
                // managed entity, JPA dirty checking persists on commit
            }
        }

        private void handleUpdated(ProductUpdatedEvent event) {
            log.info("Consuming product.updated id={} title=\"{}\"", event.id(), event.title());
            ProductRef existing = repository.findById(event.id())
                    .orElse(null);
            if (existing == null) {
                // Updated-before-created can happen on out-of-order delivery or
                // when the consumer subscribes mid-stream. Treat as an upsert.
                repository.save(new ProductRef(event.id(), event.title()));
            } else {
                existing.updateTitle(event.title());
            }
        }

        private void handleDeleted(ProductDeletedEvent event) {
            log.info("Consuming product.deleted id={}", event.id());
            // deleteById is silent when the row doesn't exist; that's the
            // semantic we want — a deleted-for-unknown-id is a no-op.
            repository.deleteById(event.id());
        }
    }
}
