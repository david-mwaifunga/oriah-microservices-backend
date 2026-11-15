package com.oriah.finance.events;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
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
import com.oriah.finance.events.Events.Bill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A bill for the month on a consignment (a courier charge or a port fee that is the month's cost, not the goods') reaches profit as an EXPENSE of a month: each bill on the
 * day it was incurred. A correction counts from the day it is made: it takes back the old bills and books the corrected ones, both on that day, so the month the bill was
 * first booked in keeps its figure. Whatever order the receipt and its corrections arrive in, the totals are the same.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
class PeriodCostApplierTest {

    private static final Instant RECEIVED = Instant.parse("2026-10-20T08:00:00Z");
    private static final Instant AMENDED = Instant.parse("2026-11-20T08:00:00Z");

    @Autowired
    private PeriodCostApplier periods;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    private void receipt(UUID consignment, List<Bill> costs) {
        periods.apply(Events.periodContext(consignment, RECEIVED, 1), Events.periodCosts(consignment, 1, RECEIVED, null, costs, List.of()));
    }

    private void correction(UUID consignment, int revision, List<Bill> costs, List<Bill> replaces) {
        periods.apply(Events.periodContext(consignment, AMENDED, revision), Events.periodCosts(consignment, revision, RECEIVED, AMENDED, costs, replaces));
    }

    private BigDecimal month(UUID consignment, String month) {
        return jdbc.queryForObject("SELECT coalesce(sum(amount), 0) FROM ledger_entry WHERE consignment_id = ? AND account = 'EXPENSE' AND category = 'CONSIGNMENT_PERIOD_COST' "
                + "AND effect_month = ?::date", BigDecimal.class, consignment, month);
    }

    private BigDecimal total(UUID consignment) {
        return jdbc.queryForObject("SELECT coalesce(sum(amount), 0) FROM ledger_entry WHERE consignment_id = ? AND account = 'EXPENSE'", BigDecimal.class, consignment);
    }

    private boolean open(String code, UUID consignment) {
        return "RAISE".equals(jdbc.query("SELECT action FROM anomaly_log WHERE code = ? AND subject = ? ORDER BY id DESC LIMIT 1", (rs, n) -> rs.getString(1), code,
                consignment.toString()).stream().findFirst().orElse(null));
    }

    @Test
    @DisplayName("each bill at receipt is an expense of the day it was incurred, with its cost type as the reason and an expense record that says the system posted it")
    void billsAtReceipt() {
        UUID c = UUID.randomUUID();

        receipt(c, List.of(new Bill(2, "COURIER_INBOUND", "40.00", "2026-10-31"), new Bill(3, "FREIGHT", "25.00", "2026-11-02")));

        List<Map<String, Object>> rows = jdbc.queryForList("SELECT entry_id, entry_key, amount, effect_day, reason, source, actor FROM ledger_entry WHERE consignment_id = ? ORDER BY entry_id", c);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("entry_key")).isEqualTo("period:" + c + ":1:add:2");
        assertThat((BigDecimal) rows.get(0).get("amount")).isEqualByComparingTo("40.00");
        assertThat(((java.sql.Date) rows.get(0).get("effect_day")).toLocalDate()).isEqualTo(LocalDate.parse("2026-10-31"));
        assertThat(rows.get(0).get("reason")).isEqualTo("COURIER_INBOUND");
        assertThat(rows.get(0).get("source")).isEqualTo("PERIOD_COST");
        assertThat(((java.sql.Date) rows.get(1).get("effect_day")).toLocalDate()).isEqualTo(LocalDate.parse("2026-11-02"));
        assertThat(month(c, "2026-10-01")).isEqualByComparingTo("40.00");
        assertThat(month(c, "2026-11-01")).isEqualByComparingTo("25.00");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM expense e JOIN ledger_entry l ON l.entry_id = e.ledger_entry_id WHERE l.consignment_id = ? AND e.origin = 'POSTED' "
                + "AND e.kind = 'ADD' AND e.category = 'CONSIGNMENT_PERIOD_COST' AND e.created_by = 'system:finance'", Long.class, c)).isEqualTo(2);
    }

    @Test
    @DisplayName("a correction takes back the old bill and books the new one, both on the day of the correction: the month the bill was first booked in keeps its figure")
    void aCorrection() {
        UUID c = UUID.randomUUID();
        receipt(c, List.of(new Bill(2, "FREIGHT", "100.00", "2026-10-31")));

        correction(c, 2, List.of(new Bill(2, "FREIGHT", "150.00", "2026-11-20")), List.of(new Bill(2, "FREIGHT", "100.00", "2026-11-20")));

        assertThat(month(c, "2026-10-01")).as("October keeps what it reported").isEqualByComparingTo("100.00");
        assertThat(month(c, "2026-11-01")).as("November carries the difference: 150.00 less 100.00").isEqualByComparingTo("50.00");
        assertThat(total(c)).isEqualByComparingTo("150.00");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM expense e JOIN ledger_entry l ON l.entry_id = e.ledger_entry_id WHERE l.consignment_id = ? AND e.kind = 'REVERSAL' "
                + "AND e.reason IS NOT NULL", Long.class, c)).isEqualTo(1);
    }

    @Test
    @DisplayName("a bill re-tagged away (to ride on the goods) is taken back on the day it is made; the bill that came back is booked on it")
    void aBillTakenBack() {
        UUID c = UUID.randomUUID();
        receipt(c, List.of(new Bill(1, "FREIGHT", "100.00", "2026-10-31")));

        correction(c, 2, List.of(), List.of(new Bill(1, "FREIGHT", "100.00", "2026-11-20")));

        assertThat(month(c, "2026-10-01")).isEqualByComparingTo("100.00");
        assertThat(month(c, "2026-11-01")).isEqualByComparingTo("-100.00");
        assertThat(total(c)).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("announced again, or arriving in the wrong order (the correction before the receipt), the totals are the same")
    void onceAndInAnyOrder() {
        UUID inOrder = UUID.randomUUID();
        UUID reversed = UUID.randomUUID();
        List<Bill> first = List.of(new Bill(2, "FREIGHT", "100.00", "2026-10-31"));
        List<Bill> now = List.of(new Bill(2, "FREIGHT", "150.00", "2026-11-20"));
        List<Bill> back = List.of(new Bill(2, "FREIGHT", "100.00", "2026-11-20"));

        receipt(inOrder, first);
        receipt(inOrder, first);
        correction(inOrder, 2, now, back);
        correction(inOrder, 2, now, back);
        correction(reversed, 2, now, back);
        receipt(reversed, first);

        assertThat(total(inOrder)).isEqualByComparingTo("150.00");
        assertThat(total(reversed)).isEqualByComparingTo("150.00");
        assertThat(month(reversed, "2026-10-01")).isEqualByComparingTo(month(inOrder, "2026-10-01"));
        assertThat(month(reversed, "2026-11-01")).isEqualByComparingTo(month(inOrder, "2026-11-01"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_entry WHERE consignment_id = ?", Long.class, inOrder))
                .as("the receipt's bill, and the correction's take-back and new bill: three rows however often they are told").isEqualTo(3);
    }

    @Test
    @DisplayName("duty, surtax and import VAT are never a bill for the month: one that is announced anyway is booked, and raised as an error that blocks reporting until someone has looked")
    void customsAsABill() {
        UUID c = UUID.randomUUID();

        receipt(c, List.of(new Bill(1, "DUTY", "500.00", "2026-10-31")));

        assertThat(total(c)).isEqualByComparingTo("500.00");
        assertThat(open("customs-as-period", c)).isTrue();
    }

    @Test
    @DisplayName("a bill dated more than a year from the receipt is booked, and raised as a warning")
    void anOddDate() {
        UUID c = UUID.randomUUID();

        receipt(c, List.of(new Bill(1, "FREIGHT", "25.00", "2028-01-01")));

        assertThat(total(c)).isEqualByComparingTo("25.00");
        assertThat(open("period-cost-date-odd", c)).isTrue();
    }

    @Test
    @DisplayName("a broken event is refused whole: not ZMW, a bill of nothing or below it, finer than an ngwee, with no day, with a day that is not a date")
    void refusals() {
        UUID c = UUID.randomUUID();
        String good = new String(Events.periodCosts(c, 1, RECEIVED, null, List.of(new Bill(1, "FREIGHT", "25.00", "2026-10-31")), List.of()), StandardCharsets.UTF_8);

        for (String broken : List.of(good.replace("\"ZMW\"", "\"USD\""), good.replace("25.00", "0.00"), good.replace("25.00", "-5.00"), good.replace("25.00", "25.005"),
                good.replace("\"effectiveOn\":\"2026-10-31\"", "\"effectiveOn\":null"), good.replace("2026-10-31", "next Tuesday"))) {
            assertThatThrownBy(() -> periods.apply(Events.periodContext(c, RECEIVED, 1), broken.getBytes(StandardCharsets.UTF_8))).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_entry WHERE consignment_id = ?", Long.class, c)).isZero();
    }

    @Test
    @DisplayName("nothing a person typed is stored: not the reason a bill was corrected")
    void nothingPersonalIsStored() {
        UUID c = UUID.randomUUID();
        receipt(c, List.of(new Bill(2, "FREIGHT", "100.00", "2026-10-31")));
        correction(c, 2, List.of(new Bill(2, "FREIGHT", "150.00", "2026-11-20")), List.of(new Bill(2, "FREIGHT", "100.00", "2026-11-20")));

        for (String table : List.of("ledger_entry", "expense", "anomaly_log")) {
            String dump = String.join("", jdbc.queryForList("SELECT row_to_json(t)::text FROM " + table + " t", String.class));
            assertThat(dump).as(table).doesNotContain("courier's bill was different");
        }
    }
}
