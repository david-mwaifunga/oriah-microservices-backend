package com.oriah.notification.events;

import java.math.BigDecimal;
import java.util.Map;
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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oriah.notification.TestcontainersConfiguration;
import com.oriah.notification.repository.NotificationLogRepository;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import({TestcontainersConfiguration.class, TestChannelBinderConfiguration.class})
@TestPropertySource(properties = {
        "eureka.client.enabled=false",
        "spring.cloud.function.definition=orderEvents",
        "spring.cloud.stream.bindings.orderEvents-in-0.destination=oriah.order"
})
class OrderEventConsumerTest {

    @Autowired
    private InputDestination inputDestination;
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
        send("order.paid", Map.of(
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
        send("order.delivered", Map.of(
                "orderId", UUID.randomUUID().toString(),
                "keycloakId", "kc-bob",
                "newStatus", "DELIVERED",
                "total", "10",
                "currency", "USD"));

        var log = repository.findAll().get(0);
        assertThat(log.subject()).contains("delivered");
        assertThat(log.eventType()).isEqualTo("order.delivered");
    }

    @Test
    @DisplayName("malformed payload is skipped (logged + swallowed), no row inserted")
    void malformedPayloadIsSkipped() {
        inputDestination.send(MessageBuilder.withPayload("not-json".getBytes())
                .setHeader("event-type", "order.paid").build(), "oriah.order");
        assertThat(repository.count()).isZero();
    }

    private void send(String eventType, Map<String, Object> payload) throws Exception {
        inputDestination.send(
                MessageBuilder.withPayload(objectMapper.writeValueAsBytes(payload))
                        .setHeader("event-type", eventType)
                        .build(),
                "oriah.order"
        );
    }
}
