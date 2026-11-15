package com.oriah.finance.ledger;

import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.oriah.events.EventJson;

/**
 * Things that do not add up, kept as an append-only log. An anomaly is OPEN while the latest row for its (code, subject) is a RAISE; raising one that is already open adds
 * nothing, and the same thing can be closed and happen again. Detail holds ids, amounts and codes only: never a value a person typed, never a name or a phone.
 */
@Component
public class AnomalyService {

    public enum Severity { INFO, WARN, ERROR }

    private final JdbcTemplate jdbc;

    public AnomalyService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Raises an anomaly unless it is already open. @return whether a row was written */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean raise(String code, String subject, Severity severity, Map<String, Object> detail, UUID sourceEventId) {
        if (isOpen(code, subject)) {
            return false;
        }
        jdbc.update("INSERT INTO anomaly_log (code, subject, action, severity, detail, source_event_id) VALUES (?, ?, 'RAISE', ?, CAST(? AS jsonb), ?)",
                code, subject, severity.name(), EventJson.write(detail == null ? Map.of() : detail), sourceEventId);
        return true;
    }

    /** Closes an anomaly the system itself has put right (an event that was refused has since been applied). @return whether one was open */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean autoResolve(String code, String subject) {
        if (!isOpen(code, subject)) {
            return false;
        }
        String severity = jdbc.queryForObject("SELECT severity FROM anomaly_log WHERE code = ? AND subject = ? ORDER BY id DESC LIMIT 1", String.class, code, subject);
        jdbc.update("INSERT INTO anomaly_log (code, subject, action, severity) VALUES (?, ?, 'AUTO_RESOLVE', ?)", code, subject, severity);
        return true;
    }

    public boolean isOpen(String code, String subject) {
        String latest = jdbc.query("SELECT action FROM anomaly_log WHERE code = ? AND subject = ? ORDER BY id DESC LIMIT 1",
                (rs, n) -> rs.getString(1), code, subject).stream().findFirst().orElse(null);
        return "RAISE".equals(latest);
    }
}
