package com.oriah.finance.events;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.oriah.finance.TestcontainersConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The courier fare Oriah paid, from {@code order.shipped}: an expense of the Lusaka day the parcel went (not the day it was typed), posted once, with an expense record of
 * its own so it can be reversed or corrected like any other. A dispatch with no fare, a K0.00 fare, a courier the recipient paid and Oriah's own delivery post nothing,
 * but every dispatch is remembered: a fare typed in by hand can only follow one.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
class FareApplierTest {

    @Autowired
    private FareApplier fares;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    private void ship(UUID order, Instant when, String fare, String payer) {
        fares.apply(Events.ctx("order.shipped", "order", order, when), Events.shipped(order, when, fare, payer));
    }

    private List<Map<String, Object>> fareRows(UUID order) {
        return jdbc.queryForList("SELECT entry_id, entry_key, account, category, amount, effect_day, source, order_id, actor FROM ledger_entry WHERE order_id = ?", order);
    }

    @Test
    @DisplayName("a fare Oriah paid is an expense of the day the parcel went, with an expense record that says it was posted by the system, from nothing personal")
    void aFareIsPosted() {
        UUID order = UUID.randomUUID();

        ship(order, Instant.parse("2026-10-14T10:00:00Z"), "40.00", "ORIAH_COLLECTS");

        List<Map<String, Object>> rows = fareRows(order);
        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(row.get("account")).isEqualTo("EXPENSE");
        assertThat(row.get("category")).isEqualTo("COURIER_FARE");
        assertThat((BigDecimal) row.get("amount")).isEqualByComparingTo("40.00");
        assertThat(((java.sql.Date) row.get("effect_day")).toLocalDate()).isEqualTo(LocalDate.parse("2026-10-14"));
        assertThat(row.get("source")).isEqualTo("ORDER_SHIPPED");
        assertThat(row.get("entry_key")).isEqualTo("fare:" + order);
        Map<String, Object> expense = jdbc.queryForMap("SELECT kind, category, origin, linked_order_id, created_by, description FROM expense WHERE ledger_entry_id = ?", row.get("entry_id"));
        assertThat(expense).containsEntry("kind", "ADD").containsEntry("category", "COURIER_FARE").containsEntry("origin", "POSTED")
                .containsEntry("linked_order_id", order).containsEntry("created_by", "system:finance");
        assertThat((String) expense.get("description")).doesNotContain(order.toString());
        Map<String, Object> dispatch = jdbc.queryForMap("SELECT dispatched_at, fare_paid, payer FROM dispatch_fact WHERE order_id = ?", order);
        assertThat(((Timestamp) dispatch.get("dispatched_at")).toInstant()).isEqualTo(Instant.parse("2026-10-14T10:00:00Z"));
        assertThat((BigDecimal) dispatch.get("fare_paid")).isEqualByComparingTo("40.00");
    }

    @Test
    @DisplayName("the fare counts on the Lusaka day: a parcel that went at 22:30 UTC on 31 October is a November cost")
    void theLusakaDay() {
        UUID order = UUID.randomUUID();

        ship(order, Instant.parse("2026-10-31T22:30:00Z"), "25.00", "ORIAH_COLLECTS");

        assertThat(((java.sql.Date) fareRows(order).get(0).get("effect_day")).toLocalDate()).isEqualTo(LocalDate.parse("2026-11-01"));
    }

    @Test
    @DisplayName("no fare, a K0.00 fare, a courier the recipient pays and Oriah's own delivery post nothing; the dispatch is still remembered")
    void nothingDueNothingPosted() {
        UUID free = UUID.randomUUID();
        UUID none = UUID.randomUUID();
        UUID recipient = UUID.randomUUID();

        ship(free, Instant.parse("2026-10-14T10:00:00Z"), "0.00", "ORIAH_COLLECTS");
        ship(none, Instant.parse("2026-10-14T10:00:00Z"), null, null);
        ship(recipient, Instant.parse("2026-10-14T10:00:00Z"), null, "RECIPIENT_PAYS");

        for (UUID order : List.of(free, none, recipient)) {
            assertThat(fareRows(order)).isEmpty();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM dispatch_fact WHERE order_id = ?", Long.class, order)).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject("SELECT fare_paid FROM dispatch_fact WHERE order_id = ?", BigDecimal.class, free)).isEqualByComparingTo("0.00");
        assertThat(jdbc.queryForObject("SELECT fare_paid FROM dispatch_fact WHERE order_id = ?", BigDecimal.class, none)).isNull();
    }

    @Test
    @DisplayName("a dispatch told again books the fare once")
    void aFareIsPostedOnce() {
        UUID order = UUID.randomUUID();
        Instant when = Instant.parse("2026-10-14T10:00:00Z");

        ship(order, when, "40.00", "ORIAH_COLLECTS");
        ship(order, when, "40.00", "ORIAH_COLLECTS");

        assertThat(fareRows(order)).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM expense WHERE linked_order_id = ?", Long.class, order)).isEqualTo(1);
    }

    @Test
    @DisplayName("an envelope that does not say the day the parcel went is refused, and so is a fare finer than an ngwee or below nothing")
    void refusals() {
        UUID order = UUID.randomUUID();
        Instant when = Instant.parse("2026-10-14T10:00:00Z");

        assertThatThrownBy(() -> fares.apply(Events.ctx("order.shipped", "order", order, when.plusSeconds(60)), Events.shipped(order, when, "40.00", "ORIAH_COLLECTS")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("dispatchedAt");
        assertThatThrownBy(() -> ship(order, when, "40.005", "ORIAH_COLLECTS")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ship(order, when, "-5.00", "ORIAH_COLLECTS")).isInstanceOf(IllegalArgumentException.class);
        assertThat(fareRows(order)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dispatch_fact WHERE order_id = ?", Long.class, order)).isZero();
    }
}
