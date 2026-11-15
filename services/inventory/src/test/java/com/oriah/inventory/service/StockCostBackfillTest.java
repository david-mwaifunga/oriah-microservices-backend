package com.oriah.inventory.service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;

import com.jayway.jsonpath.JsonPath;
import com.oriah.inventory.TestClockConfiguration;
import com.oriah.inventory.TestcontainersConfiguration;
import com.oriah.inventory.events.StockCostBackfill;
import com.oriah.inventory.events.StockCostEvents;
import com.oriah.inventory.ledger.StockReason;

import static com.oriah.inventory.service.StockTestData.ACTOR;
import static com.oriah.inventory.service.StockTestData.context;
import static com.oriah.inventory.service.StockTestData.ring;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doNothing;

/**
 * Stock that moved before {@code stock.cost_recorded} existed has no event, and the outbox is never rewritten: the start-up back-fill announces every ledger row
 * that moves a cost and was written without one, exactly once, with the row's own date and its own record time, so finance, replaying this outbox from the
 * beginning, finds every cost.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, TestClockConfiguration.class})
@TestPropertySource(properties = {"eureka.client.enabled=false"})
class StockCostBackfillTest {

    private static final Instant TUESDAY = Instant.parse("2026-11-10T12:00:00Z");

    @Autowired
    private StockService stock;

    @Autowired
    private StockCostBackfill backfill;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoSpyBean
    private StockCostEvents costEvents;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void clean() {
        TestClockConfiguration.CLOCK.set(TestClockConfiguration.T0);
        StockTestData.wipe(jdbc);
        Mockito.reset(target());
    }

    /** The bean is transactional: stub the object behind its proxy, or stubbing itself would ask for a transaction. */
    private StockCostEvents target() {
        return AopTestUtils.getUltimateTargetObject(costEvents);
    }

    /** What the code did before Phase 10: the ledger rows are written and nothing is announced. */
    private void beforePhaseTen() {
        doNothing().when(target()).movementWritten(anyLong(), any(), any());
    }

    private UUID rings() {
        UUID variant = UUID.randomUUID();
        jdbc.update("INSERT INTO product_index (variant_id, title, product_id) VALUES (?, 'Aurelia Ring', ?)", variant, UUID.randomUUID());
        stock.applyReceipt(ring(variant, "2026-11-09T08:00:00.123456Z"), context(6001));
        return variant;
    }

    private List<String> payloads(UUID variant) {
        return jdbc.queryForList("SELECT payload::text FROM outbox WHERE event_type = 'stock.cost_recorded' AND payload::jsonb ->> 'variantId' = ? ORDER BY outbox_id",
                String.class, variant.toString());
    }

    @Test
    @DisplayName("rows written before the event existed are announced at start-up, once each, with the day of the action and the time the row was written; a second run announces nothing")
    void historyIsAnnouncedOnce() {
        UUID variant = rings();
        beforePhaseTen();
        StockService.WriteOffResult lost = stock.writeOff(UUID.randomUUID(), variant, 6, StockReason.WRITE_OFF_LOST, TUESDAY, null, ACTOR).orElseThrow();
        stock.takeBack(UUID.randomUUID(), lost.actionId(), 1, TUESDAY.plusSeconds(60), null, ACTOR).orElseThrow();
        Mockito.reset(target());
        assertThat(payloads(variant)).as("nothing was announced when they were written").isEmpty();

        int first = backfill.backfill();
        int second = backfill.backfill();

        assertThat(first).as("two rows of the write-off and one of the take-back").isEqualTo(3);
        assertThat(second).isZero();
        List<String> events = payloads(variant);
        assertThat(events).hasSize(3);
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id, occurred_at, recorded_at FROM stock_movement WHERE variant_id = ? AND reason <> 'CONSIGNMENT_RECEIVED' ORDER BY id",
                variant);
        for (int i = 0; i < 3; i++) {
            assertThat(((Number) JsonPath.read(events.get(i), "$.movementId")).longValue()).isEqualTo(((Number) rows.get(i).get("id")).longValue());
            assertThat(Instant.parse(JsonPath.read(events.get(i), "$.recordedAt"))).isEqualTo(((Timestamp) rows.get(i).get("recorded_at")).toInstant());
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox WHERE event_type = 'stock.cost_recorded' AND aggregate_id = ? AND occurred_at = ?", Long.class,
                lost.actionId().toString(), Timestamp.from(TUESDAY))).as("the day of the write-off, not the day of the back-fill").isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_cost_event", Long.class)).isEqualTo(3);
    }

    @Test
    @DisplayName("a row the live path already announced is never announced again, and a receipt is never announced")
    void liveAndBackfillNeverBothAnnounce() {
        UUID variant = rings();
        stock.writeOff(UUID.randomUUID(), variant, 2, StockReason.WRITE_OFF_BROKEN, TUESDAY, null, ACTOR).orElseThrow();
        assertThat(payloads(variant)).as("announced live").hasSize(1);

        assertThat(backfill.backfill()).isZero();

        assertThat(payloads(variant)).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_cost_event", Long.class)).as("the receipt's rows are not costs").isEqualTo(1);
    }
}
