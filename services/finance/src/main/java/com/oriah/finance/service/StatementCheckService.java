package com.oriah.finance.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.oriah.finance.config.BusinessCalendar;
import com.oriah.finance.dto.StatementCheckRequest;
import com.oriah.finance.dto.StatementCheckResponse;
import com.oriah.finance.exception.FinanceProblem;

/**
 * What each payment provider's own statement says it charged in fees for a month, typed in by the bookkeeper so the month can be reconciled (the profit report shows the gap
 * against what finance recorded, to the ngwee). Every entry is kept and none is ever changed; the latest for a month and provider is the one that counts.
 */
@Service
public class StatementCheckService {

    private final JdbcTemplate jdbc;
    private final BusinessCalendar calendar;
    private final Clock clock;

    public StatementCheckService(JdbcTemplate jdbc, BusinessCalendar calendar, Clock clock) {
        this.jdbc = jdbc;
        this.calendar = calendar;
        this.clock = clock;
    }

    @Transactional
    public StatementCheckResponse add(String actor, StatementCheckRequest r) {
        LocalDate month = parseMonth(r.month());
        if (month.isAfter(BusinessCalendar.firstOfMonth(calendar.today(clock.instant())))) {
            throw new FinanceProblem(HttpStatus.BAD_REQUEST, "invalid-month", "A statement is for a month that has begun");
        }
        FreeText.noPhoneNumbers(r.note(), "the note");
        Long id = jdbc.queryForObject("INSERT INTO statement_check (month, method, statement_fees, note, entered_by) VALUES (?, ?, ?, ?, ?) RETURNING id", Long.class, month, r.method(),
                r.statementFees(), r.note(), actor);
        return jdbc.query("SELECT id, month, method, statement_fees, note, entered_by, entered_at FROM statement_check WHERE id = ?", (rs, n) -> map(rs), id).get(0);
    }

    public List<StatementCheckResponse> list(String month) {
        return jdbc.query("SELECT id, month, method, statement_fees, note, entered_by, entered_at FROM statement_check WHERE month = ? ORDER BY id", (rs, n) -> map(rs), parseMonth(month));
    }

    /** One entry per provider for the month: the latest. */
    public List<StatementCheckResponse> latest(String month) {
        return jdbc.query("""
                SELECT DISTINCT ON (method) id, month, method, statement_fees, note, entered_by, entered_at FROM statement_check WHERE month = ? ORDER BY method, id DESC""",
                (rs, n) -> map(rs), parseMonth(month));
    }

    private static StatementCheckResponse map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new StatementCheckResponse(rs.getLong("id"), YearMonth.from(rs.getDate("month").toLocalDate()).toString(), rs.getString("method"), rs.getBigDecimal("statement_fees"), rs.getString("note"),
                rs.getString("entered_by"), rs.getTimestamp("entered_at").toInstant());
    }

    private static LocalDate parseMonth(String month) {
        try {
            return YearMonth.parse(month).atDay(1);
        } catch (DateTimeParseException e) {
            throw new FinanceProblem(HttpStatus.BAD_REQUEST, "invalid-month", "A month is written year-month, for example 2026-10");
        }
    }
}
