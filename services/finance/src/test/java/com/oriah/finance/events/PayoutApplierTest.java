package com.oriah.finance.events;

import java.math.BigDecimal;
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
import com.oriah.finance.events.RefundEvents.Corrected;
import com.oriah.finance.events.RefundEvents.PaidOut;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Money that goes out (Phase 12): a refund paid, or held money returned. The payout clears what was owed (or held) beside the books, books what the provider charged
 * Oriah to send it as a payment charge, and books the part of that charge the customer bore as a reduction. Revenue is never touched by a payout: the correction took
 * the revenue back, and held money was never revenue. A payout cancelled in payment is negated exactly, on the payout's own day.
 *
 * <p>The worked examples of the plan, to the ngwee.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
class PayoutApplierTest {

    private static final Instant HELD_ON = Instant.parse("2026-11-02T10:00:00Z");

    @Autowired
    private PayoutApplier payouts;
    @Autowired
    private CorrectionApplier corrections;
    @Autowired
    private SaleApplier sales;
    @Autowired
    private PaymentApplier payments;
    @Autowired
    private JdbcTemplate jdbc;
    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    // ------------------------------------------------------------------ fixtures

    /** Example 1's sale: two rings at K100.00, K10.00 off each, K45.00 delivery: K225.00 paid on 3 November. */
    private Paid sale() {
        Paid paid = new Paid().line(Sold.of(UUID.randomUUID(), 2, "100.00", "10.00")).shipping("45.00").fee("3.40").arrived("2026-11-03T08:00:00Z");
        sales.apply(paid.context(), paid.body());
        return paid;
    }

    private Corrected cancelled(Paid paid) {
        Corrected c = Corrected.cancelling(paid).at("2026-11-05T09:15:00Z");
        corrections.apply(c.context(), c.body());
        return c;
    }

    private void pay(PaidOut p) {
        payouts.apply(p.context(), p.body());
    }

    private void voidIt(PaidOut p) {
        payouts.apply(p.voidContext(), p.voidBody());
    }

    private void hold(UUID conf, UUID order, String amount) {
        payments.apply(Events.heldContext(conf, HELD_ON), Events.held(conf, order, amount, HELD_ON, HELD_ON));
    }

    private Map<String, Object> row(String key) {
        return jdbc.queryForMap("""
                SELECT account, category, amount, effect_day, source, order_id, confirmation_id, correction_id, payout_id, reverses_entry_id
                  FROM ledger_entry WHERE entry_key = ?""", key);
    }

    private long keys(String key) {
        return jdbc.queryForObject("SELECT count(*) FROM ledger_entry WHERE entry_key = ?", Long.class, key);
    }

    private BigDecimal sum(UUID order, String account) {
        return jdbc.queryForObject("SELECT coalesce(sum(amount), 0) FROM ledger_entry WHERE order_id = ? AND account = ?", BigDecimal.class, order, account);
    }

    private BigDecimal heading(UUID payout, String category) {
        return jdbc.queryForObject("SELECT coalesce(sum(amount), 0) FROM ledger_entry WHERE payout_id = ? AND category = ?", BigDecimal.class, payout, category);
    }

    private Map<String, Object> expense(String key) {
        return jdbc.queryForMap("""
                SELECT x.kind, x.category, x.origin, x.linked_payout_id, x.linked_order_id, x.reason FROM expense x JOIN ledger_entry l ON l.entry_id = x.ledger_entry_id
                 WHERE l.entry_key = ?""", key);
    }

    private boolean open(String code, UUID subject) {
        return "RAISE".equals(jdbc.query("SELECT action FROM anomaly_log WHERE code = ? AND subject = ? ORDER BY id DESC LIMIT 1", (rs, n) -> rs.getString(1), code,
                subject.toString()).stream().findFirst().orElse(null));
    }

    // ------------------------------------------------------------------ the worked examples

    @Test
    @DisplayName("example 1: K225.00 owed is sent back by MTN on 6 November; MTN charged K5.00 and the customer bore it: nothing is owed, charges +K5.00, recovered -K5.00")
    void aRefundClearsTheDebtAndBooksBothCharges() {
        Paid paid = sale();
        Corrected c = cancelled(paid);
        PaidOut p = PaidOut.refunding(c).charges("5.00", "5.00");

        pay(p);

        String key = "payout:" + p.id + ":";
        assertThat(row(key + "owed")).containsEntry("account", "MEMO_REFUNDS_OWED").containsEntry("correction_id", c.id);
        assertThat((BigDecimal) row(key + "owed").get("amount")).isEqualByComparingTo("-225.00");
        assertThat(row(key + "send")).containsEntry("account", "EXPENSE").containsEntry("category", "PAYMENT_CHARGES");
        assertThat((BigDecimal) row(key + "send").get("amount")).isEqualByComparingTo("5.00");
        assertThat(row(key + "recovery")).containsEntry("account", "EXPENSE").containsEntry("category", "REFUND_FEE_RECOVERED");
        assertThat((BigDecimal) row(key + "recovery").get("amount")).isEqualByComparingTo("-5.00");
        for (String tail : List.of("owed", "send", "recovery")) {
            assertThat(row(key + tail)).as(tail).containsEntry("effect_day", java.sql.Date.valueOf(LocalDate.parse("2026-11-06"))).containsEntry("source", "PAID_OUT")
                    .containsEntry("payout_id", p.id).containsEntry("order_id", paid.order).containsEntry("reverses_entry_id", null);
        }
        assertThat(sum(paid.order, "MEMO_REFUNDS_OWED")).as("owed K225.00, paid K225.00").isEqualByComparingTo("0.00");
        assertThat(expense(key + "send")).containsEntry("kind", "ADD").containsEntry("origin", "POSTED").containsEntry("linked_payout_id", p.id);
        assertThat(expense(key + "recovery")).containsEntry("kind", "RECOVERY").containsEntry("origin", "POSTED").containsEntry("linked_payout_id", p.id);
        // The profit effect of the whole story is the K3.40 MTN kept when the customer paid: the sending charge is borne by the customer.
        assertThat(heading(p.id, "PAYMENT_CHARGES").add(heading(p.id, "REFUND_FEE_RECOVERED"))).isEqualByComparingTo("0.00");
        assertThat(sum(paid.order, "REVENUE_GOODS").add(sum(paid.order, "REVENUE_DELIVERY")).subtract(sum(paid.order, "DISCOUNTS"))).isEqualByComparingTo("0.00");

        pay(p);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_entry WHERE payout_id = ?", Long.class, p.id)).as("told twice, booked once").isEqualTo(3);
    }

    @Test
    @DisplayName("example 3: K85.00 held for a customer is sent back; MTN charged K2.00 and the customer bore it: nothing is held, and revenue was never touched")
    void heldMoneyRepaidLeavesRevenueUnchanged() {
        UUID conf = UUID.randomUUID();
        UUID order = UUID.randomUUID();
        hold(conf, order, "85.00");
        PaidOut p = PaidOut.returningHeld(conf, order, "85.00").charges("2.00", "2.00").on("2026-11-07T09:00:00Z");

        pay(p);

        assertThat(row("payout:" + p.id + ":held")).containsEntry("account", "MEMO_HELD_MONEY").containsEntry("effect_day", java.sql.Date.valueOf(LocalDate.parse("2026-11-07")));
        assertThat((BigDecimal) row("payout:" + p.id + ":held").get("amount")).isEqualByComparingTo("-85.00");
        assertThat(sum(order, "MEMO_HELD_MONEY")).isEqualByComparingTo("0.00");
        assertThat(heading(p.id, "PAYMENT_CHARGES")).isEqualByComparingTo("2.00");
        assertThat(heading(p.id, "REFUND_FEE_RECOVERED")).isEqualByComparingTo("-2.00");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_entry WHERE order_id = ? AND account IN ('REVENUE_GOODS', 'REVENUE_DELIVERY', 'DISCOUNTS')", Long.class, order))
                .as("held money was never revenue, and returning it is not either").isZero();
        assertThat(keys("payout:" + p.id + ":owed")).isZero();
    }

    @Test
    @DisplayName("when Oriah bears the sending charge (the piece was sold while the money was on its way) nothing is recovered: the charge is Oriah's cost")
    void oriahBearsMeansNoRecovery() {
        UUID conf = UUID.randomUUID();
        UUID order = UUID.randomUUID();
        hold(conf, order, "85.00");
        PaidOut p = PaidOut.returningHeld(conf, order, "85.00").charges("2.00", "0.00");

        pay(p);

        assertThat(keys("payout:" + p.id + ":recovery")).isZero();
        assertThat(heading(p.id, "PAYMENT_CHARGES")).isEqualByComparingTo("2.00");
        // And cash handed over costs nothing to send: no charge row at all.
        UUID cashConf = UUID.randomUUID();
        UUID cashOrder = UUID.randomUUID();
        hold(cashConf, cashOrder, "50.00");
        PaidOut cash = PaidOut.returningHeld(cashConf, cashOrder, "50.00").by("CASH");
        pay(cash);
        assertThat(keys("payout:" + cash.id + ":send") + keys("payout:" + cash.id + ":recovery")).isZero();
        assertThat(sum(cashOrder, "MEMO_HELD_MONEY")).isEqualByComparingTo("0.00");
    }

    // ------------------------------------------------------------------ a payout taken back

    @Test
    @DisplayName("a payout cancelled in payment is negated exactly, on the payout's own day: the debt is owed again and both charges are gone")
    void aVoidNegatesOnThePayoutsDay() {
        Paid paid = sale();
        Corrected c = cancelled(paid);
        PaidOut p = PaidOut.refunding(c).charges("5.00", "5.00");
        pay(p);

        voidIt(p);
        voidIt(p);

        for (String tail : List.of("owed", "send", "recovery")) {
            Map<String, Object> negation = row("payout-void:" + p.id + ":" + tail);
            Map<String, Object> original = row("payout:" + p.id + ":" + tail);
            assertThat(((BigDecimal) negation.get("amount")).add((BigDecimal) original.get("amount"))).as(tail).isEqualByComparingTo("0.00");
            assertThat(negation).containsEntry("effect_day", java.sql.Date.valueOf(LocalDate.parse("2026-11-06"))).containsEntry("source", "PAYOUT_VOIDED")
                    .containsEntry("payout_id", p.id);
            assertThat(negation.get("reverses_entry_id")).isNotNull();
        }
        assertThat(sum(paid.order, "MEMO_REFUNDS_OWED")).as("owed again").isEqualByComparingTo("225.00");
        assertThat(heading(p.id, "PAYMENT_CHARGES")).isEqualByComparingTo("0.00");
        assertThat(heading(p.id, "REFUND_FEE_RECOVERED")).isEqualByComparingTo("0.00");
        assertThat(expense("payout-void:" + p.id + ":send")).containsEntry("kind", "REVERSAL").containsEntry("reason", "payout cancelled in payment");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_entry WHERE payout_id = ?", Long.class, p.id)).isEqualTo(6);
    }

    @Test
    @DisplayName("a cancellation of a payout that reaches finance before the payout posts nothing until the payout lands; then both are booked and net to nothing")
    void aVoidBeforeItsPayoutPostsNothingUntilItLands() {
        Paid paid = sale();
        Corrected c = cancelled(paid);
        PaidOut p = PaidOut.refunding(c).charges("5.00", "5.00");

        voidIt(p);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_entry WHERE payout_id = ?", Long.class, p.id)).isZero();

        pay(p);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_entry WHERE payout_id = ?", Long.class, p.id)).isEqualTo(6);
        assertThat(sum(paid.order, "MEMO_REFUNDS_OWED")).isEqualByComparingTo("225.00");
        assertThat(heading(p.id, "PAYMENT_CHARGES")).isEqualByComparingTo("0.00");
    }

    // ------------------------------------------------------------------ never below nothing, in any order

    @Test
    @DisplayName("held money returned before finance has heard that it was held waits: what is held never reads below nothing, and the charges are booked at once")
    void heldMoneyWithoutItsArrivalNeverGoesNegative() {
        UUID conf = UUID.randomUUID();
        UUID order = UUID.randomUUID();
        PaidOut p = PaidOut.returningHeld(conf, order, "85.00").charges("2.00", "2.00");

        pay(p);
        assertThat(keys("payout:" + p.id + ":held")).isZero();
        assertThat(sum(order, "MEMO_HELD_MONEY")).isEqualByComparingTo("0.00");
        assertThat(heading(p.id, "PAYMENT_CHARGES")).as("what the provider charged is a cost whatever else is known").isEqualByComparingTo("2.00");

        hold(conf, order, "85.00");
        assertThat(keys("payout:" + p.id + ":held")).isEqualTo(1);
        assertThat(sum(order, "MEMO_HELD_MONEY")).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("a refund paid before finance has heard of its correction waits the same way")
    void aRefundBeforeItsCorrectionWaits() {
        Paid paid = sale();
        Corrected c = Corrected.cancelling(paid).at("2026-11-05T09:15:00Z");
        PaidOut p = PaidOut.refunding(c).charges("5.00", "5.00");

        pay(p);
        assertThat(keys("payout:" + p.id + ":owed")).isZero();
        assertThat(sum(paid.order, "MEMO_REFUNDS_OWED")).isEqualByComparingTo("0.00");

        corrections.apply(c.context(), c.body());
        assertThat(keys("payout:" + p.id + ":owed")).isEqualTo(1);
        assertThat(sum(paid.order, "MEMO_REFUNDS_OWED")).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("whatever order finance hears of the correction, the payout and its cancellation, the books end the same")
    void everyOrderOfArrivalAgrees() {
        int[][] orders = {{0, 1, 2}, {0, 2, 1}, {1, 0, 2}, {1, 2, 0}, {2, 0, 1}, {2, 1, 0}};
        for (int[] order : orders) {
            Paid paid = sale();
            Corrected c = Corrected.cancelling(paid).at("2026-11-05T09:15:00Z");
            PaidOut p = PaidOut.refunding(c).charges("5.00", "5.00");
            for (int step : order) {
                switch (step) {
                    case 0 -> corrections.apply(c.context(), c.body());
                    case 1 -> pay(p);
                    default -> voidIt(p);
                }
            }
            String told = java.util.Arrays.toString(order);
            assertThat(sum(paid.order, "MEMO_REFUNDS_OWED")).as(told).isEqualByComparingTo("225.00");
            assertThat(heading(p.id, "PAYMENT_CHARGES")).as(told).isEqualByComparingTo("0.00");
            assertThat(heading(p.id, "REFUND_FEE_RECOVERED")).as(told).isEqualByComparingTo("0.00");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_entry WHERE payout_id = ?", Long.class, p.id)).as(told).isEqualTo(6);
        }
    }

    @Test
    @DisplayName("no row of a payout names a confirmation: a refund's sending charge can never be mistaken for the fee of the payment that came in")
    void noPayoutRowCarriesAConfirmation() {
        Paid paid = sale();
        Corrected c = cancelled(paid);
        PaidOut p = PaidOut.refunding(c).charges("5.00", "5.00");
        pay(p);
        voidIt(p);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_entry WHERE payout_id = ? AND confirmation_id IS NOT NULL", Long.class, p.id)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM expense WHERE linked_payout_id = ? AND linked_confirmation_id IS NOT NULL", Long.class, p.id)).isZero();
    }

    // ------------------------------------------------------------------ the open error of a cancelled money record

    @Test
    @DisplayName("paying back a sale whose money record had been cancelled settles the open 'paid after void' error: the money did arrive, and it has gone back")
    void payingBackASaleWhoseMoneyRecordWasCancelledSettlesTheOpenError() {
        Paid paid = new Paid().line(Sold.of(UUID.randomUUID(), 1, "1000.00", "0.00")).fee("10.00").arrived("2026-11-03T08:00:00Z");
        Instant arrived = paid.arrivedAt;
        payments.apply(Events.confirmedContext(paid.confirmation, arrived), Events.confirmed(paid.confirmation, paid.order, "1000.00", "10.00", arrived, 1));
        sales.apply(paid.context(), paid.body());
        Instant voided = Instant.parse("2026-11-04T09:00:00Z");
        payments.apply(Events.voidedContext(paid.confirmation, voided), Events.voided(paid.confirmation, paid.order, "1000.00", "10.00", "PENDING", voided, arrived));
        assertThat(open("paid-after-void", paid.confirmation)).isTrue();
        Corrected c = cancelled(paid);

        pay(PaidOut.refunding(c));

        assertThat(open("paid-after-void", paid.confirmation)).isFalse();
        // Hearing of the confirmation again does not ask the question again.
        payments.apply(Events.confirmedContext(paid.confirmation, arrived), Events.confirmed(paid.confirmation, paid.order, "1000.00", "10.00", arrived, 2));
        assertThat(open("paid-after-void", paid.confirmation)).isFalse();
        assertThat(jdbc.queryForObject("SELECT coalesce(sum(amount), 0) FROM ledger_entry WHERE confirmation_id = ? AND category = 'PAYMENT_CHARGES'", BigDecimal.class,
                paid.confirmation)).as("the money did arrive: its fee stays a cost").isEqualByComparingTo("10.00");
    }

    // ------------------------------------------------------------------ what is refused

    @Test
    @DisplayName("a payout that breaks its own promises is refused and nothing is written")
    void refusals() {
        Paid paid = sale();
        Corrected c = cancelled(paid);
        List<PaidOut> broken = List.of(
                PaidOut.refunding(c).set("currency", "USD"),
                PaidOut.refunding(c).set("net", new BigDecimal("224.00")),
                PaidOut.refunding(c).charges("2.00", "5.00"),
                PaidOut.refunding(c).set("purpose", "GIFT"),
                PaidOut.refunding(c).set("gross", new BigDecimal("225.001")),
                PaidOut.refunding(c).set("sendCharge", new BigDecimal("-1.00")),
                PaidOut.refunding(c).set("debtId", null),
                PaidOut.refunding(c).set("paidAt", "2026-11-07T12:00:00Z"));
        for (PaidOut p : broken) {
            assertThatThrownBy(() -> pay(p)).as(p.overrides + " " + p.customerFee).isInstanceOf(IllegalArgumentException.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM payout_fact WHERE payout_id = ?", Long.class, p.id)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_entry WHERE payout_id = ?", Long.class, p.id)).isZero();
        }
        assertThat(sum(paid.order, "MEMO_REFUNDS_OWED")).isEqualByComparingTo("225.00");
    }
}
