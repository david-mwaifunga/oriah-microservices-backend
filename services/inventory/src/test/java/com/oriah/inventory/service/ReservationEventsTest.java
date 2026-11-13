package com.oriah.inventory.service;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oriah.inventory.TestClockConfiguration;
import com.oriah.inventory.TestcontainersConfiguration;
import com.oriah.inventory.service.BoxCommitmentService.ProductSnapshot;
import com.oriah.inventory.service.ReservationService.Line;

import static com.oriah.inventory.service.StockTestData.ACTOR;
import static com.oriah.inventory.service.StockTestData.box;
import static com.oriah.inventory.service.StockTestData.context;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Inventory publishes what happened to a hold (Phase 7), on {@code oriah.inventory}, from its outbox, in the same transaction as the change:
 * a reservation that was SOLD says which lines and what each cost (the profit report's cost of sales), dated to the day the money arrived; a
 * reservation that EXPIRED tells the order, which is waiting to be moved to EXPIRED. Releasing a hold writes nothing (whoever released it
 * already knows), a refused or repeated sale writes nothing (a rolled-back change must not have announced itself), and one hold ends once.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, TestClockConfiguration.class})
@TestPropertySource(properties = {"eureka.client.enabled=false"})
class ReservationEventsTest {

    @Autowired
    private ReservationService reservations;

    @Autowired
    private BoxCommitmentService commitments;

    @Autowired
    private StockService stock;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper json;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    private UUID ring;
    private UUID box;
    private UUID product;

    @BeforeEach
    void stockOnTheShelf() {
        TestClockConfiguration.CLOCK.set(TestClockConfiguration.T0);
        StockTestData.wipe(jdbc);
        jdbc.execute("DELETE FROM box_commitment");
        jdbc.execute("DELETE FROM product_state");
        ring = UUID.randomUUID();
        box = UUID.randomUUID();
        StockTestData.stock(stock, ring, 5);
        stock.applyReceipt(box(box, "2026-11-09T08:00:00.123456Z"), context(3));
        product = UUID.randomUUID();
        commitments.applyProduct(new ProductSnapshot(product, "SELLABLE", "ACTIVE", box, 0, false), UUID.randomUUID(), "system:test");
    }

    private Line line(int qty) {
        return new Line(UUID.randomUUID(), ring, product, qty, UUID.randomUUID(), null);
    }

    private List<Map<String, Object>> events(UUID reservation) {
        return jdbc.queryForList("""
                SELECT event_type, aggregate_type, aggregate_id, seq, schema_version, topic, occurred_at, actor, payload::text AS payload
                  FROM outbox WHERE aggregate_type = 'reservation' AND aggregate_id = ? ORDER BY outbox_id""", reservation.toString());
    }

    private JsonNode payload(Map<String, Object> event) throws Exception {
        return json.readTree((String) event.get("payload"));
    }

    @Test
    @DisplayName("a sold reservation is announced with its lines and what each cost, dated to the day the money arrived")
    void consumedIsAnnounced() throws Exception {
        Line line = line(2);
        UUID id = UUID.randomUUID();
        UUID order = UUID.randomUUID();
        reservations.reserve(id, order, List.of(line), null, ACTOR);
        Instant arrived = Instant.parse("2026-11-10T10:00:00Z");

        reservations.consume(id, arrived, ACTOR);

        var events = events(id);
        assertThat(events).hasSize(1);
        var e = events.get(0);
        assertThat(e.get("event_type")).isEqualTo("reservation.consumed");
        assertThat(e.get("topic")).isEqualTo("oriah.inventory");
        assertThat(e.get("schema_version")).isEqualTo(1);
        assertThat(((Timestamp) e.get("occurred_at")).toInstant()).as("the day the money arrived").isEqualTo(arrived);
        assertThat(e.get("actor")).isEqualTo(ACTOR);
        JsonNode body = payload(e);
        assertThat(body.get("reservationId").asText()).isEqualTo(id.toString());
        assertThat(body.get("orderId").asText()).isEqualTo(order.toString());
        assertThat(body.get("currency").asText()).isEqualTo("ZMW");
        assertThat(body.get("lines")).hasSize(2);
        JsonNode goods = null;
        JsonNode boxLine = null;
        for (JsonNode l : body.get("lines")) {
            if (l.get("kind").asText().equals("GOODS")) {
                goods = l;
            } else {
                boxLine = l;
            }
        }
        assertThat(goods.get("saleLineId").asText()).isEqualTo(line.saleLineId().toString());
        assertThat(goods.get("variantId").asText()).isEqualTo(ring.toString());
        assertThat(goods.get("quantity").asInt()).isEqualTo(2);
        assertThat(new BigDecimal(goods.get("cost").asText())).as("2 x K10.00").isEqualByComparingTo("20.00");
        assertThat(boxLine.get("saleLineId").asText()).isEqualTo(line.boxLineId().toString());
        assertThat(boxLine.get("productId").asText()).as("a box line names the product it went with").isEqualTo(product.toString());
        assertThat(new BigDecimal(boxLine.get("cost").asText())).as("2 x K78.99, the oldest lot").isEqualByComparingTo("157.98");
    }

    @Test
    @DisplayName("an expired reservation is announced, dated to when it ended, so the order can be moved to EXPIRED")
    void expiredIsAnnounced() throws Exception {
        UUID id = UUID.randomUUID();
        UUID order = UUID.randomUUID();
        reservations.reserve(id, order, List.of(line(1)), null, ACTOR);
        TestClockConfiguration.CLOCK.advance(Duration.ofMinutes(11));

        reservations.expireDue(10);

        var events = events(id);
        assertThat(events).hasSize(1);
        var e = events.get(0);
        assertThat(e.get("event_type")).isEqualTo("reservation.expired");
        assertThat(e.get("topic")).isEqualTo("oriah.inventory");
        assertThat(e.get("actor")).isEqualTo("system:inventory");
        assertThat(((Timestamp) e.get("occurred_at")).toInstant()).isEqualTo(TestClockConfiguration.CLOCK.instant());
        JsonNode body = payload(e);
        assertThat(body.get("reservationId").asText()).isEqualTo(id.toString());
        assertThat(body.get("orderId").asText()).isEqualTo(order.toString());
        assertThat(Instant.parse(body.get("expiresAt").asText())).isEqualTo(TestClockConfiguration.T0.plus(Duration.ofMinutes(10)));
    }

    @Test
    @DisplayName("releasing a hold, and a second sweep, announce nothing")
    void releasingAndRepeatsAnnounceNothing() {
        UUID released = UUID.randomUUID();
        reservations.reserve(released, UUID.randomUUID(), List.of(line(1)), null, ACTOR);
        reservations.release(released, ACTOR);
        UUID lapsed = UUID.randomUUID();
        reservations.reserve(lapsed, UUID.randomUUID(), List.of(line(1)), null, ACTOR);
        TestClockConfiguration.CLOCK.advance(Duration.ofMinutes(11));
        reservations.expireDue(10);
        reservations.expireDue(10);

        assertThat(events(released)).isEmpty();
        assertThat(events(lapsed)).as("announced once").hasSize(1);
    }

    @Test
    @DisplayName("a repeated sale announces nothing new, and a refused sale announces nothing at all")
    void repeatsAndRefusalsAnnounceNothing() {
        UUID id = UUID.randomUUID();
        reservations.reserve(id, UUID.randomUUID(), List.of(line(1)), null, ACTOR);
        reservations.consume(id, TestClockConfiguration.T0, ACTOR);
        reservations.consume(id, TestClockConfiguration.T0, ACTOR);
        assertThat(events(id)).hasSize(1);

        UUID lapsed = UUID.randomUUID();
        reservations.reserve(lapsed, UUID.randomUUID(), List.of(line(1)), null, ACTOR);
        TestClockConfiguration.CLOCK.advance(Duration.ofMinutes(11));
        assertThatThrownBy(() -> reservations.consume(lapsed, TestClockConfiguration.T0, ACTOR)).isNotNull();
        assertThat(events(lapsed)).isEmpty();
    }

    @Test
    @DisplayName("a sale that rolls back (a date in the future) leaves no announcement behind")
    void aRolledBackSaleAnnouncesNothing() {
        UUID id = UUID.randomUUID();
        reservations.reserve(id, UUID.randomUUID(), List.of(line(1)), null, ACTOR);

        assertThatThrownBy(() -> reservations.consume(id, TestClockConfiguration.T0.plus(Duration.ofHours(2)), ACTOR))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(events(id)).isEmpty();
    }
}
