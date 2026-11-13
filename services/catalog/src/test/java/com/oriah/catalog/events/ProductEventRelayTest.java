package com.oriah.catalog.events;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cloud.stream.binder.test.OutputDestination;
import org.springframework.cloud.stream.binder.test.TestChannelBinderConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHeaders;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.util.MimeType;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oriah.catalog.TestcontainersConfiguration;

import static com.oriah.catalog.events.ProductEventGoldenTest.golden;
import static com.oriah.catalog.events.ProductEventGoldenTest.nextFor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What reaches {@code catalog.product} now that the relay publishes it: the
 * same payload as before, byte for byte, plus the envelope every event in
 * the platform carries.
 *
 * <p>The envelope is what lets a consumer apply each event once and in
 * order without parsing the body: {@code event-id} to recognise a
 * redelivery, {@code seq} to skip a snapshot older than the one it holds,
 * {@code aggregate-id} to know which product (consumers read this header,
 * never the Kafka key), {@code actor} for who made the change. The Kafka
 * key is the product id, so every event of one product lands on one
 * partition and is read in order there.
 *
 * <p>The relay runs on its own schedule here, as in production, so the test
 * waits for each message rather than asking for it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestChannelBinderConfiguration.class})
@TestPropertySource(properties = {"eureka.client.enabled=false"})
class ProductEventRelayTest {

    private static final List<String> ENVELOPE = List.of(
            "event-id", "event-type", "aggregate-type", "aggregate-id", "seq", "outbox-id",
            "occurred-at", "recorded-at", "actor", "schema-version", "producer", "kafka_messageKey");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private OutputDestination output;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    @Test
    @DisplayName("each published message is today's payload plus the envelope, matching its outbox row")
    void publishedMessagesCarryTodaysPayloadAndTheEnvelope() throws Exception {
        AdminWrites admin = new AdminWrites(mvc, objectMapper, "relay-admin");
        String sku = ProductOutboxTest.freshSku();
        String productId = admin.create("{\"title\":\"Aurelia Solitaire Ring\",\"tags\":[\"rings\",\"gold\"]}");
        admin.addVariant(productId, "{\"sku\":\"" + sku + "\"}").andExpect(status().isCreated());
        String variantId = admin.variantId(productId, 0);
        Map<String, String> ids = Map.of("productId", productId, "variantId", variantId);

        Message<byte[]> created = nextFor(output, objectMapper, productId);
        Message<byte[]> updated = nextFor(output, objectMapper, productId);

        assertEnvelope(created, productId, 1, "product.created");
        assertThat(new String(created.getPayload(), StandardCharsets.UTF_8))
                .isEqualTo(golden("product.created.json", ids));

        assertEnvelope(updated, productId, 2, "product.updated");
        assertThat(new String(updated.getPayload(), StandardCharsets.UTF_8))
                .isEqualTo(golden("product.updated.variant-added.json", ids)
                        .replace(ProductOutboxTest.GOLDEN_SKU, ProductOutboxTest.goldenSku(sku)));
    }

    private void assertEnvelope(Message<byte[]> message, String productId, long seq, String eventType) {
        MessageHeaders headers = message.getHeaders();
        assertThat(headers).containsKeys(ENVELOPE.toArray(String[]::new));

        Map<String, Object> row = jdbc.queryForMap("""
                SELECT outbox_id, event_id, occurred_at, recorded_at, payload::text AS payload
                  FROM outbox
                 WHERE aggregate_type = 'product' AND aggregate_id = ? AND seq = ?""", productId, seq);

        assertThat(header(message, "event-id")).isEqualTo(row.get("event_id").toString());
        assertThat(header(message, "event-type")).isEqualTo(eventType);
        assertThat(header(message, "aggregate-type")).isEqualTo("product");
        assertThat(header(message, "aggregate-id")).isEqualTo(productId);
        assertThat(header(message, "seq")).isEqualTo(Long.toString(seq));
        assertThat(header(message, "outbox-id")).isEqualTo(row.get("outbox_id").toString());
        assertThat(header(message, "occurred-at"))
                .isEqualTo(((Timestamp) row.get("occurred_at")).toInstant().toString());
        assertThat(header(message, "recorded-at"))
                .isEqualTo(((Timestamp) row.get("recorded_at")).toInstant().toString());
        assertThat(header(message, "actor")).isEqualTo("user:relay-admin");
        assertThat(header(message, "schema-version")).isEqualTo("1");
        assertThat(header(message, "producer")).isEqualTo("catalog");
        assertThat(headers.get("kafka_messageKey"))
                .isInstanceOf(byte[].class)
                .isEqualTo(productId.getBytes(StandardCharsets.UTF_8));
        assertThat(MimeType.valueOf(header(message, MessageHeaders.CONTENT_TYPE)))
                .isEqualTo(MimeType.valueOf("application/json"));
        // The bytes published are the bytes the outbox recorded.
        assertThat(new String(message.getPayload(), StandardCharsets.UTF_8)).isEqualTo(row.get("payload"));
    }

    private static String header(Message<byte[]> message, String name) {
        Object value = message.getHeaders().get(name);
        return value == null ? null : value.toString();
    }
}
