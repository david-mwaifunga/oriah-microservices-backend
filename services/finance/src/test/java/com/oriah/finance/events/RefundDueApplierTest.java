package com.oriah.finance.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What payment says about a refund that is owed (Phase 12). The debt itself books nothing: the correction already put what is owed beside the books. Finance keeps
 * it to check the two services agree, to the ngwee. A debt CLOSED without a payout (the money it would have returned never arrived: the money record was cancelled
 * after it had paid the sale, and the owner confirmed nothing came in) clears the memo and takes the provider's fee out of the books, because a fee on money that
 * never arrived was never charged.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
class RefundDueApplierTest {

    private static final Instant ARRIVED = Instant.parse("2026-11-03T08:00:00Z");
    private static final Instant VOIDED = Instant.parse("2026-11-04T09:00:00Z");
    private static final Instant CANCELLED = Instant.parse("2026-11-05T09:15:00Z");
    private static final Instant CLOSED = Instant.parse("2026-11-07T08:30:00Z");

    @Autowired
    private RefundDueApplier dues;
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

    /** One ring at K1,000.00 (what the confirmation builder confirms), a K10.00 fee kept by the provider. */
    private Paid sale() {
        return new Paid().line(Sold.of(UUID.randomUUID(), 1, "1000.00", "0.00")).fee("10.00").arrived(ARRIVED.toString());
    }

    private void sell(Paid paid) {
        sales.apply(paid.context(), paid.body());
    }

    private Corrected cancel(Paid paid) {
        Corrected c = Corrected.cancelling(paid).at(CANCELLED.toString());
        corrections.apply(c.context(), c.body());
        return c;
    }

    private void due(Corrected c, String owed, String status) {
        dues.apply(RefundEvents.dueContext(c.id, CANCELLED), RefundEvents.due(c.id, c.order, c.confirmation, owed, status, CANCELLED));
    }

    private void close(Corrected c, String owed) {
        dues.apply(RefundEvents.closedContext(c.id, CLOSED), RefundEvents.closed(c.id, c.order, c.confirmation, owed, CLOSED));
    }

    private void confirm(Paid paid, int attempt) {
        payments.apply(Events.confirmedContext(paid.confirmation, ARRIVED), Events.confirmed(paid.confirmation, paid.order, "1000.00", "10.00", ARRIVED, attempt));
    }

    private void voidIt(Paid paid) {
        payments.apply(Events.voidedContext(paid.confirmation, VOIDED), Events.voided(paid.confirmation, paid.order, "1000.00", "10.00", "PENDING", VOIDED, ARRIVED));
    }

    private BigDecimal memo(UUID order) {
        return jdbc.queryForObject("SELECT coalesce(sum(amount), 0) FROM ledger_entry WHERE order_id = ? AND account = 'MEMO_REFUNDS_OWED'", BigDecimal.class, order);
    }

    private BigDecimal feeNet(UUID conf) {
        return jdbc.queryForObject("SELECT coalesce(sum(amount), 0) FROM ledger_entry WHERE confirmation_id = ? AND category = 'PAYMENT_CHARGES'", BigDecimal.class, conf);
    }

    private long keys(String key) {
        return jdbc.queryForObject("SELECT count(*) FROM ledger_entry WHERE entry_key = ?", Long.class, key);
    }

    private boolean open(String code, UUID subject) {
        return "RAISE".equals(jdbc.query("SELECT action FROM anomaly_log WHERE code = ? AND subject = ? ORDER BY id DESC LIMIT 1", (rs, n) -> rs.getString(1), code,
                subject.toString()).stream().findFirst().orElse(null));
    }

    // ------------------------------------------------------------------ the debt

    @Test
    @DisplayName("the debt books nothing: what is owed was booked by the correction; payment's figure is kept beside it")
    void theDebtBooksNothing() {
        Paid paid = sale();
        sell(paid);
        Corrected c = cancel(paid);
        long before = jdbc.queryForObject("SELECT count(*) FROM ledger_entry WHERE order_id = ?", Long.class, paid.order);

        due(c, "1000.00", "OWED");
        due(c, "1000.00", "OWED");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_entry WHERE order_id = ?", Long.class, paid.order)).isEqualTo(before);
        assertThat(jdbc.queryForMap("SELECT status, owed_gross FROM refund_due_fact WHERE due_id = ?", c.id)).containsEntry("status", "OWED");
        assertThat(memo(paid.order)).isEqualByComparingTo("1000.00");
        assertThat(open("debt-differs-from-correction", c.id)).isFalse();
    }

    @Test
    @DisplayName("a debt that is not the amount the correction owes is raised, whichever of the two finance heard first")
    void aDebtThatDiffersIsRaised() {
        Paid paid = sale();
        sell(paid);
        Corrected c = cancel(paid);
        due(c, "990.00", "OWED");
        assertThat(open("debt-differs-from-correction", c.id)).isTrue();

        // The debt first.
        Paid other = sale();
        sell(other);
        Corrected later = Corrected.cancelling(other).at(CANCELLED.toString());
        due(later, "990.00", "OWED");
        assertThat(open("debt-differs-from-correction", later.id)).as("nothing to compare with yet").isFalse();
        corrections.apply(later.context(), later.body());
        assertThat(open("debt-differs-from-correction", later.id)).isTrue();
    }

    @Test
    @DisplayName("a debt in another currency, finer than an ngwee, or of a status nobody knows is refused")
    void refusals() {
        UUID id = UUID.randomUUID();
        for (byte[] body : new byte[][] {
                RefundEvents.due(id, UUID.randomUUID(), UUID.randomUUID(), "10.005", "OWED", CANCELLED),
                RefundEvents.due(id, UUID.randomUUID(), UUID.randomUUID(), "10.00", "MAYBE", CANCELLED),
                RefundEvents.due(id, UUID.randomUUID(), UUID.randomUUID(), "-10.00", "OWED", CANCELLED),
                new String(RefundEvents.due(id, UUID.randomUUID(), UUID.randomUUID(), "10.00", "OWED", CANCELLED)).replace("ZMW", "USD").getBytes()}) {
            assertThatThrownBy(() -> dues.apply(RefundEvents.dueContext(id, CANCELLED), body)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refund_due_fact WHERE due_id = ?", Long.class, id)).isZero();
    }

    // ------------------------------------------------------------------ a debt closed without a payout

    @Test
    @DisplayName("a closed debt clears what was owed, on the day it was closed; told twice it is cleared once")
    void aClosedDebtClearsTheMemo() {
        Paid paid = sale();
        sell(paid);
        Corrected c = cancel(paid);
        due(c, "1000.00", "IN_QUESTION");

        close(c, "1000.00");
        close(c, "1000.00");

        Map<String, Object> row = jdbc.queryForMap("SELECT account, amount, effect_day, source, correction_id, order_id FROM ledger_entry WHERE entry_key = ?",
                "close:" + c.id + ":owed");
        assertThat(row).containsEntry("account", "MEMO_REFUNDS_OWED").containsEntry("source", "REFUND_CLOSED").containsEntry("correction_id", c.id)
                .containsEntry("effect_day", java.sql.Date.valueOf(LocalDate.parse("2026-11-07")));
        assertThat((BigDecimal) row.get("amount")).isEqualByComparingTo("-1000.00");
        assertThat(memo(paid.order)).as("nothing is owed any more").isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("a close that reaches finance before its correction posts nothing until the correction lands: what is owed never reads below nothing")
    void aCloseBeforeItsCorrectionPostsNothingUntilItLands() {
        Paid paid = sale();
        sell(paid);
        Corrected c = Corrected.cancelling(paid).at(CANCELLED.toString());

        close(c, "1000.00");
        assertThat(keys("close:" + c.id + ":owed")).isZero();
        assertThat(memo(paid.order)).isEqualByComparingTo("0.00");

        corrections.apply(c.context(), c.body());
        assertThat(keys("close:" + c.id + ":owed")).isEqualTo(1);
        assertThat(memo(paid.order)).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("money that never arrived was never charged a fee: the fee leaves the books whichever order finance heard of the sale and of the cancelled money record")
    void aClosedDebtRemovesTheFeeWhicheverOrderTheFactsCame() {
        // The money record was cancelled first (its fee was taken back), then found to have paid the sale (the fee was put back, with an open error).
        Paid first = sale();
        confirm(first, 1);
        voidIt(first);
        sell(first);
        assertThat(keys("fee-reinstated:" + first.confirmation)).isEqualTo(1);
        assertThat(open("paid-after-void", first.confirmation)).isTrue();
        Corrected a = cancel(first);
        close(a, "1000.00");
        assertThat(keys("fee-unreinstated:" + first.confirmation)).isEqualTo(1);
        assertThat(feeNet(first.confirmation)).isEqualByComparingTo("0.00");
        assertThat(open("paid-after-void", first.confirmation)).as("settled: the owner said what became of the money").isFalse();

        // The sale first, then the cancellation of the money record: the fee was never taken back.
        Paid second = sale();
        confirm(second, 1);
        sell(second);
        voidIt(second);
        assertThat(keys("fee-void:" + second.confirmation)).isZero();
        assertThat(feeNet(second.confirmation)).isEqualByComparingTo("10.00");
        Corrected b = cancel(second);
        close(b, "1000.00");
        assertThat(keys("fee-never-came:" + second.confirmation)).isEqualTo(1);
        assertThat(feeNet(second.confirmation)).isEqualByComparingTo("0.00");
        assertThat(jdbc.queryForObject("""
                SELECT r.effect_day = f.effect_day AND r.reverses_entry_id = f.entry_id FROM ledger_entry r, ledger_entry f
                 WHERE r.entry_key = ? AND f.entry_key = ?""", Boolean.class, "fee-never-came:" + second.confirmation, "fee:" + second.confirmation))
                .as("it reverses the fee row itself, on that row's day").isTrue();
        assertThat(open("paid-after-void", second.confirmation)).isFalse();

        // Hearing of the confirmation again changes nothing: the fee stays out and the error stays settled.
        confirm(second, 2);
        close(b, "1000.00");
        assertThat(feeNet(second.confirmation)).isEqualByComparingTo("0.00");
        assertThat(open("paid-after-void", second.confirmation)).isFalse();
    }

    @Test
    @DisplayName("a closed debt for money that carried no fee removes nothing")
    void noFeeNothingToRemove() {
        Paid paid = new Paid().line(Sold.of(UUID.randomUUID(), 1, "1000.00", "0.00")).arrived(ARRIVED.toString());
        sell(paid);
        Corrected c = cancel(paid);

        close(c, "1000.00");

        assertThat(feeNet(paid.confirmation)).isEqualByComparingTo("0.00");
        assertThat(keys("fee-never-came:" + paid.confirmation) + keys("fee-unreinstated:" + paid.confirmation)).isZero();
    }
}
