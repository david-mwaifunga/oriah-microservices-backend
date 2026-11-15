package com.oriah.finance.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.oriah.events.EventJson;
import com.oriah.finance.dto.AnomalyResponse;
import com.oriah.finance.dto.ReverifyResponse;
import com.oriah.finance.dto.StatusResponse;
import com.oriah.finance.exception.FinanceProblem;
import com.oriah.finance.ledger.Locks;

/**
 * Whether the books can be trusted: the catch-up of every producer, the anomalies that are open, acknowledging one, and re-reading a producer's history.
 *
 * <p>A catch-up that has finished never asks again by itself, so an event that was refused and has since been fixed (or a history older than Kafka's retention) is applied only by a
 * RE-VERIFY: it marks the producer's catch-up as not started, and at the next start finance asks for the whole history again. Reading it again is harmless: an event already applied
 * is applied once, whichever stream brings it.
 */
@Service
public class OperationsService {

    private final JdbcTemplate jdbc;
    private final Locks locks;

    public OperationsService(JdbcTemplate jdbc, Locks locks) {
        this.jdbc = jdbc;
        this.locks = locks;
    }

    // ------------------------------------------------------------------ status

    @Transactional(readOnly = true)
    public StatusResponse status() {
        List<StatusResponse.Producer> producers = jdbc.query("SELECT producer, replayed_through, completed_through, completed_at FROM outbox_catch_up ORDER BY producer",
                (rs, n) -> new StatusResponse.Producer(rs.getString(1), rs.getLong(2), (Long) rs.getObject(3), rs.getTimestamp(4) == null ? null : rs.getTimestamp(4).toInstant(), rs.getTimestamp(4) != null));
        boolean complete = !producers.isEmpty() && producers.stream().allMatch(StatusResponse.Producer::complete);
        return new StatusResponse(producers, complete, open("ERROR"), open("WARN"), jdbc.queryForObject("SELECT count(*) FROM skipped_fact", Long.class),
                jdbc.queryForObject("SELECT count(*) FROM ledger_entry", Long.class));
    }

    private int open(String severity) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM (SELECT DISTINCT ON (code, subject) action, severity FROM anomaly_log ORDER BY code, subject, id DESC) a WHERE action = 'RAISE' AND severity = ?",
                Integer.class, severity);
        return n == null ? 0 : n;
    }

    // ------------------------------------------------------------------ anomalies

    @Transactional(readOnly = true)
    public List<AnomalyResponse> anomalies(String status) {
        if (status != null && !status.isBlank() && !"OPEN".equals(status) && !"ALL".equals(status)) {
            throw new FinanceProblem(HttpStatus.BAD_REQUEST, "invalid-status", "status is OPEN or ALL");
        }
        boolean onlyOpen = status == null || status.isBlank() || "OPEN".equals(status);
        return query(onlyOpen ? "WHERE l.action = 'RAISE'" : "", List.of());
    }

    private List<AnomalyResponse> query(String where, List<Object> args) {
        return jdbc.query("""
                SELECT r.id, l.code, l.subject, l.action, l.severity, r.detail::text, r.at, l.by_actor, l.at, l.note
                  FROM (SELECT DISTINCT ON (code, subject) * FROM anomaly_log ORDER BY code, subject, id DESC) l
                  JOIN (SELECT DISTINCT ON (code, subject) id, code, subject, detail, at FROM anomaly_log WHERE action = 'RAISE' ORDER BY code, subject, id DESC) r
                    ON r.code = l.code AND r.subject = l.subject""" + " " + where +" ORDER BY r.at DESC, r.id DESC LIMIT 500",
                (rs, n) -> {
                    String action = rs.getString(4);
                    String state = "RAISE".equals(action) ? "OPEN" : "ACK".equals(action) ? "ACKNOWLEDGED" : "RESOLVED";
                    boolean acked = "ACK".equals(action);
                    return new AnomalyResponse(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(5), state, detail(rs.getString(6)), rs.getTimestamp(7).toInstant(),
                            acked ? rs.getString(8) : null, acked ? rs.getTimestamp(9).toInstant() : null, acked ? rs.getString(10) : null);
                }, args.toArray());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> detail(String json) {
        return json == null ? Map.of() : EventJson.mapper().readValue(json, Map.class);
    }

    @Transactional
    public AnomalyResponse acknowledge(String actor, long id, String note) {
        FreeText.noPhoneNumbers(note, "the note");
        locks.writer();
        List<Map<String, Object>> raised = jdbc.queryForList("SELECT code, subject, severity FROM anomaly_log WHERE id = ? AND action = 'RAISE'", id);
        if (raised.isEmpty()) {
            throw new FinanceProblem(HttpStatus.NOT_FOUND, "anomaly-not-found", "There is no anomaly " + id);
        }
        String code = (String) raised.get(0).get("code");
        String subject = (String) raised.get(0).get("subject");
        Long latest = jdbc.queryForObject("SELECT max(id) FROM anomaly_log WHERE code = ? AND subject = ?", Long.class, code, subject);
        String latestAction = jdbc.queryForObject("SELECT action FROM anomaly_log WHERE id = ?", String.class, latest);
        if (latest == null || latest != id || !"RAISE".equals(latestAction)) {
            throw new FinanceProblem(HttpStatus.CONFLICT, "anomaly-not-open", "That anomaly is not open: it was acknowledged, resolved, or has happened again since (acknowledge the newer one)");
        }
        jdbc.update("INSERT INTO anomaly_log (code, subject, action, severity, by_actor, note) VALUES (?, ?, 'ACK', ?, ?, ?)", code, subject, raised.get(0).get("severity"), actor, note);
        return query("WHERE l.code = ? AND l.subject = ?", List.of(code, subject)).get(0);
    }

    // ------------------------------------------------------------------ re-verify

    @Transactional
    public ReverifyResponse reverify(String producer) {
        locks.writer();
        List<String> known = jdbc.queryForList("SELECT producer FROM outbox_catch_up ORDER BY producer", String.class);
        List<String> chosen = new ArrayList<>();
        if (producer == null || producer.isBlank()) {
            chosen.addAll(known);
        } else if (known.contains(producer)) {
            chosen.add(producer);
        } else {
            throw new FinanceProblem(HttpStatus.BAD_REQUEST, "unknown-producer", "There is no catch-up for '" + producer + "': it is one of " + String.join(", ", known));
        }
        for (String p : chosen) {
            jdbc.update("UPDATE outbox_catch_up SET replayed_through = 0, completed_through = NULL, completed_at = NULL WHERE producer = ?", p);
        }
        return new ReverifyResponse(chosen, "at the next start of finance: it asks " + (chosen.size() == 1 ? "that producer" : "each of them") + " for the whole history again; an event already applied is applied once");
    }
}
