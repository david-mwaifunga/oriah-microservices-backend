package com.oriah.inventory.events;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.stream.binder.test.InputDestination;
import org.springframework.cloud.stream.binder.test.TestChannelBinderConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.oriah.inventory.TestcontainersConfiguration;
import com.oriah.inventory.model.ProductRef;
import com.oriah.inventory.repository.ProductRefRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the {@code productEvents-in-0} consumer binding correctly
 * translates inbound Kafka messages into row mutations on the
 * {@code product_index} table.
 *
 * <p>Uses the Spring Cloud Stream test binder
 * ({@link TestChannelBinderConfiguration}) so the test can inject messages
 * via an {@link InputDestination} without a real Kafka. The consumer is the
 * production {@link ProductEventConsumer} {@code @Bean Consumer<...>} —
 * we exercise the binding wiring end-to-end up to the JPA write.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, TestChannelBinderConfiguration.class})
@TestPropertySource(properties = {
        "eureka.client.enabled=false",
        // Declare the consumer function and route catalog.product → it.
        "spring.cloud.function.definition=productEvents",
        "spring.cloud.stream.bindings.productEvents-in-0.destination=catalog.product"
})
class ProductEventConsumerTest {

    @Autowired
    private InputDestination inputDestination;

    @Autowired
    private ProductRefRepository productRefs;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void cleanIndex() {
        productRefs.deleteAll();
    }

    @Test
    @DisplayName("product.created event inserts a ProductRef row")
    void createdEventInsertsRow() {
        UUID id = UUID.randomUUID();
        send(new ProductCreatedEvent(id, "Aurelia", List.of("rings")), ProductCreatedEvent.TYPE);

        Optional<ProductRef> stored = productRefs.findById(id);
        assertThat(stored).isPresent();
        assertThat(stored.get().title()).isEqualTo("Aurelia");
    }

    @Test
    @DisplayName("replaying the same product.created event is idempotent (no duplicate, no error)")
    void replayedCreatedIsIdempotent() {
        UUID id = UUID.randomUUID();
        ProductCreatedEvent event = new ProductCreatedEvent(id, "Aurelia", List.of("rings"));
        send(event, ProductCreatedEvent.TYPE);
        send(event, ProductCreatedEvent.TYPE); // replay

        assertThat(productRefs.count()).isEqualTo(1L);
    }

    @Test
    @DisplayName("product.updated event mutates the cached title")
    void updatedEventChangesTitle() {
        UUID id = UUID.randomUUID();
        send(new ProductCreatedEvent(id, "Aurelia", List.of("rings")), ProductCreatedEvent.TYPE);
        send(new ProductUpdatedEvent(id, "Aurelia Renamed", "fresh copy", List.of("rings")),
                ProductUpdatedEvent.TYPE);

        Optional<ProductRef> stored = productRefs.findById(id);
        assertThat(stored).isPresent();
        assertThat(stored.get().title()).isEqualTo("Aurelia Renamed");
    }

    @Test
    @DisplayName("product.updated for an unknown id is upserted (creates the row)")
    void updatedForUnknownIdInserts() {
        UUID id = UUID.randomUUID();
        // No prior created event — but the consumer should still record what
        // it learned. Treating updated-on-unknown as an upsert is the right
        // call when events can be replayed out of order or the created event
        // was lost before this consumer subscribed.
        send(new ProductUpdatedEvent(id, "Aurelia", null, List.of()), ProductUpdatedEvent.TYPE);

        Optional<ProductRef> stored = productRefs.findById(id);
        assertThat(stored).isPresent();
        assertThat(stored.get().title()).isEqualTo("Aurelia");
    }

    @Test
    @DisplayName("product.deleted event removes the row")
    void deletedEventRemovesRow() {
        UUID id = UUID.randomUUID();
        send(new ProductCreatedEvent(id, "Aurelia", List.of("rings")), ProductCreatedEvent.TYPE);
        send(new ProductDeletedEvent(id), ProductDeletedEvent.TYPE);

        assertThat(productRefs.findById(id)).isEmpty();
    }

    @Test
    @DisplayName("product.deleted for an unknown id is a no-op (not an error)")
    void deletedForUnknownIdIsNoOp() {
        UUID id = UUID.randomUUID();
        send(new ProductDeletedEvent(id), ProductDeletedEvent.TYPE);

        assertThat(productRefs.findById(id)).isEmpty();
    }

    private void send(Object payload, String type) {
        inputDestination.send(
                MessageBuilder.withPayload(payload)
                        .setHeader("event-type", type)
                        .build(),
                "catalog.product"
        );
    }
}
