package com.oriah.finance.schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.oriah.finance.TestcontainersConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The ledger is the books, and the books are never edited: every rule that keeps a month from losing or changing a row is held by the DATABASE, so no code path, test
 * double or forgotten check can break it. A correction is a reversal plus a new row.
 *
 * <p>Each test uses its own unique keys and reads only its own rows (the ledger refuses TRUNCATE, so nothing is wiped between tests).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
class LedgerSchemaTest {

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    // ------------------------------------------------------------------ fixtures

    private long entry(String account, String category, String reason, String amount, String day) {
        return jdbc.queryForObject("""
                INSERT INTO ledger_entry (entry_key, account, category, reason, amount, occurred_at, effect_day, source, actor)
                VALUES (?, ?, ?, ?, ?, now(), ?, 'EXPENSE', 'user:kc-test') RETURNING entry_id""",
                Long.class, "test:" + UUID.randomUUID(), account, category, reason, new BigDecimal(amount), LocalDate.parse(day));
    }

    private long reverse(long original, String account, String category, String amount, String day) {
        return jdbc.queryForObject("""
                INSERT INTO ledger_entry (entry_key, account, category, amount, occurred_at, effect_day, source, actor, reverses_entry_id)
                VALUES (?, ?, ?, ?, now(), ?, 'EXPENSE', 'user:kc-test', ?) RETURNING entry_id""",
                Long.class, "test:" + UUID.randomUUID(), account, category, new BigDecimal(amount), LocalDate.parse(day), original);
    }

    private long rent(String amount, String day) {
        return entry("EXPENSE", "RENT_UTILITIES", null, amount, day);
    }

    // ------------------------------------------------------------------ append-only

    @Test
    @DisplayName("a ledger row is never changed or removed, and the table is never emptied: the database refuses UPDATE, DELETE and TRUNCATE")
    void theLedgerIsAppendOnly() {
        long id = rent("1200.00", "2026-10-15");

        assertThatThrownBy(() -> jdbc.update("UPDATE ledger_entry SET amount = 1.00 WHERE entry_id = ?", id)).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM ledger_entry WHERE entry_id = ?", id)).hasMessageContaining("append-only");
        // the tables that point at the ledger are named too, or Postgres refuses the statement for that reason before any trigger is asked
        assertThatThrownBy(() -> jdbc.execute("TRUNCATE ledger_entry, expense, tax_entry")).hasMessageContaining("append-only");
        assertThat(jdbc.queryForObject("SELECT amount FROM ledger_entry WHERE entry_id = ?", BigDecimal.class, id)).isEqualByComparingTo("1200.00");
    }

    @Test
    @DisplayName("every table that holds a fact, an expense, a tax, an issued month or an anomaly refuses edits, removal and emptying")
    void everyFactTableIsAppendOnly() {
        for (String table : List.of("expense", "tax_entry", "idempotency", "sale", "sale_line", "confirmation_fact", "dispatch_fact", "consignment_line_fact",
                "anomaly_log", "report_issue", "statement_check", "skipped_fact", "processed_event",
                "sale_correction", "sale_correction_line", "refund_due_fact", "payout_fact", "refund_close_fact")) {
            // sale_line points at sale, and sale_correction_line at sale_correction
            String statement = "sale".equals(table) ? "TRUNCATE sale, sale_line"
                    : "sale_correction".equals(table) ? "TRUNCATE sale_correction, sale_correction_line" : "TRUNCATE " + table;
            assertThatThrownBy(() -> jdbc.execute(statement)).as(table + " refuses TRUNCATE").hasMessageContaining("append-only");
            // a row trigger only fires for a row, so an empty table cannot show it: the trigger itself must exist
            assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_trigger WHERE tgrelid = ?::regclass AND tgname = ? AND NOT tgisinternal", Integer.class, table,
                    table + "_append_only_row")).as(table + " has its row trigger").isEqualTo(1);
        }
    }

    // ------------------------------------------------------------------ the month is the day's month

    @Test
    @DisplayName("a row's month is worked out by the database from its day, so an April expense can never be booked in March")
    void theMonthFollowsTheDay() {
        long id = rent("100.00", "2027-04-01");

        assertThat(jdbc.queryForObject("SELECT effect_month FROM ledger_entry WHERE entry_id = ?", LocalDate.class, id)).isEqualTo(LocalDate.parse("2027-04-01"));
        assertThatThrownBy(() -> jdbc.update("INSERT INTO ledger_entry (entry_key, account, category, amount, occurred_at, effect_day, effect_month, source, actor) "
                + "VALUES ('test:month', 'EXPENSE', 'RENT_UTILITIES', 1, now(), '2027-04-01', '2027-03-01', 'EXPENSE', 'user:x')")).isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("what finance learned and when is the database's own clock, taken at the moment of writing, and a row says when it was recorded and by whom")
    void whenFinanceLearnedIt() {
        long id = rent("100.00", "2026-10-02");

        OffsetDateTime recorded = jdbc.queryForObject("SELECT recorded_at FROM ledger_entry WHERE entry_id = ?", OffsetDateTime.class, id);

        assertThat(recorded).isCloseTo(OffsetDateTime.now(), org.assertj.core.api.Assertions.within(1, java.time.temporal.ChronoUnit.MINUTES));
        assertThat(jdbc.queryForObject("SELECT actor FROM ledger_entry WHERE entry_id = ?", String.class, id)).isEqualTo("user:kc-test");
    }

    // ------------------------------------------------------------------ reversal

    @Test
    @DisplayName("a reversal negates exactly the row it reverses and nets the month to nothing; a row can be reversed once")
    void aReversingEntryNetsOut() {
        long id = rent("1200.00", "2026-10-15");

        long reversal = reverse(id, "EXPENSE", "RENT_UTILITIES", "-1200.00", "2026-10-15");

        assertThat(jdbc.queryForObject("SELECT sum(amount) FROM ledger_entry WHERE entry_id IN (?, ?)", BigDecimal.class, id, reversal)).isEqualByComparingTo("0.00");
        assertThatThrownBy(() -> reverse(id, "EXPENSE", "RENT_UTILITIES", "-1200.00", "2026-10-15")).isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("a reversal that is not exactly the negative of its original (another amount, day, account or heading) is refused")
    void aReversalMustNegateExactly() {
        long id = rent("1200.00", "2026-10-15");

        assertThatThrownBy(() -> reverse(id, "EXPENSE", "RENT_UTILITIES", "-1000.00", "2026-10-15")).hasMessageContaining("negates exactly");
        assertThatThrownBy(() -> reverse(id, "EXPENSE", "RENT_UTILITIES", "-1200.00", "2026-10-16")).hasMessageContaining("negates exactly");
        assertThatThrownBy(() -> reverse(id, "EXPENSE", "MARKETING", "-1200.00", "2026-10-15")).hasMessageContaining("negates exactly");
        assertThatThrownBy(() -> reverse(id, "EXPENSE", "RENT_UTILITIES", "1200.00", "2026-10-15")).hasMessageContaining("negates exactly");
    }

    // ------------------------------------------------------------------ shapes the books keep

    @Test
    @DisplayName("an expense row has a heading and no other row has one; a stock-out row names a reason from the closed list; a K0.00 row exists only for a cost")
    void theShapeOfARow() {
        assertThatThrownBy(() -> entry("EXPENSE", null, null, "10.00", "2026-10-01")).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> entry("REVENUE_GOODS", "RENT_UTILITIES", null, "10.00", "2026-10-01")).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> entry("STOCK_OUT", null, null, "10.00", "2026-10-01")).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> entry("STOCK_OUT", null, "SOMETHING_ELSE", "10.00", "2026-10-01")).hasMessageContaining("not a stock-out reason");
        assertThat(entry("STOCK_OUT", null, "WRITE_OFF_LOST", "10.00", "2026-10-01")).isPositive();
        assertThatThrownBy(() -> entry("EXPENSE", "RENT_UTILITIES", null, "0.00", "2026-10-01")).isInstanceOf(DataAccessException.class);
        assertThat(jdbc.update("INSERT INTO ledger_entry (entry_key, account, amount, occurred_at, effect_day, source, actor) "
                + "VALUES (?, 'COGS_GOODS', 0.00, now(), '2026-10-01', 'STOCK_COST', 'system:inventory')", "test:zero-cost:" + UUID.randomUUID())).isEqualTo(1);
    }

    @Test
    @DisplayName("an amount is ZMW only, an entry key happens once, and an amount finer than an ngwee does not exist")
    void currencyKeysAndNgwee() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO ledger_entry (entry_key, account, category, amount, currency, occurred_at, effect_day, source, actor) "
                + "VALUES ('test:usd', 'EXPENSE', 'RENT_UTILITIES', 1, 'USD', now(), '2026-10-01', 'EXPENSE', 'user:x')")).isInstanceOf(DataAccessException.class);
        String key = "test:once:" + UUID.randomUUID();
        String insert = "INSERT INTO ledger_entry (entry_key, account, category, amount, occurred_at, effect_day, source, actor) "
                + "VALUES (?, 'EXPENSE', 'RENT_UTILITIES', 5, now(), '2026-10-01', 'EXPENSE', 'user:x')";
        jdbc.update(insert, key);
        assertThatThrownBy(() -> jdbc.update(insert, key)).isInstanceOf(DataAccessException.class);
        long id = jdbc.queryForObject("INSERT INTO ledger_entry (entry_key, account, category, amount, occurred_at, effect_day, source, actor) "
                + "VALUES (?, 'EXPENSE', 'RENT_UTILITIES', 10.005, now(), '2026-10-01', 'EXPENSE', 'user:x') RETURNING entry_id", Long.class, "test:ngwee:" + UUID.randomUUID());
        assertThat(jdbc.queryForObject("SELECT amount FROM ledger_entry WHERE entry_id = ?", BigDecimal.class, id)).as("the column keeps ngwee: the service refuses finer before it gets here")
                .isEqualByComparingTo("10.01");
    }

    // ------------------------------------------------------------------ the closed lists

    @Test
    @DisplayName("the headings are the proposed closed list: posted ones cannot be typed, typed ones are keyable, and each says what it needs")
    void theExpenseHeadings() {
        List<String> keyable = jdbc.queryForList("SELECT code FROM expense_category WHERE hand_keyable ORDER BY code", String.class);
        List<String> postedOnly = jdbc.queryForList("SELECT code FROM expense_category WHERE posted AND NOT hand_keyable ORDER BY code", String.class);

        assertThat(keyable).contains("RENT_UTILITIES", "SALARIES_CASUAL_HELP", "BANK_MOBILE_ACCOUNT_FEES", "COURIER_FARE", "OTHER", "PAYMENT_CHARGES_ADJUSTMENT")
                .doesNotContain("PAYMENT_CHARGES", "CONSIGNMENT_PERIOD_COST");
        assertThat(postedOnly).as("the sending charge a customer bore on a refund is posted by the system, never typed (Phase 12)")
                .containsExactly("CONSIGNMENT_PERIOD_COST", "PAYMENT_CHARGES", "REFUND_FEE_RECOVERED");
        assertThat(jdbc.queryForObject("SELECT needs_order FROM expense_category WHERE code = 'COURIER_FARE'", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT needs_provider FROM expense_category WHERE code = 'BANK_MOBILE_ACCOUNT_FEES'", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT needs_confirmation FROM expense_category WHERE code = 'PAYMENT_CHARGES_ADJUSTMENT'", Boolean.class)).isTrue();
    }

    @Test
    @DisplayName("the only tax that can be entered is turnover tax; duty, surtax and import VAT are on the refused list with the plain reason, because they reach profit through the cost of goods")
    void theTaxLists() {
        assertThat(jdbc.queryForList("SELECT code FROM tax_type", String.class)).containsExactly("TURNOVER_TAX");
        assertThat(jdbc.queryForList("SELECT code FROM tax_type_refused ORDER BY code", String.class)).containsExactly("DUTY", "IMPORT_VAT", "SURTAX");
        assertThat(jdbc.queryForObject("SELECT plain_reason FROM tax_type_refused WHERE code = 'DUTY'", String.class)).contains("count it twice");
    }

    @Test
    @DisplayName("every account says whether it adds to profit, takes from it, or is only shown: held money and delivery given up are never profit")
    void theAccounts() {
        assertThat(jdbc.queryForObject("SELECT profit_sign FROM ledger_account WHERE code = 'REVENUE_GOODS'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT profit_sign FROM ledger_account WHERE code = 'COGS_GOODS'", Integer.class)).isEqualTo(-1);
        assertThat(jdbc.queryForObject("SELECT profit_sign FROM ledger_account WHERE code = 'MEMO_HELD_MONEY'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT profit_sign FROM ledger_account WHERE code = 'MEMO_DELIVERY_WAIVED'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT profit_sign FROM ledger_account WHERE code = 'MEMO_REFUNDS_OWED'", Integer.class))
                .as("a refund owed and not yet paid is shown, never profit (Phase 12)").isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_account", Integer.class)).isEqualTo(11);
    }

    // ------------------------------------------------------------------ Phase 12: refunds

    @Test
    @DisplayName("a row may come from a corrected sale, a payout, a payout taken back or a refund that was closed, and from nothing else new; the list has its own name")
    void theSources() {
        for (String source : List.of("SALE_CORRECTED", "PAID_OUT", "PAYOUT_VOIDED", "REFUND_CLOSED")) {
            assertThat(jdbc.update("""
                    INSERT INTO ledger_entry (entry_key, account, amount, occurred_at, effect_day, source, actor, correction_id, payout_id)
                    VALUES (?, 'MEMO_REFUNDS_OWED', 10.00, now(), DATE '2026-11-12', ?, 'system:test', ?, ?)""",
                    "test:" + UUID.randomUUID(), source, UUID.randomUUID(), UUID.randomUUID())).as(source).isEqualTo(1);
        }
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO ledger_entry (entry_key, account, amount, occurred_at, effect_day, source, actor)
                VALUES (?, 'MEMO_REFUNDS_OWED', 10.00, now(), DATE '2026-11-12', 'REFUND_GUESSED', 'system:test')""", "test:" + UUID.randomUUID()))
                .hasMessageContaining("ck_ledger_source");
    }

    @Test
    @DisplayName("the two lists V4 widened were replaced under names of their own, and exactly one constraint holds each")
    void theReplacedConstraintsHaveTheirNames() {
        assertThat(jdbc.queryForList("""
                SELECT conname FROM pg_constraint WHERE conrelid = 'ledger_entry'::regclass AND contype = 'c'
                   AND pg_get_constraintdef(oid) LIKE '%ORDER_PAID%'""", String.class)).containsExactly("ck_ledger_source");
        assertThat(jdbc.queryForList("""
                SELECT conname FROM pg_constraint WHERE conrelid = 'expense'::regclass AND contype = 'c'
                   AND pg_get_constraintdef(oid) LIKE '%ADD%REVERSAL%'""", String.class)).containsExactly("ck_expense_kind");
        assertThat(jdbc.queryForObject("""
                SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'ck_expense_kind'""", String.class))
                .as("a sending charge the customer bore is recorded as a recovery").contains("RECOVERY");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_name = 'expense' AND column_name = 'linked_payout_id'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("a returned piece written off is a reason stock can leave for; a marked-down relist is not (it recognises no cost)")
    void theStockOutReasons() {
        assertThat(jdbc.queryForList("SELECT code FROM stock_out_reason", String.class)).contains("WRITE_OFF_RETURNED").doesNotContain("RELISTED_MARKED_DOWN");
        assertThat(entry("STOCK_OUT", null, "WRITE_OFF_RETURNED", "65.50", "2026-11-12")).isPositive();
    }

    @Test
    @DisplayName("a payout that was taken back says when, and one that stands does not; a payout is recorded once")
    void theRefundFacts() {
        String payout = """
                INSERT INTO payout_fact (payout_id, kind, purpose, debt_id, order_id, confirmation_id, method, gross, customer_fee, send_charge, paid_at, voided_at,
                                         actor, source_event_id)
                VALUES (?, ?, 'REFUND', ?, ?, ?, 'MTN', 180.00, 2.50, 2.50, now(), ?, 'user:kc-test', ?)""";
        UUID id = UUID.randomUUID();
        java.sql.Timestamp voided = java.sql.Timestamp.from(java.time.Instant.now());
        assertThat(jdbc.update(payout, id, "PAID_OUT", UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null, UUID.randomUUID())).isEqualTo(1);
        assertThat(jdbc.update(payout, id, "VOIDED", UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), voided, UUID.randomUUID())).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update(payout, UUID.randomUUID(), "VOIDED", UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null, UUID.randomUUID()))
                .hasMessageContaining("payout_fact_voided_dated");
        assertThatThrownBy(() -> jdbc.update(payout, UUID.randomUUID(), "PAID_OUT", UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), voided, UUID.randomUUID()))
                .hasMessageContaining("payout_fact_voided_dated");
        assertThatThrownBy(() -> jdbc.update(payout, id, "PAID_OUT", UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null, UUID.randomUUID()))
                .as("a payout is recorded once").isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    }
}
