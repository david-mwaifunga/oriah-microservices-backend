package com.oriah.notification.model;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What V4 does to the message table, proved on a real Postgres migrated to version 4 and no further (so no later migration changes what this
 * proves).
 *
 * <p>Two promises. First: every row that existed before V4 was only ever a line in a log, and none of them may ever be emailed; they become
 * "not sent, recorded before sending existed" and the sender's own query never finds one. Second: a row that makes no sense cannot be written.
 * Each refusal is asserted by the NAME of the rule that refused it, so a test cannot pass because some other rule happened to object.
 */
class MessageStatusMigrationTest {

    private static final Instant THEN = Instant.parse("2026-11-01T08:00:00Z");
    private static final Instant NOW = Instant.parse("2026-11-09T07:31:00Z");

    private static PostgreSQLContainer postgres;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void migrateToVersionThreeThenFour() {
        postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));
        postgres.start();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));

        flyway("3").migrate();
        // Three rows as the service wrote them before Phase 11: an owner, a type, the words, and the moment they were written down.
        for (int i = 1; i <= 3; i++) {
            jdbc.update("""
                    INSERT INTO notification_log (id, order_id, keycloak_id, event_type, channel, subject, body, delivered_at)
                    VALUES (?, ?, 'kc-alice', 'order.paid', 'email', 'Payment received', 'old words', ?)""",
                    UUID.randomUUID(), UUID.randomUUID(), Timestamp.from(THEN.plusSeconds(i)));
        }
        flyway("4").migrate();
    }

    private static Flyway flyway(String target) {
        return Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration").target(target).load();
    }

    @AfterAll
    static void stop() {
        postgres.stop();
    }

    @BeforeEach
    void keepOnlyTheOldRows() {
        jdbc.update("DELETE FROM notification_log WHERE status_reason IS DISTINCT FROM 'RECORDED_BEFORE_SENDING'");
    }

    @Test
    @DisplayName("every row from before V4 becomes 'not sent, recorded before sending existed', keeps its time, and is never due to be sent")
    void rowsFromBeforeV4BecomeNotSentAndAreNeverDue() {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM notification_log ORDER BY recorded_at");

        assertThat(rows).hasSize(3);
        for (int i = 0; i < 3; i++) {
            Map<String, Object> row = rows.get(i);
            assertThat(row.get("recipient_kind")).isEqualTo("SHOPPER");
            assertThat(row.get("status")).isEqualTo("NOT_SENT");
            assertThat(row.get("status_reason")).isEqualTo("RECORDED_BEFORE_SENDING");
            assertThat(((Timestamp) row.get("recorded_at")).toInstant()).isEqualTo(THEN.plusSeconds(i + 1));
            assertThat(row.get("status_at")).isEqualTo(row.get("recorded_at"));
            assertThat(row.get("next_attempt_at")).isNull();
            assertThat(row.get("send_before")).isNull();
            assertThat(row.get("attempts")).isEqualTo(0);
        }
        // The sender's question, asked a year later: nothing.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_log WHERE status = 'RECORDED' AND next_attempt_at <= now() + interval '1 year'",
                Long.class)).isZero();
    }

    @Test
    @DisplayName("a new row must say what it is: there is no default status, recipient or time")
    void statusHasNoDefault() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO notification_log (id, order_id, keycloak_id, event_type, channel, subject, body, recorded_at)
                VALUES (?, ?, 'kc-alice', 'order.paid', 'email', 's', 'b', ?)""", UUID.randomUUID(), UUID.randomUUID(), Timestamp.from(NOW)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns WHERE table_name = 'notification_log'
                   AND column_name IN ('status', 'recipient_kind', 'status_at', 'recorded_at') AND column_default IS NOT NULL""", Long.class)).isZero();
    }

    @Test
    @DisplayName("a shopper's row needs its owner, and nobody else's row has one")
    void aShoppersRowNeedsItsOwnerAndNobodyElsesHasOne() {
        refused("notification_log_recipient_shape", row().with("keycloak_id", null));
        refused("notification_log_recipient_shape", caller().with("keycloak_id", "kc-alice"));
        refused("notification_log_recipient_shape", staff().with("keycloak_id", "kc-maya"));
        refused("notification_log_recipient_shape", caller().with("order_id", null));
        refused("notification_log_recipient_known", row().with("recipient_kind", "ROBOT"));
        accepted(row());
        accepted(caller());
        accepted(staff());
    }

    @Test
    @DisplayName("only a message to staff names a mailbox, and it says which of the two groups it is for")
    void onlyAStaffRowNamesAMailbox() {
        refused("notification_log_recipient_shape", row().with("staff_to", "owner@oriah.local"));
        refused("notification_log_recipient_shape", caller().with("staff_to", "owner@oriah.local"));
        refused("notification_log_recipient_shape", staff().with("audience", null));
        refused("notification_log_recipient_shape", staff().with("audience", "EVERYONE"));
        refused("notification_log_recipient_shape", row().with("audience", "MONEY"));
        refused("notification_log_channel_known", row().with("channel", "sms"));
    }

    @Test
    @DisplayName("a staff row's type says which alert it is about, and no other row's type looks like an alert")
    void aStaffRowsTypeSaysWhichAlert() {
        refused("notification_log_staff_event_type", staff().with("event_type", "order.paid"));
        refused("notification_log_staff_event_type", row().with("event_type", "alert.payment_to_confirm"));
    }

    @Test
    @DisplayName("words for a caller are always 'by hand', and nobody else's are")
    void aCallersRowIsAlwaysByHand() {
        refused("notification_log_by_hand_only_for_a_caller", caller().with("status", "NOT_SENT").with("status_reason", "BELL_ONLY"));
        refused("notification_log_status_shape", row().with("status", "BY_HAND").with("next_attempt_at", null).with("send_before", null));
        refused("notification_log_status_shape", caller().with("status_reason", "BELL_ONLY"));
    }

    @Test
    @DisplayName("a row waiting to be sent has its next try and its deadline, and no reason")
    void aRecordedRowNeedsItsNextTryAndItsDeadline() {
        refused("notification_log_status_shape", row().with("next_attempt_at", null));
        refused("notification_log_status_shape", row().with("send_before", null));
        refused("notification_log_status_shape", row().with("status_reason", "BELL_ONLY"));
        refused("notification_log_status_shape", row().with("sent_at", Timestamp.from(NOW)));
        refused("notification_log_status_known", row().with("status", "DELIVERED"));
    }

    @Test
    @DisplayName("a sent row has the time the mail server took it, no next try and no reason")
    void aSentRowHasItsTimeAndNoReason() {
        Row sent = row().with("status", "SENT").with("next_attempt_at", null).with("sent_at", Timestamp.from(NOW));
        accepted(sent);
        refused("notification_log_status_shape", sent.with("sent_at", null));
        refused("notification_log_status_shape", sent.with("next_attempt_at", Timestamp.from(NOW)));
        refused("notification_log_status_shape", sent.with("status_reason", "GAVE_UP"));
    }

    @Test
    @DisplayName("a failed row says why (gave up, address refused, outcome unknown); a row not sent says why too, and the two lists do not mix")
    void aFailedRowSaysWhy() {
        Row failed = row().with("status", "FAILED").with("next_attempt_at", null);
        for (String reason : List.of("GAVE_UP", "ADDRESS_REFUSED", "OUTCOME_UNKNOWN")) {
            accepted(failed.with("status_reason", reason));
            refused("notification_log_status_shape", row().with("status", "NOT_SENT").with("next_attempt_at", null).with("status_reason", reason));
        }
        refused("notification_log_status_shape", failed);
        refused("notification_log_status_shape", failed.with("status_reason", "BELL_ONLY"));
        refused("notification_log_status_shape", row().with("status", "NOT_SENT").with("next_attempt_at", null));
        refused("notification_log_reason_known", row().with("status", "NOT_SENT").with("next_attempt_at", null).with("status_reason", "BECAUSE"));
        accepted(row().with("status", "NOT_SENT").with("next_attempt_at", null).with("status_reason", "BELL_ONLY"));
    }

    @Test
    @DisplayName("a notice of an alert has its number, belongs to staff, and is one of the six kinds")
    void aNoticeHasItsNumber() {
        refused("notification_log_notice_whole", staff().with("alert_notice", "FIRST"));
        refused("notification_log_notice_whole", staff().with("alert_tell_no", 1));
        refused("notification_log_notice_whole", staff().with("alert_notice", "FIRST").with("alert_tell_no", 0));
        refused("notification_log_notice_known", staff().with("alert_notice", "SHOUT").with("alert_tell_no", 1));
        refused("notification_log_notice_known", row().with("alert_notice", "FIRST").with("alert_tell_no", 1));
        refused("notification_log_alert_has_notice", staff().with("alert_id", UUID.randomUUID()));
        refused("notification_log_alert_has_notice", staff().with("alert_id", UUID.randomUUID()).with("alert_notice", "DIGEST").with("alert_tell_no", 1));
        accepted(staff().with("alert_id", UUID.randomUUID()).with("alert_notice", "FIRST").with("alert_tell_no", 1));
        accepted(staff().with("alert_notice", "DIGEST").with("alert_tell_no", 1));
    }

    @Test
    @DisplayName("only a shopper's row that was sent can be marked as the short note; 'may have arrived' needs a try; tries are never negative")
    void onlyASentShopperRowCanBeShort() {
        refused("notification_log_short_form_only_when_sent", row().with("short_form", true));
        refused("notification_log_short_form_only_when_sent",
                staff().with("status", "SENT").with("next_attempt_at", null).with("sent_at", Timestamp.from(NOW)).with("short_form", true));
        accepted(row().with("status", "SENT").with("next_attempt_at", null).with("sent_at", Timestamp.from(NOW)).with("short_form", true));
        refused("notification_log_maybe_only_after_a_try", row().with("maybe_handed_over", true));
        accepted(row().with("maybe_handed_over", true).with("attempts", 1));
        refused("notification_log_attempts_not_negative", row().with("attempts", -1));
    }

    @Test
    @DisplayName("one event makes one message row")
    void oneEventIsOneRow() {
        UUID event = UUID.randomUUID();
        accepted(row().with("event_id", event));
        refused("uq_notification_log_event", row().with("event_id", event));
    }

    @Test
    @DisplayName("one telling of one notice of one alert goes to one mailbox once, and an empty mailbox counts as one too")
    void oneTellingOfOneNoticeGoesToOneMailboxOnce() {
        UUID alert = UUID.randomUUID();
        Row first = staff().with("alert_id", alert).with("alert_notice", "FIRST").with("alert_tell_no", 1);
        accepted(first);
        refused("uq_notification_log_alert_notice", first);
        accepted(first.with("staff_to", "manager@oriah.local"));
        accepted(first.with("alert_tell_no", 2).with("alert_notice", "AGAIN"));
        accepted(first.with("alert_notice", "REMINDER"));

        Row nobody = staff().with("alert_id", UUID.randomUUID()).with("alert_notice", "FIRST").with("alert_tell_no", 1).with("staff_to", null)
                .with("status", "NOT_SENT").with("status_reason", "NO_RECIPIENT_CONFIGURED").with("next_attempt_at", null);
        accepted(nobody);
        refused("uq_notification_log_alert_notice", nobody);
    }

    // ---- rows ------------------------------------------------------------------------------------------------------------------------------

    /** A row as a column map; {@code with} returns a changed copy, so one good row can be bent one way at a time. */
    private record Row(Map<String, Object> columns) {
        Row with(String column, Object value) {
            Map<String, Object> changed = new java.util.LinkedHashMap<>(columns);
            changed.put(column, value);
            return new Row(changed);
        }
    }

    /** A shopper's message waiting to be sent: the commonest good row. */
    private static Row row() {
        Map<String, Object> columns = new java.util.LinkedHashMap<>();
        columns.put("order_id", UUID.randomUUID());
        columns.put("keycloak_id", "kc-alice");
        columns.put("event_type", "order.paid");
        columns.put("channel", "email");
        columns.put("subject", "s");
        columns.put("body", "b");
        columns.put("recorded_at", Timestamp.from(NOW));
        columns.put("recipient_kind", "SHOPPER");
        columns.put("status", "RECORDED");
        columns.put("status_at", Timestamp.from(NOW));
        columns.put("next_attempt_at", Timestamp.from(NOW));
        columns.put("send_before", Timestamp.from(NOW.plusSeconds(21600)));
        return new Row(columns);
    }

    /** Words for a person to send to a caller. */
    private static Row caller() {
        return row().with("keycloak_id", null).with("recipient_kind", "CALLER").with("channel", "by-hand").with("status", "BY_HAND")
                .with("next_attempt_at", null).with("send_before", null);
    }

    /** A message to a shop mailbox about an alert, waiting to be sent. */
    private static Row staff() {
        return row().with("keycloak_id", null).with("recipient_kind", "STAFF").with("audience", "MONEY").with("staff_to", "owner@oriah.local")
                .with("event_type", "alert.payment_to_confirm");
    }

    private static void insert(Row row) {
        Map<String, Object> columns = new java.util.LinkedHashMap<>(row.columns());
        columns.put("id", UUID.randomUUID());
        String names = String.join(", ", columns.keySet());
        String marks = String.join(", ", columns.keySet().stream().map(name -> "?").toList());
        jdbc.update("INSERT INTO notification_log (" + names + ") VALUES (" + marks + ")", columns.values().toArray());
    }

    private static void accepted(Row row) {
        insert(row);
    }

    private static void refused(String ruleName, Row row) {
        assertThatThrownBy(() -> insert(row)).isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining(ruleName);
    }
}
