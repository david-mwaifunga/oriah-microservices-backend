package com.oriah.payment.events;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cloud.stream.binder.test.OutputDestination;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.Message;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.oriah.payment.TestcontainersConfiguration;
import com.oriah.payment.model.PaymentIntent;
import com.oriah.payment.repository.IdempotencyKeyRepository;
import com.oriah.payment.repository.PaymentIntentRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Every charge outcome is a fact in payment's permanent log, written in the
 * same transaction that records it, and published from there.
 *
 * <ul>
 *   <li>aggregate ("payment_intent", intent id), topic oriah.payment,
 *       schema 1, a full snapshot of the intent;</li>
 *   <li>occurred_at is the gateway's decision time -- the moment the money
 *       moved (or did not) -- never the moment payment wrote it;</li>
 *   <li>actor is {@code user:<JWT subject>}, the shopper who confirmed.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"eureka.client.enabled=false"})
class PaymentEventsTest {

    private static final String ALICE = "kc-alice";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private PaymentIntentRepository intents;
    @Autowired
    private IdempotencyKeyRepository idempotencyKeys;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private OutputDestination output;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    private JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        jdbc = new JdbcTemplate(dataSource);
        idempotencyKeys.deleteAll();
        intents.deleteAll();
    }

    @Test
    @DisplayName("a capture appends payment.succeeded: the snapshot, at the gateway's time, by the shopper")
    void aCaptureAppendsPaymentSucceeded() throws Exception {
        PaymentIntent intent = pendingFor(ALICE);

        confirm(intent, "4242424242424242");

        PaymentIntent paid = intents.findById(intent.id()).orElseThrow();
        List<Map<String, Object>> events = eventsFor(intent.id());
        assertThat(events).hasSize(1);
        Map<String, Object> event = events.get(0);
        assertThat(event)
                .containsEntry("event_type", "payment.succeeded")
                .containsEntry("seq", 1L)
                .containsEntry("schema_version", 1)
                .containsEntry("topic", "oriah.payment")
                .containsEntry("actor", "user:" + ALICE);
        assertThat(event.get("occurred_at")).isEqualTo(paid.confirmedAt());
        assertThat(event.get("payload")).isEqualTo("{"
                + "\"intentId\":\"" + paid.id() + "\","
                + "\"orderId\":\"" + paid.orderId() + "\","
                + "\"amount\":4500.00,"
                + "\"currency\":\"ZMW\","
                + "\"chargeReference\":\"" + paid.chargeReference() + "\","
                + "\"gatewayReference\":\"" + paid.gatewayReference() + "\","
                + "\"confirmedAt\":\"" + paid.confirmedAt() + "\","
                + "\"status\":\"SUCCEEDED\""
                + "}");
    }

    @Test
    @DisplayName("a decline appends payment.failed: the snapshot with the reason, at the gateway's time")
    void aDeclineAppendsPaymentFailed() throws Exception {
        PaymentIntent intent = pendingFor(ALICE);

        confirm(intent, "4000000000009995");

        PaymentIntent failed = intents.findById(intent.id()).orElseThrow();
        List<Map<String, Object>> events = eventsFor(intent.id());
        assertThat(events).hasSize(1);
        Map<String, Object> event = events.get(0);
        assertThat(event)
                .containsEntry("event_type", "payment.failed")
                .containsEntry("seq", 1L)
                .containsEntry("actor", "user:" + ALICE);
        assertThat(event.get("occurred_at")).isEqualTo(failed.confirmedAt());
        assertThat(event.get("payload")).isEqualTo("{"
                + "\"intentId\":\"" + failed.id() + "\","
                + "\"orderId\":\"" + failed.orderId() + "\","
                + "\"amount\":4500.00,"
                + "\"currency\":\"ZMW\","
                + "\"chargeReference\":\"" + failed.chargeReference() + "\","
                + "\"reason\":\"insufficient funds\","
                + "\"failedAt\":\"" + failed.confirmedAt() + "\","
                + "\"status\":\"FAILED\""
                + "}");
    }

    @Test
    @DisplayName("the relay publishes payment.succeeded on oriah.payment with the full envelope")
    void theRelayPublishesIt() throws Exception {
        PaymentIntent intent = pendingFor(ALICE);
        confirm(intent, "4242424242424242");
        Map<String, Object> stored = eventsFor(intent.id()).get(0);

        // The relay runs on its own schedule (every 500 ms), so wait for it,
        // passing over anything other tests in this context published.
        Message<byte[]> message = publishedFor(intent.id());

        assertThat(new String(message.getPayload(), StandardCharsets.UTF_8)).isEqualTo(stored.get("payload"));
        assertThat(message.getHeaders())
                .containsEntry("event-id", stored.get("event_id").toString())
                .containsEntry("event-type", "payment.succeeded")
                .containsEntry("aggregate-type", "payment_intent")
                .containsEntry("aggregate-id", intent.id().toString())
                .containsEntry("seq", "1")
                .containsEntry("outbox-id", stored.get("outbox_id").toString())
                .containsEntry("occurred-at", stored.get("occurred_at").toString())
                .containsEntry("recorded-at", stored.get("recorded_at").toString())
                .containsEntry("actor", "user:" + ALICE)
                .containsEntry("schema-version", "1")
                .containsEntry("producer", "payment");
        assertThat(String.valueOf(message.getHeaders().get("contentType"))).contains("application/json");
        assertThat((byte[]) message.getHeaders().get("kafka_messageKey"))
                .isEqualTo(intent.id().toString().getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------

    private Message<byte[]> publishedFor(UUID intentId) {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            Message<byte[]> message = output.receive(500, "oriah.payment");
            if (message != null && intentId.toString().equals(String.valueOf(message.getHeaders().get("aggregate-id")))) {
                return message;
            }
        }
        throw new AssertionError("nothing was published on oriah.payment for intent " + intentId + " within 15 s");
    }

    private PaymentIntent pendingFor(String owner) {
        return intents.saveAndFlush(new PaymentIntent(UUID.randomUUID(), owner, new BigDecimal("4500.00"), "ZMW"));
    }

    private void confirm(PaymentIntent intent, String card) throws Exception {
        mvc.perform(post("/api/v1/payments/intents/{id}/confirm", intent.id())
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER")).jwt(j -> j.subject(ALICE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cardNumber\":\"" + card + "\"}"))
                .andExpect(status().isOk());
    }

    /** The outbox rows for one intent, oldest first; times as Instants, the payload as its stored text. */
    private List<Map<String, Object>> eventsFor(UUID intentId) {
        return jdbc.query("""
                SELECT outbox_id, event_id, event_type, seq, schema_version, topic, occurred_at, recorded_at,
                       actor, payload::text AS payload
                FROM outbox WHERE aggregate_type = 'payment_intent' AND aggregate_id = ?
                ORDER BY seq""", (rs, n) -> {
                    Map<String, Object> row = new HashMap<>();
                    row.put("outbox_id", rs.getLong("outbox_id"));
                    row.put("event_id", rs.getObject("event_id", UUID.class));
                    row.put("event_type", rs.getString("event_type"));
                    row.put("seq", rs.getLong("seq"));
                    row.put("schema_version", rs.getInt("schema_version"));
                    row.put("topic", rs.getString("topic"));
                    row.put("occurred_at", rs.getObject("occurred_at", OffsetDateTime.class).toInstant());
                    row.put("recorded_at", rs.getObject("recorded_at", OffsetDateTime.class).toInstant());
                    row.put("actor", rs.getString("actor"));
                    row.put("payload", rs.getString("payload"));
                    return row;
                }, intentId.toString());
    }
}
