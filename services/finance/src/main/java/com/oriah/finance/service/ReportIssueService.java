package com.oriah.finance.service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.oriah.events.EventJson;
import com.oriah.finance.config.BusinessCalendar;
import com.oriah.finance.dto.IssueRequest;
import com.oriah.finance.dto.IssueResponse;
import com.oriah.finance.dto.IssueSummary;
import com.oriah.finance.dto.ProductReportResponse;
import com.oriah.finance.dto.ProfitReportResponse;
import com.oriah.finance.exception.FinanceProblem;
import com.oriah.finance.ledger.Locks;

/**
 * Reporting a month. It takes the ledger lock EXCLUSIVELY: it waits for every writer in flight, so everything finance has recorded by the moment it stores is committed, and
 * anything written afterwards is stamped later. It then stores the whole month as it was (the report and the table by product) with that moment as its {@code asOf}: that
 * stored snapshot is the evidence of what was reported, and anything recorded after it shows as a change since.
 *
 * <p>A month cannot be reported until it has ended and the grace has passed (a sale keyed late, within the 72 hours the shop allows, belongs to it). Until the books are
 * complete (a catch-up still running, an open error, a cost no product can be named for) it is refused and says why, unless the person accepts the problem, which is kept with the
 * issue. A second issue of a month that has changed since the first needs a note.
 */
@Service
public class ReportIssueService {

    private final JdbcTemplate jdbc;
    private final ProfitReportService reports;
    private final Locks locks;
    private final BusinessCalendar calendar;
    private final Clock clock;
    private final Duration grace;

    public ReportIssueService(JdbcTemplate jdbc, ProfitReportService reports, Locks locks, BusinessCalendar calendar, Clock clock,
                              @Value("${oriah.finance.issue-grace:PT72H}") Duration grace) {
        this.jdbc = jdbc;
        this.reports = reports;
        this.locks = locks;
        this.calendar = calendar;
        this.clock = clock;
        this.grace = grace;
    }

    @Transactional
    public IssueResponse issue(String actor, String month, IssueRequest request) {
        YearMonth ym = ProfitReportService.parse(month);
        FreeText.noPhoneNumbers(request.note(), "the note");
        locks.issuer();
        Instant now = clock.instant();
        if (now.isBefore(calendar.endOf(ym).plus(grace))) {
            throw new FinanceProblem(HttpStatus.CONFLICT, "month-not-finished", "The month of " + ym + " cannot be reported until it has ended and " + grace.toHours()
                    + " hours have passed, so a sale keyed late is in it");
        }
        Instant asOf = jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant();
        ProfitReportResponse report = reports.month(ym, asOf);
        ProductReportResponse products = reports.allProducts(ym, asOf);
        List<String> reasons = new ArrayList<>();
        if (!report.status().catchUpComplete()) {
            reasons.add("finance has not yet read every service's whole history (the catch-up is not complete)");
        }
        if (report.status().openErrors() > 0) {
            reasons.add(report.status().openErrors() + " error(s) in the books are open (see the anomalies)");
        }
        if (report.status().notTiedToProduct().signum() != 0) {
            reasons.add("K" + report.status().notTiedToProduct().toPlainString() + " of this month's revenue or cost is not tied to a product");
        }
        if (!reasons.isEmpty() && !Boolean.TRUE.equals(request.acknowledgeIncomplete())) {
            throw new FinanceProblem(HttpStatus.CONFLICT, "books-incomplete", "The books show a problem: " + String.join("; ", reasons)
                    + ". Put it right, or report the month anyway and say that you accept it").with("reasons", reasons);
        }
        Integer lastNo = jdbc.queryForObject("SELECT max(issue_no) FROM report_issue WHERE month = ?", Integer.class, ym.atDay(1));
        if (lastNo != null) {
            Timestamp lastAsOf = jdbc.queryForObject("SELECT as_of FROM report_issue WHERE month = ? AND issue_no = ?", Timestamp.class, ym.atDay(1), lastNo);
            Long changed = jdbc.queryForObject("SELECT count(*) FROM ledger_entry WHERE effect_month = ? AND recorded_at > ? AND recorded_at <= ?", Long.class, ym.atDay(1), lastAsOf, Timestamp.from(asOf));
            if (changed != null && changed > 0 && (request.note() == null || request.note().isBlank())) {
                throw new FinanceProblem(HttpStatus.BAD_REQUEST, "issue-note-required", "The month has changed since it was last reported (" + changed + " row(s)): say why it is reported again");
            }
        }
        int issueNo = lastNo == null ? 1 : lastNo + 1;
        IssueResponse.Snapshot snapshot = new IssueResponse.Snapshot(report, products);
        jdbc.update("""
                INSERT INTO report_issue (issue_id, month, issue_no, as_of, issued_at, issued_by, note, incomplete_reasons, snapshot) VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb))""",
                UUID.randomUUID(), ym.atDay(1), issueNo, Timestamp.from(asOf), Timestamp.from(asOf), actor, request.note(), reasons.isEmpty() ? null : EventJson.write(reasons), EventJson.write(snapshot));
        return new IssueResponse(issueNo, ym.toString(), asOf, asOf, actor, request.note(), reasons, snapshot);
    }

    @Transactional(readOnly = true)
    public List<IssueSummary> issues(String month) {
        YearMonth ym = ProfitReportService.parse(month);
        return jdbc.query("SELECT issue_no, as_of, issued_at, issued_by, note, incomplete_reasons::text FROM report_issue WHERE month = ? ORDER BY issue_no",
                (rs, n) -> new IssueSummary(rs.getInt(1), ym.toString(), rs.getTimestamp(2).toInstant(), rs.getTimestamp(3).toInstant(), rs.getString(4), rs.getString(5),
                        rs.getString(6) == null ? List.of() : EventJson.mapper().readValue(rs.getString(6), new tools.jackson.core.type.TypeReference<List<String>>() { })), ym.atDay(1));
    }
}
