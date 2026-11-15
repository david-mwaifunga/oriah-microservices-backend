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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What payment says about money: it came in (the fee payment charged is a cost of the day it arrived), it was taken back (the fee is taken back in the month it was
 * posted, however much later it is voided), it could not pay a sale and is owed back (a liability beside the books, never revenue).
 *
 * <p>The three facts about one confirmation (it came in, it was voided, it paid a sale) arrive in ANY order, from different topics. Whatever the order, the net fee is
 * the same: a fee is posted once, taken back only when the confirmation was voided and never paid a sale, and put back, with a standing error, when a voided confirmation
 * is found to have paid a sale after all.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
class PaymentApplierTest {

    private static final Instant ARRIVED = Instant.parse("2026-10-14T08:00:00Z");
    private static final Instant VOIDED = Instant.parse("2026-11-03T09:00:00Z");

    @Autowired
    private PaymentApplier payments;

    @Autowired
    private SaleApplier sales;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    // ------------------------------------------------------------------ fixtures

    private void confirm(UUID conf, UUID order, String fee, Instant arrived, int attempt) {
        payments.apply(Events.confirmedContext(conf, arrived), Events.confirmed(conf, order, "1000.00", fee, arrived, attempt));
    }

    private void voidIt(UUID conf, UUID order, String fee, String outcome, Instant voidedAt, Instant arrivedAt) {
        payments.apply(Events.voidedContext(conf, voidedAt), Events.voided(conf, order, "1000.00", fee, outcome, voidedAt, arrivedAt));
    }

    private void hold(UUID conf, UUID order, Instant heldAt, Instant arrivedAt) {
        payments.apply(Events.heldContext(conf, heldAt), Events.held(conf, order, "1000.00", heldAt, arrivedAt));
    }

    private void sell(UUID conf, UUID order, Instant arrived) {
        Paid paid = new Paid().order(order).confirmation(conf).line(Sold.of(UUID.randomUUID(), 1, "1000.00", "0.00")).fee("10.00").arrived(arrived.toString());
        sales.apply(paid.context(), paid.body());
    }

    private BigDecimal feeNet(UUID conf) {
        return jdbc.queryForObject("SELECT coalesce(sum(amount), 0) FROM ledger_entry WHERE confirmation_id = ? AND account = 'EXPENSE' AND category = 'PAYMENT_CHARGES'",
                BigDecimal.class, conf);
    }

    private BigDecimal feeNet(UUID conf, String month) {
        return jdbc.queryForObject("SELECT coalesce(sum(amount), 0) FROM ledger_entry WHERE confirmation_id = ? AND account = 'EXPENSE' AND category = 'PAYMENT_CHARGES' "
                + "AND effect_month = ?::date", BigDecimal.class, conf, month);
    }

    private BigDecimal heldNet(UUID conf) {
        return jdbc.queryForObject("SELECT coalesce(sum(amount), 0) FROM ledger_entry WHERE confirmation_id = ? AND account = 'MEMO_HELD_MONEY'", BigDecimal.class, conf);
    }

    private long rows(UUID conf, String account) {
        return jdbc.queryForObject("SELECT count(*) FROM ledger_entry WHERE confirmation_id = ? AND account = ?", Long.class, conf, account);
    }

    private boolean open(String code, UUID subject) {
        return "RAISE".equals(jdbc.query("SELECT action FROM anomaly_log WHERE code = ? AND subject = ? ORDER BY id DESC LIMIT 1", (rs, n) -> rs.getString(1), code,
                subject.toString()).stream().findFirst().orElse(null));
    }

    // ------------------------------------------------------------------ the fee

    @Test
    @DisplayName("money that came in posts the fee payment charged as a cost of the day it arrived, with an expense record that says the system posted it; announced ten times it is posted once")
    void aFeeIsPostedOnce() {
        UUID conf = UUID.randomUUID();
        UUID order = UUID.randomUUID();

        for (int attempt = 1; attempt <= 10; attempt++) {
            confirm(conf, order, "10.00", ARRIVED, attempt);
        }

        Map<String, Object> fee = jdbc.queryForMap("SELECT entry_id, entry_key, amount, effect_day, source, order_id FROM ledger_entry WHERE confirmation_id = ? AND account = 'EXPENSE'", conf);
        assertThat((BigDecimal) fee.get("amount")).isEqualByComparingTo("10.00");
        assertThat(fee.get("entry_key")).isEqualTo("fee:" + conf);
        assertThat(((java.sql.Date) fee.get("effect_day")).toLocalDate()).isEqualTo(LocalDate.parse("2026-10-14"));
        assertThat(fee.get("source")).isEqualTo("PAYMENT_CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM expense WHERE ledger_entry_id = ? AND origin = 'POSTED' AND kind = 'ADD' AND category = 'PAYMENT_CHARGES' AND created_by = 'system:finance'",
                Long.class, fee.get("entry_id"))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM confirmation_fact WHERE confirmation_id = ? AND kind = 'CONFIRMED'", Long.class, conf)).isEqualTo(1);
    }

    @Test
    @DisplayName("cash costs nothing: a fee of K0.00 posts no row")
    void aCashFee() {
        UUID conf = UUID.randomUUID();

        confirm(conf, UUID.randomUUID(), "0.00", ARRIVED, 1);

        assertThat(rows(conf, "EXPENSE")).isZero();
    }

    @Test
    @DisplayName("the same confirmation announced with another amount or fee is raised as a conflict and the first stands")
    void aConfirmationThatDiffers() {
        UUID conf = UUID.randomUUID();
        UUID order = UUID.randomUUID();
        confirm(conf, order, "10.00", ARRIVED, 1);

        confirm(conf, order, "12.00", ARRIVED, 2);

        assertThat(open("confirmation-differs", conf)).isTrue();
        assertThat(feeNet(conf)).isEqualByComparingTo("10.00");
    }

    // ------------------------------------------------------------------ a void

    @Test
    @DisplayName("a void takes the fee back in the month the fee was posted, not the month it was voided: October keeps a K0.00 fee and November is untouched")
    void aVoidReversesInTheOriginalMonth() {
        UUID conf = UUID.randomUUID();
        UUID order = UUID.randomUUID();
        confirm(conf, order, "10.00", ARRIVED, 1);

        voidIt(conf, order, "10.00", "PENDING", VOIDED, ARRIVED);

        assertThat(feeNet(conf, "2026-10-01")).isEqualByComparingTo("0.00");
        assertThat(feeNet(conf, "2026-11-01")).isEqualByComparingTo("0.00");
        assertThat(rows(conf, "EXPENSE")).as("the fee and its reversal").isEqualTo(2);
        Map<String, Object> reversal = jdbc.queryForMap("SELECT reverses_entry_id, effect_day, source FROM ledger_entry WHERE entry_key = ?", "fee-void:" + conf);
        assertThat(reversal.get("reverses_entry_id")).isNotNull();
        assertThat(((java.sql.Date) reversal.get("effect_day")).toLocalDate()).isEqualTo(LocalDate.parse("2026-10-14"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM expense WHERE kind = 'REVERSAL' AND created_by = 'system:finance' AND linked_confirmation_id = ?", Long.class, conf)).isEqualTo(1);
    }

    @Test
    @DisplayName("a void that arrives before the confirmation, or from an event written before it carried the arrival day, still takes the fee back in the right month")
    void aVoidInAnyOrder() {
        UUID early = UUID.randomUUID();
        UUID old = UUID.randomUUID();
        UUID order = UUID.randomUUID();

        voidIt(early, order, "10.00", "PENDING", VOIDED, ARRIVED);
        confirm(early, order, "10.00", ARRIVED, 1);
        confirm(old, order, "10.00", ARRIVED, 1);
        voidIt(old, order, "10.00", "PENDING", VOIDED, null);

        assertThat(feeNet(early, "2026-10-01")).isEqualByComparingTo("0.00");
        assertThat(feeNet(old, "2026-10-01")).as("from the confirmation's own arrival day").isEqualByComparingTo("0.00");
        assertThat(rows(early, "EXPENSE")).isEqualTo(2);
    }

    @Test
    @DisplayName("every order the three facts can arrive in gives the same books: voided and never paid nets to nothing; voided and then found to have paid a sale keeps the fee and says so")
    void everyOrderOfArrivalAgrees() {
        List<List<String>> orders = List.of(
                List.of("confirmed", "voided"), List.of("voided", "confirmed"));
        for (List<String> sequence : orders) {
            UUID conf = UUID.randomUUID();
            UUID order = UUID.randomUUID();
            for (String step : sequence) {
                if (step.equals("confirmed")) {
                    confirm(conf, order, "10.00", ARRIVED, 1);
                } else {
                    voidIt(conf, order, "10.00", "PENDING", VOIDED, ARRIVED);
                }
            }
            assertThat(feeNet(conf)).as(sequence.toString()).isEqualByComparingTo("0.00");
            assertThat(open("paid-after-void", conf)).as(sequence.toString()).isFalse();
        }
        List<List<String>> all = List.of(
                List.of("confirmed", "voided", "sold"), List.of("confirmed", "sold", "voided"), List.of("voided", "confirmed", "sold"),
                List.of("voided", "sold", "confirmed"), List.of("sold", "confirmed", "voided"), List.of("sold", "voided", "confirmed"));
        for (List<String> sequence : all) {
            UUID conf = UUID.randomUUID();
            UUID order = UUID.randomUUID();
            for (String step : sequence) {
                switch (step) {
                    case "confirmed" -> confirm(conf, order, "10.00", ARRIVED, 1);
                    case "voided" -> voidIt(conf, order, "10.00", "PENDING", VOIDED, ARRIVED);
                    default -> sell(conf, order, ARRIVED);
                }
            }
            assertThat(feeNet(conf)).as("the money did pay the sale, so its fee is a cost: " + sequence).isEqualByComparingTo("10.00");
            assertThat(open("paid-after-void", conf)).as(sequence.toString()).isTrue();
        }
    }

    @Test
    @DisplayName("a sale whose payment fee is not the fee payment charged is raised, both facts standing")
    void aFeeMismatch() {
        UUID conf = UUID.randomUUID();
        UUID order = UUID.randomUUID();
        confirm(conf, order, "12.00", ARRIVED, 1);

        sell(conf, order, ARRIVED);

        assertThat(open("fee-mismatch", conf)).isTrue();
        assertThat(feeNet(conf)).as("the fee payment charged is the one that is a cost").isEqualByComparingTo("12.00");
    }

    // ------------------------------------------------------------------ held money

    @Test
    @DisplayName("money that could not pay a sale is owed back from the day it ARRIVED, never revenue, and posts no fee of its own: K1,000.00 that came in at 23:59 on 31 March is a March liability")
    void heldMoneyIsDatedByArrival() {
        UUID conf = UUID.randomUUID();
        UUID order = UUID.randomUUID();
        Instant arrived = Instant.parse("2026-03-31T21:59:00Z");   // 23:59 in Lusaka
        confirm(conf, order, "10.00", arrived, 1);

        hold(conf, order, Instant.parse("2026-03-31T22:02:00Z"), arrived);   // learned at 00:02 on 1 April

        Map<String, Object> memo = jdbc.queryForMap("SELECT amount, effect_day, effect_month FROM ledger_entry WHERE entry_key = ?", "held:" + conf);
        assertThat((BigDecimal) memo.get("amount")).isEqualByComparingTo("1000.00");
        assertThat(((java.sql.Date) memo.get("effect_month")).toLocalDate()).isEqualTo(LocalDate.parse("2026-03-01"));
        assertThat(rows(conf, "REVENUE_GOODS")).isZero();
        assertThat(feeNet(conf)).as("the fee posts once, from the confirmation").isEqualByComparingTo("10.00");
    }

    @Test
    @DisplayName("held money with no arrival day on its event (written before the field) takes the confirmation's arrival day; with neither it WAITS for the confirmation, never guessing a day that could be another month")
    void heldMoneyFromAnOlderEvent() {
        UUID known = UUID.randomUUID();
        UUID unknown = UUID.randomUUID();
        UUID order = UUID.randomUUID();
        confirm(known, order, "10.00", ARRIVED, 1);

        hold(known, order, Instant.parse("2026-10-15T08:00:00Z"), null);
        hold(unknown, order, Instant.parse("2026-11-01T08:00:00Z"), null);

        assertThat(jdbc.queryForObject("SELECT effect_day FROM ledger_entry WHERE entry_key = ?", java.sql.Date.class, "held:" + known).toLocalDate()).isEqualTo(LocalDate.parse("2026-10-14"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_entry WHERE entry_key = ?", Long.class, "held:" + unknown)).as("no day to book it on yet").isZero();
        assertThat(open("held-without-arrival-date", unknown)).as("and it says it is waiting").isTrue();

        confirm(unknown, order, "10.00", ARRIVED, 1);

        assertThat(jdbc.queryForObject("SELECT effect_day FROM ledger_entry WHERE entry_key = ?", java.sql.Date.class, "held:" + unknown).toLocalDate())
                .as("the confirmation arrives and the money is booked on the day it arrived, not the day it was learned").isEqualTo(LocalDate.parse("2026-10-14"));
        assertThat(open("held-without-arrival-date", unknown)).isFalse();
    }

    @Test
    @DisplayName("money held is checked against the confirmation it came from: another order or another amount is an open error, not a quietly different liability")
    void heldMustMatchTheConfirmation() {
        UUID conf = UUID.randomUUID();
        UUID order = UUID.randomUUID();
        confirm(conf, order, "10.00", ARRIVED, 1);

        payments.apply(Events.heldContext(conf, Instant.parse("2026-10-15T08:00:00Z")), Events.held(conf, order, "900.00", Instant.parse("2026-10-15T08:00:00Z"), ARRIVED));

        assertThat(open("held-differs-from-confirmation", conf)).isTrue();
    }

    @Test
    @DisplayName("finance keeps no reason text from a held-money event: the field is not stored at all")
    void noHeldReasonIsKept() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_name = 'confirmation_fact' AND column_name = 'held_reason'", Long.class)).isZero();
    }

    @Test
    @DisplayName("held money that is then voided is cleared by exactly what was held, and the fee is taken back with it")
    void heldThenVoided() {
        UUID conf = UUID.randomUUID();
        UUID order = UUID.randomUUID();
        confirm(conf, order, "10.00", ARRIVED, 1);
        hold(conf, order, Instant.parse("2026-10-15T08:00:00Z"), ARRIVED);

        voidIt(conf, order, "10.00", "HELD", VOIDED, ARRIVED);

        assertThat(heldNet(conf)).isEqualByComparingTo("0.00");
        assertThat(feeNet(conf)).isEqualByComparingTo("0.00");
        assertThat(rows(conf, "MEMO_HELD_MONEY")).isEqualTo(2);
    }

    // ------------------------------------------------------------------ what is refused, what is never stored

    @Test
    @DisplayName("a broken event is refused whole: not ZMW, a fee finer than an ngwee, a day the money arrived that the envelope does not say, an amount below nothing")
    void refusals() {
        UUID conf = UUID.randomUUID();
        UUID order = UUID.randomUUID();

        assertThatThrownBy(() -> payments.apply(Events.confirmedContext(conf, ARRIVED), Events.confirmed(conf, order, "1000.00", "10.005", ARRIVED, 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> payments.apply(Events.confirmedContext(conf, ARRIVED.plusSeconds(60)), Events.confirmed(conf, order, "1000.00", "10.00", ARRIVED, 1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("arrivedAt");
        assertThatThrownBy(() -> payments.apply(Events.confirmedContext(conf, ARRIVED), new String(Events.confirmed(conf, order, "1000.00", "10.00", ARRIVED, 1),
                java.nio.charset.StandardCharsets.UTF_8).replace("\"ZMW\"", "\"USD\"").getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> payments.apply(Events.confirmedContext(conf, ARRIVED), Events.confirmed(conf, order, "1000.00", "-1.00", ARRIVED, 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(rows(conf, "EXPENSE")).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM confirmation_fact WHERE confirmation_id = ?", Long.class, conf)).isZero();
    }

    @Test
    @DisplayName("nothing a person typed is stored: not the reference, the staff member, the reason for backdating or for voiding")
    void nothingPersonalIsStored() {
        UUID conf = UUID.randomUUID();
        UUID order = UUID.randomUUID();
        confirm(conf, order, "10.00", ARRIVED, 1);
        voidIt(conf, order, "10.00", "HELD", VOIDED, ARRIVED);
        hold(conf, order, VOIDED, ARRIVED);

        for (String table : List.of("confirmation_fact", "ledger_entry", "expense", "anomaly_log")) {
            String dump = String.join("", jdbc.queryForList("SELECT row_to_json(t)::text FROM " + table + " t", String.class));
            assertThat(dump).as(table).doesNotContain("MP2411ABC").doesNotContain("kc-sam").doesNotContain("Mary Banda").doesNotContain("Saturday");
        }
    }
}
