package com.oriah.catalog.events;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.stream.binder.test.OutputDestination;
import org.springframework.cloud.stream.binder.test.TestChannelBinderConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.messaging.Message;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oriah.catalog.TestcontainersConfiguration;
import com.oriah.catalog.dto.ProductUpdateRequest;
import com.oriah.catalog.model.Product;
import com.oriah.catalog.repository.ProductRepository;
import com.oriah.catalog.service.ProductService;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that mutations on {@link ProductService} actually result in
 * Kafka messages on the {@code catalog.product} topic, via the
 * {@link KafkaProductEventBridge}'s {@code @TransactionalEventListener}
 * (AFTER_COMMIT) handlers.
 *
 * <p>Uses the Spring Cloud Stream <b>test binder</b>
 * ({@link TestChannelBinderConfiguration}): replaces the kafka binder with
 * an in-memory {@link OutputDestination} so we can drain messages from
 * the destination as if Kafka had received them, without spinning up a
 * Kafka container.
 *
 * <p>Why this needs a real transaction (via {@link TransactionTemplate})
 * rather than just calling {@code service.create(...)}: the bridge listens
 * with {@code phase = AFTER_COMMIT}. Without an outer transaction the
 * service's {@code @Transactional} starts and commits inline, the
 * AFTER_COMMIT phase fires, and we observe the message. But if any test
 * setup ran inside a single {@code @Transactional} class-level
 * annotation, the listeners would defer until the end of the suite. The
 * test deliberately avoids class-level {@code @Transactional} for this reason.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, TestChannelBinderConfiguration.class})
@TestPropertySource(properties = {
        "eureka.client.enabled=false",
        // Bind the same logical channel ('productEvents-out-0') to a known
        // destination name so OutputDestination.receive() can find it.
        "spring.cloud.stream.bindings.productEvents-out-0.destination=catalog.product"
})
class KafkaProductEventBridgeTest {

    @Autowired
    private ProductService service;

    @Autowired
    private ProductRepository repository;

    @Autowired
    private OutputDestination outputDestination;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void cleanDatabase() {
        repository.deleteAll();
        // Drain any messages left over from previous tests so receive()
        // below returns *this* test's message, not a stale one.
        while (outputDestination.receive(0, "catalog.product") != null) {
            // discard
        }
    }

    @Test
    @DisplayName("create() flows to catalog.product topic as a ProductCreatedEvent with event-type=product.created header")
    void createPublishesToKafkaTopic() throws Exception {
        var saved = service.create("Aurelia", "18k", List.of("rings", "gold"));

        Message<byte[]> message = outputDestination.receive(2_000, "catalog.product");
        assertThat(message)
                .as("expected a message on catalog.product within 2s of create()")
                .isNotNull();
        assertThat(message.getHeaders())
                .containsEntry("event-type", "product.created");

        ProductCreatedEvent received = objectMapper.readValue(
                message.getPayload(), ProductCreatedEvent.class);
        assertThat(received).isEqualTo(new ProductCreatedEvent(
                saved.id(),
                "Aurelia",
                List.of("rings", "gold"),
                "JEWELLERY",
                "DRAFT",
                List.of()
        ));
    }

    @Test
    @DisplayName("update() flows to catalog.product topic with event-type=product.updated")
    void updatePublishesToKafkaTopic() throws Exception {
        var saved = service.create("Aurelia", "18k", List.of("rings"));
        outputDestination.receive(2_000, "catalog.product"); // drain create event

        service.update(saved.id(), new ProductUpdateRequest("Aurelia Renamed", null, null));

        Message<byte[]> message = outputDestination.receive(2_000, "catalog.product");
        assertThat(message).isNotNull();
        assertThat(message.getHeaders()).containsEntry("event-type", "product.updated");

        ProductUpdatedEvent received = objectMapper.readValue(
                message.getPayload(), ProductUpdatedEvent.class);
        assertThat(received.id()).isEqualTo(saved.id());
        assertThat(received.title()).isEqualTo("Aurelia Renamed");
    }

    @Test
    @DisplayName("deleteById() flows to catalog.product topic with event-type=product.deleted")
    void deletePublishesToKafkaTopic() throws Exception {
        var saved = service.create("Aurelia", "18k", List.of("rings"));
        outputDestination.receive(2_000, "catalog.product"); // drain create event

        service.deleteById(saved.id());

        Message<byte[]> message = outputDestination.receive(2_000, "catalog.product");
        assertThat(message).isNotNull();
        assertThat(message.getHeaders()).containsEntry("event-type", "product.deleted");

        ProductDeletedEvent received = objectMapper.readValue(
                message.getPayload(), ProductDeletedEvent.class);
        assertThat(received).isEqualTo(new ProductDeletedEvent(saved.id()));
    }

    @Test
    @DisplayName("an update() on an unknown id produces no message")
    void unknownUpdatePublishesNothing() {
        UUID unknown = UUID.randomUUID();
        service.update(unknown, new ProductUpdateRequest("anything", null, null));

        Message<byte[]> message = outputDestination.receive(500, "catalog.product");
        assertThat(message).as("expected no message for an unknown-id update").isNull();
    }
}
