package com.oriah.notification.alerts;

import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.oriah.notification.MutableClock;
import com.oriah.notification.ServiceTest;
import com.oriah.notification.TestData;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The rules the database itself holds for an alert (V8), each asserted by the NAME of the rule that refused the row.
 */
@ServiceTest
class StaffAlertRulesTest {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MutableClock clock;
    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void clean() {
        TestData.clean(jdbc, clock);
    }

    private Map<String, Object> alert() {
        Map<String, Object> columns = new LinkedHashMap<>();
        columns.put("id", UUID.randomUUID());
        columns.put("kind", "PAYMENT_TO_CONFIRM");
        columns.put("subject_id", UUID.randomUUID().toString());
        columns.put("audience", "MONEY");
        columns.put("state", "OPEN");
        columns.put("summary", "s".repeat(300));
        columns.put("detail", "d");
        columns.put("raised_at", Timestamp.from(MutableClock.START));
        return columns;
    }

    private Map<String, Object> with(Map<String, Object> row, String column, Object value) {
        Map<String, Object> changed = new LinkedHashMap<>(row);
        changed.put(column, value);
        return changed;
    }

    private void insert(Map<String, Object> row) {
        jdbc.update("INSERT INTO staff_alert (" + String.join(", ", row.keySet()) + ") VALUES ("
                + String.join(", ", row.keySet().stream().map(c -> "?").toList()) + ")", row.values().toArray());
    }

    private void refused(String rule, Map<String, Object> row) {
        assertThatThrownBy(() -> insert(row)).isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining(rule);
    }

    @Test
    @DisplayName("one open alert per thing; once it has ended, the same thing may alert again; a one-line summary may be long")
    void oneOpenAlertPerThing() {
        Map<String, Object> open = alert();
        insert(open);
        refused("uq_staff_alert_open", with(open, "id", UUID.randomUUID()));

        jdbc.update("UPDATE staff_alert SET state = 'RESOLVED', resolved_at = now(), resolved_how = 'PAID' WHERE id = ?", open.get("id"));
        insert(with(open, "id", UUID.randomUUID()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM staff_alert", Long.class)).isEqualTo(2);
    }

    @Test
    @DisplayName("an ended alert says when and how, an open one says neither, and 'how' is one of the known words")
    void aResolvedAlertSaysHow() {
        refused("staff_alert_resolved_whole", with(alert(), "state", "RESOLVED"));
        refused("staff_alert_resolved_whole", with(alert(), "resolved_how", "PAID"));
        refused("staff_alert_resolved_whole", with(with(alert(), "state", "RESOLVED"), "resolved_at", Timestamp.from(MutableClock.START)));
        refused("staff_alert_resolved_how_known",
                with(with(with(alert(), "state", "RESOLVED"), "resolved_at", Timestamp.from(MutableClock.START)), "resolved_how", "SOMEHOW"));
        refused("staff_alert_state_known", with(alert(), "state", "SNOOZED"));
    }

    @Test
    @DisplayName("a reminder has its words; the group told follows the kind; only a payment claim can be beyond the email limit")
    void theOtherRules() {
        refused("staff_alert_reminder_has_words", with(alert(), "remind_at", Timestamp.from(MutableClock.START)));
        refused("staff_alert_audience_by_kind", with(alert(), "audience", "COST"));
        refused("staff_alert_audience_by_kind", with(with(alert(), "kind", "BELOW_COST"), "audience", "MONEY"));
        refused("staff_alert_kind_known", with(alert(), "kind", "SOMETHING_ELSE"));
        refused("staff_alert_only_a_claim_is_limited", with(with(alert(), "kind", "SETTLEMENT_STUCK"), "email_limited", true));
        insert(with(alert(), "email_limited", true));
    }

    @Test
    @DisplayName("an email about an alert must point at a real alert")
    void aStaffRowMustPointAtARealAlert() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO notification_log (id, event_type, channel, subject, body, recorded_at, recipient_kind, audience, staff_to, status,
                                              status_at, next_attempt_at, send_before, alert_id, alert_notice, alert_tell_no)
                VALUES (?, 'alert.payment_to_confirm', 'email', 's', 'b', now(), 'STAFF', 'MONEY', 'owner@oriah.local', 'RECORDED', now(), now(), now(),
                        ?, 'FIRST', 1)""", UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("fk_notification_log_alert");
    }
}
