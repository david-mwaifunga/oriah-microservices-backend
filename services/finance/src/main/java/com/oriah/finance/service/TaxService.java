package com.oriah.finance.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.oriah.finance.config.BusinessCalendar;
import com.oriah.finance.dto.TaxCorrectionRequest;
import com.oriah.finance.dto.TaxEntryRequest;
import com.oriah.finance.dto.TaxEntryResponse;
import com.oriah.finance.dto.TaxTypeResponse;
import com.oriah.finance.exception.FinanceProblem;
import com.oriah.finance.ledger.LedgerWriter;
import com.oriah.finance.ledger.LedgerWriter.Entry;
import com.oriah.finance.ledger.Locks;

/**
 * Tax paid to ZRA, typed in by hand (nothing calculates it in the MVP). It is booked to the month it is FOR, shown below the profit line, and append-only like an expense: it is
 * added, reversed (the amount taken back on the same month, with a reason) or corrected (a reversal plus a new entry in one step). The tax comes from a closed list of taxes on the
 * business itself; duty, surtax and import VAT are refused with the plain reason, in any spelling, because entering one here would count it twice.
 */
@Service
public class TaxService {

    private static final int MAX_MONTHS_BACK = 60;

    private final JdbcTemplate jdbc;
    private final LedgerWriter ledger;
    private final Locks locks;
    private final BusinessCalendar calendar;
    private final Clock clock;
    private final ExpenseService expenses;

    public TaxService(JdbcTemplate jdbc, LedgerWriter ledger, Locks locks, BusinessCalendar calendar, Clock clock, ExpenseService expenses) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.locks = locks;
        this.calendar = calendar;
        this.clock = clock;
        this.expenses = expenses;
    }

    private record Row(UUID id, long entryId, String taxType, LocalDate month, BigDecimal amount, LocalDate paidOn, String reference, boolean reversal) {
    }

    @Transactional
    public TaxEntryResponse add(String actor, TaxEntryRequest r) {
        locks.writer();
        return get(post(actor, r.taxType(), r.forMonth(), r.amount(), r.paidOn(), r.reference(), r.reason(), null));
    }

    @Transactional
    public TaxEntryResponse reverse(String actor, UUID id, String reason) {
        locks.writer();
        return get(reversal(actor, loadForUndo(id), reason, null));
    }

    @Transactional
    public TaxEntryResponse correct(String actor, UUID id, TaxCorrectionRequest c) {
        locks.writer();
        Row o = loadForUndo(id);
        String forMonth = c.forMonth() != null ? c.forMonth() : o.month().getYear() + "-" + String.format("%02d", o.month().getMonthValue());
        BigDecimal amount = c.amount() != null ? c.amount() : o.amount();
        LocalDate paidOn = c.paidOn() != null ? c.paidOn() : o.paidOn();
        String reference = c.reference() != null ? c.reference() : o.reference();
        if (parseMonth(forMonth).equals(o.month()) && amount.compareTo(o.amount()) == 0 && paidOn.equals(o.paidOn()) && Objects.equals(reference, o.reference())) {
            throw new FinanceProblem(HttpStatus.BAD_REQUEST, "correction-changes-nothing", "This correction changes nothing: the month, amount, day and reference are as they were. Say what was wrong");
        }
        UUID group = UUID.randomUUID();
        reversal(actor, o, c.reason(), group);
        return get(post(actor, o.taxType(), forMonth, amount, paidOn, reference, c.reason(), group));
    }

    private UUID post(String actor, String taxCode, String forMonth, BigDecimal amount, LocalDate paidOn, String reference, String reason, UUID group) {
        String type = taxType(taxCode);
        LocalDate month = parseMonth(forMonth);
        LocalDate thisMonth = BusinessCalendar.firstOfMonth(calendar.today(clock.instant()));
        if (month.isAfter(thisMonth) || month.isBefore(thisMonth.minusMonths(MAX_MONTHS_BACK))) {
            throw new FinanceProblem(HttpStatus.BAD_REQUEST, "invalid-month", "A tax is for a month that has begun and is not more than " + MAX_MONTHS_BACK + " months back");
        }
        if (paidOn.isAfter(calendar.today(clock.instant()))) {
            throw new FinanceProblem(HttpStatus.BAD_REQUEST, "invalid-paid-date", "A tax cannot have been paid in the future");
        }
        FreeText.noPhoneNumbers(reference, "the reference");
        boolean issued = expenses.issued(month);
        if (issued && (reason == null || reason.isBlank())) {
            throw new FinanceProblem(HttpStatus.BAD_REQUEST, "reason-required-for-issued-month", "The month of " + forMonth + " was already reported: say why this is entered now, and the report will show it was entered after the fact");
        }
        UUID id = UUID.randomUUID();
        long entryId = ledger.post(Entry.of("tax:" + id, "TAX_ENTERED", amount, month.atStartOfDay(BusinessCalendar.ZONE).toInstant(), month, "TAX_ENTRY", actor)).entryId();
        jdbc.update("""
                INSERT INTO tax_entry (tax_entry_id, ledger_entry_id, tax_type, period_month, amount, paid_on, reference, correction_group, reason, in_issued_month, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", id, entryId, type, month, amount, paidOn, reference, group, reason, issued, actor);
        return id;
    }

    private UUID reversal(String actor, Row o, String reason, UUID group) {
        boolean issued = expenses.issued(o.month());
        UUID id = UUID.randomUUID();
        long entryId = ledger.post(Entry.of("tax-rev:" + id, "TAX_ENTERED", o.amount().negate(), o.month().atStartOfDay(BusinessCalendar.ZONE).toInstant(), o.month(), "TAX_ENTRY", actor)
                .reverses(o.entryId())).entryId();
        jdbc.update("""
                INSERT INTO tax_entry (tax_entry_id, ledger_entry_id, tax_type, period_month, amount, paid_on, reference, reverses_tax_entry_id, correction_group, reason, in_issued_month, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", id, entryId, o.taxType(), o.month(), o.amount(), o.paidOn(), o.reference(), o.id(), group, reason, issued, actor);
        return id;
    }

    private Row loadForUndo(UUID id) {
        Row row = find(id);
        if (row == null) {
            throw new FinanceProblem(HttpStatus.NOT_FOUND, "tax-entry-not-found", "There is no tax entry " + id);
        }
        if (row.reversal()) {
            throw new FinanceProblem(HttpStatus.CONFLICT, "not-reversible", "That row is itself a taking-back: to put the tax back, enter it again");
        }
        if (jdbc.queryForObject("SELECT count(*) FROM tax_entry WHERE reverses_tax_entry_id = ?", Long.class, id) > 0) {
            throw new FinanceProblem(HttpStatus.CONFLICT, "tax-entry-already-reversed", "That tax entry was already taken back");
        }
        return row;
    }

    // ------------------------------------------------------------------ reading

    public TaxEntryResponse get(UUID id) {
        List<TaxEntryResponse> found = query("WHERE t.tax_entry_id = ?", List.of(id));
        if (found.isEmpty()) {
            throw new FinanceProblem(HttpStatus.NOT_FOUND, "tax-entry-not-found", "There is no tax entry " + id);
        }
        return found.get(0);
    }

    public List<TaxEntryResponse> list(String month) {
        if (month == null) {
            return query("WHERE TRUE", List.of());
        }
        return query("WHERE t.period_month = ?", List.of(parseMonth(month)));
    }

    public List<TaxTypeResponse> types() {
        return jdbc.query("SELECT code, label FROM tax_type ORDER BY label", (rs, n) -> new TaxTypeResponse(rs.getString(1), rs.getString(2)));
    }

    private List<TaxEntryResponse> query(String where, List<?> args) {
        return jdbc.query("""
                SELECT t.tax_entry_id, t.tax_type, ty.label, t.period_month, t.amount, t.paid_on, t.reference, t.reason, t.reverses_tax_entry_id, t.correction_group, t.in_issued_month,
                       t.created_by, t.created_at, l.recorded_at,
                       (SELECT r.tax_entry_id FROM tax_entry r WHERE r.reverses_tax_entry_id = t.tax_entry_id) AS reversed_by
                  FROM tax_entry t JOIN ledger_entry l ON l.entry_id = t.ledger_entry_id JOIN tax_type ty ON ty.code = t.tax_type """ + " " + where + " ORDER BY l.recorded_at DESC, l.entry_id DESC",
                (rs, n) -> {
                    UUID reverses = rs.getObject("reverses_tax_entry_id", UUID.class);
                    UUID reversedBy = rs.getObject("reversed_by", UUID.class);
                    String status = reverses != null ? "REVERSAL" : reversedBy != null ? "REVERSED" : "ACTIVE";
                    LocalDate month = rs.getDate("period_month").toLocalDate();
                    return new TaxEntryResponse(rs.getObject("tax_entry_id", UUID.class), rs.getString("tax_type"), rs.getString("label"), YearMonth.from(month).toString(), rs.getBigDecimal("amount"),
                            rs.getDate("paid_on").toLocalDate(), rs.getString("reference"), rs.getString("reason"), status, reversedBy, reverses, rs.getObject("correction_group", UUID.class),
                            rs.getBoolean("in_issued_month"), rs.getString("created_by"), rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("recorded_at").toInstant());
                }, new ArrayList<Object>(args).toArray());
    }

    // ------------------------------------------------------------------ helpers

    private Row find(UUID id) {
        List<Row> found = jdbc.query("SELECT t.tax_entry_id, t.ledger_entry_id, t.tax_type, t.period_month, t.amount, t.paid_on, t.reference, t.reverses_tax_entry_id FROM tax_entry t WHERE t.tax_entry_id = ?",
                (rs, n) -> new Row(rs.getObject(1, UUID.class), rs.getLong(2), rs.getString(3), rs.getDate(4).toLocalDate(), rs.getBigDecimal(5), rs.getDate(6).toLocalDate(), rs.getString(7),
                        rs.getObject(8, UUID.class) != null), id);
        return found.isEmpty() ? null : found.get(0);
    }

    /** The tax a person typed, normalised (case, spaces and hyphens), refused if it is a customs charge, and found in the closed list. */
    private String taxType(String typed) {
        String code = typed.strip().toUpperCase().replaceAll("[\\s-]+", "_");
        List<Map<String, Object>> refused = jdbc.queryForList("SELECT code, plain_reason FROM tax_type_refused WHERE code = ?", alias(code));
        if (!refused.isEmpty()) {
            throw new FinanceProblem(HttpStatus.BAD_REQUEST, "customs-charge-not-a-tax-entry", (String) refused.get(0).get("plain_reason"));
        }
        if (jdbc.queryForObject("SELECT count(*) FROM tax_type WHERE code = ?", Long.class, code) == 0) {
            throw new FinanceProblem(HttpStatus.BAD_REQUEST, "unknown-tax", "'" + typed + "' is not a tax that can be entered here: the list is turnover tax");
        }
        return code;
    }

    private static String alias(String code) {
        return switch (code) {
            case "CUSTOMS_DUTY", "IMPORT_DUTY" -> "DUTY";
            case "VAT", "CUSTOMS_VAT" -> "IMPORT_VAT";
            default -> code;
        };
    }

    static LocalDate parseMonth(String month) {
        try {
            return YearMonth.parse(month).atDay(1);
        } catch (DateTimeParseException e) {
            throw new FinanceProblem(HttpStatus.BAD_REQUEST, "invalid-month", "A month is written year-month, for example 2026-10");
        }
    }
}
