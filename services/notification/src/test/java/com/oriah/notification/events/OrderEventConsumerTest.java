package com.oriah.notification.events;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.stream.binder.test.InputDestination;
import org.springframework.cloud.stream.binder.test.TestChannelBinderConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.messaging.Message;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.oriah.notification.TestcontainersConfiguration;
import com.oriah.notification.repository.NotificationLogRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import({TestcontainersConfiguration.class, TestChannelBinderConfiguration.class})
@TestPropertySource(properties = {
        "eureka.client.enabled=false",
        "spring.cloud.function.definition=orderEvents",
        "spring.cloud.stream.bindings.orderEvents-in-0.destination=oriah.order"
})
class OrderEventConsumerTest {

    /** The order in both example payloads (src/test/resources/events). */
    private static final UUID EXAMPLE_ORDER = UUID.fromString("8a1f4b2e-3c5d-4e6f-9a0b-1c2d3e4f5a6b");

    @Autowired
    private InputDestination inputDestination;
    @Autowired
    @Qualifier("orderEvents")
    private Consumer<Message<byte[]>> orderEvents;
    @Autowired
    private NotificationLogRepository repository;
    @Autowired
    private ObjectMapper objectMapper;
    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    @DisplayName("order.paid event produces a notification_log row with the right subject")
    void paidEvent() throws Exception {
        UUID orderId = UUID.randomUUID();
        send("order.paid", orderId, Map.of(
                "orderId", orderId.toString(),
                "keycloakId", "kc-alice",
                "previousStatus", "CREATED",
                "newStatus", "PAID",
                "total", new BigDecimal("200.00"),
                "currency", "USD"));

        var all = repository.findAll();
        assertThat(all).hasSize(1);
        var log = all.get(0);
        assertThat(log.eventType()).isEqualTo("order.paid");
        assertThat(log.keycloakId()).isEqualTo("kc-alice");
        assertThat(log.orderId()).isEqualTo(orderId);
        assertThat(log.subject()).contains("Payment");
    }

    @Test
    @DisplayName("order.delivered event uses the 'delivered' template")
    void deliveredEvent() throws Exception {
        UUID orderId = UUID.randomUUID();
        send("order.delivered", orderId, Map.of(
                "orderId", orderId.toString(),
                "keycloakId", "kc-bob",
                "newStatus", "DELIVERED",
                "total", "10",
                "currency", "USD"));

        var log = repository.findAll().get(0);
        assertThat(log.subject()).contains("delivered");
        assertThat(log.eventType()).isEqualTo("order.delivered");
    }

    @Test
    @DisplayName("a body that is not JSON is refused: the exception leaves the consumer and nothing is sent")
    void malformedPayloadIsRefused() {
        // It used to be logged and swallowed, which lost the event for good.
        // Now it fails, and on Kafka the oriah-events module dead-letters it
        // (on its first delivery: no later delivery could read it either).
        Message<byte[]> garbage = Relayed.orderEvent("order.paid", UUID.randomUUID(), 2,
                "not-json".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> orderEvents.accept(garbage)).isInstanceOf(RuntimeException.class);
        assertThat(repository.count()).isZero();
    }

    @Test
    @DisplayName("the contract's example order.created parses and notifies, its total written with its scale and currency")
    void exampleCreatedNotifies() throws Exception {
        inputDestination.send(Relayed.orderEvent("order.created", EXAMPLE_ORDER, 1,
                fixture("events/order.created.v1.json")), "oriah.order");

        var all = repository.findAll();
        assertThat(all).hasSize(1);
        var log = all.get(0);
        assertThat(log.eventType()).isEqualTo("order.created");
        assertThat(log.orderId()).isEqualTo(EXAMPLE_ORDER);
        assertThat(log.keycloakId()).isEqualTo("kc-alice");
        // Every amount in the event is in its "currency", written with its
        // scale. A customer is told K5,875.00 exactly, never 5875.0.
        assertThat(log.body()).contains(EXAMPLE_ORDER + " for 5875.00 ZMW");
    }

    @Test
    @DisplayName("the contract's example order.paid (payment intent set) parses and notifies")
    void examplePaidNotifies() throws Exception {
        inputDestination.send(Relayed.orderEvent("order.paid", EXAMPLE_ORDER, 2,
                fixture("events/order.paid.v1.json")), "oriah.order");

        var all = repository.findAll();
        assertThat(all).hasSize(1);
        var log = all.get(0);
        assertThat(log.eventType()).isEqualTo("order.paid");
        assertThat(log.orderId()).isEqualTo(EXAMPLE_ORDER);
        assertThat(log.keycloakId()).isEqualTo("kc-alice");
        assertThat(log.subject()).contains("Payment");
    }

    @Test
    @DisplayName("notification reads every field of the order event contract: names, order, types, scale and nulls")
    void readsTheWholeContract() throws Exception {
        // Read with the consumer's own mapper and written back, each example
        // must come out exactly as order wrote it. A field notification's
        // view leaves out, misnames or mistypes -- or an amount that loses
        // its scale -- shows up as a difference here.
        for (String path : List.of("events/order.created.v1.json", "events/order.paid.v1.json")) {
            byte[] json = fixture(path);
            OrderEventPayload read = objectMapper.readValue(json, OrderEventPayload.class);

            assertThat(objectMapper.writeValueAsString(read)).as(path).isEqualTo(compact(json));
        }
    }

    /** The example without its layout: the same properties in the same order, every number exactly as written. */
    private static String compact(byte[] json) throws IOException {
        ObjectMapper exact = JsonMapper.builder()
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
                .build();
        return exact.writeValueAsString(exact.readTree(json));
    }

    static byte[] fixture(String path) throws IOException {
        try (InputStream in = OrderEventConsumerTest.class.getClassLoader().getResourceAsStream(path)) {
            assertThat(in).as(path).isNotNull();
            return in.readAllBytes();
        }
    }

    private void send(String eventType, UUID orderId, Map<String, Object> payload) throws Exception {
        inputDestination.send(Relayed.orderEvent(eventType, orderId, 1, objectMapper.writeValueAsBytes(payload)),
                "oriah.order");
    }
}
