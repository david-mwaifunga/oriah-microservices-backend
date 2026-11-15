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
import com.oriah.finance.events.Events.Paid;
import com.oriah.finance.events.Events.Sold;

import static com.oriah.finance.events.Events.k;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A sale, from {@code order.paid}: the revenue of the goods at list price, what a promotion took off, and the delivery the shop collected, each on the Lusaka day the
 * money ARRIVED, with the product each belongs to frozen on the row. The sale happens once however often it is announced; contradictory announcements are raised, never
 * guessed at; a sale that does not add up is refused and leaves nothing behind.
 *
 * <p>Each test makes its own order, so nothing needs wiping (the ledger refuses TRUNCATE).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
class SaleApplierTest {

    @Autowired
    private SaleApplier sales;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    // ------------------------------------------------------------------ fixtures

    private List<Map<String, Object>> entries(UUID order) {
        return jdbc.queryForList("SELECT entry_key, account, amount, effect_day, effect_month, product_id, sale_line_id, source, actor, source_recorded_at "
                + "FROM ledger_entry WHERE order_id = ? ORDER BY entry_id", order);
    }

    private BigDecimal profit(UUID order) {
        return jdbc.queryForObject("SELECT coalesce(sum(e.amount * a.profit_sign), 0) FROM ledger_entry e JOIN ledger_account a ON a.code = e.account WHERE e.order_id = ?",
                BigDecimal.class, order);
    }

    private BigDecimal sum(UUID order, String account) {
        return jdbc.queryForObject("SELECT coalesce(sum(amount), 0) FROM ledger_entry WHERE order_id = ? AND account = ?", BigDecimal.class, order, account);
    }

    private boolean open(String code, String subject) {
        return "RAISE".equals(jdbc.query("SELECT action FROM anomaly_log WHERE code = ? AND subject = ? ORDER BY id DESC LIMIT 1", (rs, n) -> rs.getString(1), code, subject)
                .stream().findFirst().orElse(null));
    }

    private void apply(Paid paid) {
        sales.apply(paid.context(), paid.body());
    }

    /** Two rings at K1,250.00 less K50.00 each, a gift box at K45.50, delivered for K150.00: K2,595.50. */
    private Paid aSale(UUID ringProduct, UUID boxProduct) {
        return new Paid().line(Sold.of(ringProduct, 2, "1250.00", "50.00")).line(Sold.of(boxProduct, 1, "45.50", "0.00")).shipping("150.00").payer("ORIAH_COLLECTS").fee("25.96");
    }

    // ------------------------------------------------------------------ the sale

    @Test
    @DisplayName("a web sale books the goods at list price, the discount and the delivery, each on the day the money arrived, with its product; together they are exactly what was paid")
    void aWebSale() {
        UUID ringProduct = UUID.randomUUID();
        UUID boxProduct = UUID.randomUUID();
        Paid paid = aSale(ringProduct, boxProduct);

        apply(paid);

        List<Map<String, Object>> rows = entries(paid.order);
        assertThat(rows).extracting(r -> r.get("account")).containsExactly("REVENUE_GOODS", "DISCOUNTS", "REVENUE_GOODS", "REVENUE_DELIVERY");
        assertThat((BigDecimal) rows.get(0).get("amount")).isEqualByComparingTo("2500.00");
        assertThat((BigDecimal) rows.get(1).get("amount")).as("what a promotion took off, as a positive amount of its own").isEqualByComparingTo("100.00");
        assertThat((BigDecimal) rows.get(2).get("amount")).isEqualByComparingTo("45.50");
        assertThat((BigDecimal) rows.get(3).get("amount")).isEqualByComparingTo("150.00");
        assertThat(rows.get(0).get("product_id")).isEqualTo(ringProduct);
        assertThat(rows.get(1).get("product_id")).as("the discount is the ring's").isEqualTo(ringProduct);
        assertThat(rows.get(2).get("product_id")).isEqualTo(boxProduct);
        assertThat(rows.get(3).get("product_id")).as("delivery belongs to no product").isNull();
        for (Map<String, Object> r : rows) {
            assertThat(((java.sql.Date) r.get("effect_day")).toLocalDate()).isEqualTo(LocalDate.parse("2026-10-14"));
            assertThat(r.get("source")).isEqualTo("ORDER_PAID");
            assertThat(r.get("actor")).isEqualTo("system:test");
            assertThat(((Timestamp) r.get("source_recorded_at")).toInstant()).isEqualTo(Instant.parse("2026-10-14T08:00:05Z"));
        }
        assertThat(rows.get(0).get("entry_key")).isEqualTo("sale:" + paid.confirmation + ":goods:" + paid.lines.get(0).lineId());
        assertThat(profit(paid.order)).as("goods less discounts plus delivery is what the customer paid").isEqualByComparingTo("2595.50");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sale_line WHERE confirmation_id = ?", Long.class, paid.confirmation)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_ref WHERE order_id = ?", Long.class, paid.order)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM confirmation_fact WHERE confirmation_id = ? AND kind = 'SALE'", Long.class, paid.confirmation)).isEqualTo(1);
    }

    @Test
    @DisplayName("the sale counts on the LUSAKA day: money that arrived at 22:00 UTC on 31 March is an April sale")
    void theLusakaMonthBoundary() {
        Paid paid = aSale(UUID.randomUUID(), UUID.randomUUID()).arrived("2026-03-31T22:00:00Z");

        apply(paid);

        for (Map<String, Object> r : entries(paid.order)) {
            assertThat(((java.sql.Date) r.get("effect_day")).toLocalDate()).isEqualTo(LocalDate.parse("2026-04-01"));
            assertThat(((java.sql.Date) r.get("effect_month")).toLocalDate()).isEqualTo(LocalDate.parse("2026-04-01"));
        }
        Paid lastMinute = aSale(UUID.randomUUID(), UUID.randomUUID()).arrived("2026-03-31T21:59:59Z");
        apply(lastMinute);
        assertThat(((java.sql.Date) entries(lastMinute.order).get(0).get("effect_month")).toLocalDate()).as("one second earlier it is still March").isEqualTo(LocalDate.parse("2026-03-01"));
    }

    @Test
    @DisplayName("a sale with no discount and nothing to deliver books only its goods: a K0.00 row is no row")
    void noDiscountNoDelivery() {
        Paid paid = new Paid().line(Sold.of(UUID.randomUUID(), 1, "300.00", "0.00"));

        apply(paid);

        assertThat(entries(paid.order)).extracting(r -> r.get("account")).containsExactly("REVENUE_GOODS");
    }

    @Test
    @DisplayName("delivery the shop gave away is shown beside the sale and never counted: the charge is K0.00, the amount given up is a memo")
    void aWaivedDelivery() {
        Paid paid = new Paid().line(Sold.of(UUID.randomUUID(), 1, "1250.00", "0.00")).shipping("0.00").waived("150.00");

        apply(paid);

        assertThat(sum(paid.order, "MEMO_DELIVERY_WAIVED")).isEqualByComparingTo("150.00");
        assertThat(sum(paid.order, "REVENUE_DELIVERY")).isEqualByComparingTo("0.00");
        assertThat(profit(paid.order)).as("a memo is never profit").isEqualByComparingTo("1250.00");
    }

    @Test
    @DisplayName("a delivery the recipient pays the courier for is no revenue of ours; a charge on one is a broken sale")
    void aRecipientPaidDelivery() {
        Paid fine = new Paid().line(Sold.of(UUID.randomUUID(), 1, "500.00", "0.00")).payer("RECIPIENT_PAYS");
        apply(fine);
        assertThat(sum(fine.order, "REVENUE_DELIVERY")).isEqualByComparingTo("0.00");

        Paid broken = new Paid().line(Sold.of(UUID.randomUUID(), 1, "500.00", "0.00")).payer("RECIPIENT_PAYS").shipping("80.00");
        assertThatThrownBy(() -> apply(broken)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("recipient");
        assertThat(entries(broken.order)).isEmpty();
    }

    @Test
    @DisplayName("a phone sale keyed days late counts on the day the money arrived; the day the sale truly happened is kept beside it, never used")
    void aPhoneSale() {
        Paid paid = new Paid().line(Sold.of(UUID.randomUUID(), 1, "900.00", "0.00")).arrived("2026-10-14T08:00:00Z").phone("2026-10-10T09:30:00Z");

        apply(paid);

        for (Map<String, Object> r : entries(paid.order)) {
            assertThat(((java.sql.Date) r.get("effect_day")).toLocalDate()).isEqualTo(LocalDate.parse("2026-10-14"));
        }
        Map<String, Object> sale = jdbc.queryForMap("SELECT channel, sale_occurred_at FROM sale WHERE confirmation_id = ?", paid.confirmation);
        assertThat(sale.get("channel")).isEqualTo("PHONE");
        assertThat(((Timestamp) sale.get("sale_occurred_at")).toInstant()).isEqualTo(Instant.parse("2026-10-10T09:30:00Z"));
    }

    // ------------------------------------------------------------------ once

    @Test
    @DisplayName("announced again, a sale posts nothing more: it is one sale, however many times it is told")
    void aSaleHappensOnce() {
        Paid paid = aSale(UUID.randomUUID(), UUID.randomUUID());
        apply(paid);
        int before = entries(paid.order).size();

        apply(paid);
        sales.apply(paid.context(), paid.body());

        assertThat(entries(paid.order)).hasSize(before);
        assertThat(profit(paid.order)).isEqualByComparingTo("2595.50");
        assertThat(open("conflicting-duplicate", paid.confirmation.toString())).isFalse();
    }

    @Test
    @DisplayName("the same confirmation announced with different figures is raised as a conflict and the first sale stands")
    void aConflictingDuplicate() {
        UUID ringProduct = UUID.randomUUID();
        Paid first = new Paid().line(Sold.of(ringProduct, 1, "1250.00", "0.00"));
        apply(first);
        Paid second = new Paid().order(first.order).confirmation(first.confirmation).line(Sold.of(ringProduct, 1, "1300.00", "0.00"));

        apply(second);

        assertThat(open("conflicting-duplicate", first.confirmation.toString())).isTrue();
        assertThat(sum(first.order, "REVENUE_GOODS")).as("the first sale stands").isEqualByComparingTo("1250.00");
    }

    @Test
    @DisplayName("a second, different confirmation of an order that is already paid is raised and posts no revenue: one order is sold once")
    void aSecondConfirmationOfOneOrder() {
        Paid first = new Paid().line(Sold.of(UUID.randomUUID(), 1, "1250.00", "0.00"));
        apply(first);
        Paid second = new Paid().order(first.order).line(Sold.of(UUID.randomUUID(), 1, "1250.00", "0.00"));

        apply(second);

        assertThat(open("second-confirmation-for-order", first.order.toString())).isTrue();
        assertThat(sum(first.order, "REVENUE_GOODS")).isEqualByComparingTo("1250.00");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sale WHERE confirmation_id = ?", Long.class, second.confirmation)).isZero();
    }

    @Test
    @DisplayName("a republished announcement of a sale finance never saw books it (the original was lost) and says so; the original arriving later changes nothing")
    void aRepublishedSaleRecoversALostOriginal() {
        Paid republished = aSale(UUID.randomUUID(), UUID.randomUUID()).republished();

        apply(republished);

        assertThat(profit(republished.order)).isEqualByComparingTo("2595.50");
        assertThat(jdbc.queryForObject("SELECT recovered_from_republish FROM sale WHERE confirmation_id = ?", Boolean.class, republished.confirmation)).isTrue();
        assertThat(open("recovered-from-republish", republished.confirmation.toString())).isTrue();

        Paid original = new Paid().order(republished.order).confirmation(republished.confirmation);
        original.lines.addAll(republished.lines);
        original.shipping("150.00").payer("ORIAH_COLLECTS").fee("25.96");
        apply(original);

        assertThat(profit(republished.order)).isEqualByComparingTo("2595.50");
        assertThat(open("conflicting-duplicate", republished.confirmation.toString())).isFalse();
    }

    // ------------------------------------------------------------------ what is refused

    @Test
    @DisplayName("a sale that does not add up, or breaks a promise the event makes, is refused whole: its lines must make its total, its subtotal, its discount and what was paid")
    void aBrokenSaleIsRefused() {
        List<Paid> broken = List.of(
                aSale(UUID.randomUUID(), UUID.randomUUID()).set("total", k("2595.51")),
                aSale(UUID.randomUUID(), UUID.randomUUID()).set("paymentGross", k("2600.00")),
                aSale(UUID.randomUUID(), UUID.randomUUID()).set("subtotal", k("2545.00")),
                aSale(UUID.randomUUID(), UUID.randomUUID()).set("discountAmount", k("0.00")),
                aSale(UUID.randomUUID(), UUID.randomUUID()).set("paymentNet", k("2595.50")),
                aSale(UUID.randomUUID(), UUID.randomUUID()).set("currency", "USD"),
                aSale(UUID.randomUUID(), UUID.randomUUID()).set("shippingAmount", k("150.001")),
                aSale(UUID.randomUUID(), UUID.randomUUID()).set("confirmationId", null),
                aSale(UUID.randomUUID(), UUID.randomUUID()).set("lines", List.of()));
        for (Paid paid : broken) {
            assertThatThrownBy(() -> apply(paid)).as(paid.overrides.toString()).isInstanceOf(IllegalArgumentException.class);
            assertThat(entries(paid.order)).as("nothing is left behind by " + paid.overrides).isEmpty();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM sale WHERE order_id = ?", Long.class, paid.order)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM order_ref WHERE order_id = ?", Long.class, paid.order)).isZero();
        }
    }

    @Test
    @DisplayName("an envelope that does not say the day the money arrived is refused: the date a sale counts by is read from one place and must agree with the other")
    void theEnvelopeMustAgreeWithTheBody() {
        Paid paid = aSale(UUID.randomUUID(), UUID.randomUUID());
        EventContext wrongDay = Events.ctx("order.paid", "order", paid.order, paid.arrivedAt.plusSeconds(3600), 2);

        assertThatThrownBy(() -> sales.apply(wrongDay, paid.body())).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("arrivedAt");
        assertThat(entries(paid.order)).isEmpty();
    }

    @Test
    @DisplayName("order.paid in its first version names no confirmation: it is set aside with a standing warning, books nothing, and is never an error that stops the log")
    void versionOneIsSetAside() {
        Paid paid = aSale(UUID.randomUUID(), UUID.randomUUID());
        paid.version = 1;
        EventContext legacy = paid.context();

        sales.apply(legacy, paid.body());

        assertThat(entries(paid.order)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM skipped_fact WHERE event_id = ? AND reason = 'LEGACY_NO_CONFIRMATION'", Long.class, legacy.eventId())).isEqualTo(1);
        assertThat(open("legacy-order-paid-v1", paid.order.toString())).isTrue();
    }

    @Test
    @DisplayName("nothing personal is ever stored: the owner, the title and every field finance does not need are never read into a table")
    void nothingPersonalIsStored() {
        Paid paid = aSale(UUID.randomUUID(), UUID.randomUUID());
        apply(paid);

        for (String table : List.of("sale", "ledger_entry", "confirmation_fact", "anomaly_log", "order_ref")) {
            String dump = String.join("", jdbc.queryForList("SELECT row_to_json(t)::text FROM " + table + " t", String.class));
            assertThat(dump).as(table).doesNotContain("kc-alice-0001");
        }
    }
}
