package com.oriah.finance.service;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.oriah.finance.config.BusinessCalendar;
import com.oriah.finance.dto.CorrectionRequest;
import com.oriah.finance.dto.ExpenseCategoryResponse;
import com.oriah.finance.dto.ExpensePage;
import com.oriah.finance.dto.ExpenseRequest;
import com.oriah.finance.dto.ExpenseResponse;
import com.oriah.finance.exception.FinanceProblem;
import com.oriah.finance.ledger.LedgerWriter;
import com.oriah.finance.ledger.LedgerWriter.Entry;
import com.oriah.finance.ledger.Locks;

/**
 * Expenses typed in by hand, and the corrections of any expense (typed or posted by the system). The ledger row is the truth; the expense record says who, why and where it
 * came from. There is no update and no delete anywhere: an expense is added, reversed (a row of the same heading and day, negative, with a reason) or corrected (a reversal plus
 * a new expense in one step, sharing a group), so an expense can never vanish from a month already reported.
 *
 * <p>What the system posts is never typed a second time: a payment charge and a bill for the month are refused as headings; a courier fare is refused while a posted fare for
 * the order stands, and refused until finance knows the order was dispatched (the dispatch event is always applied first, so the two can never both be counted). A bill for
 * the month a consignment posted is corrected on the consignment, never here.
 */
@Service
public class ExpenseService {

    static final int DEFAULT_SIZE = 50;
    static final int MAX_SIZE = 200;

    private final JdbcTemplate jdbc;
    private final LedgerWriter ledger;
    private final Locks locks;
    private final BusinessCalendar calendar;
    private final Clock clock;

    public ExpenseService(JdbcTemplate jdbc, LedgerWriter ledger, Locks locks, BusinessCalendar calendar, Clock clock) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.locks = locks;
        this.calendar = calendar;
        this.clock = clock;
    }

    private record Category(String code, String label, boolean handKeyable, boolean posted, boolean needsOrder, boolean needsProvider, boolean needsConfirmation) {
    }

    private record Row(UUID id, long entryId, String kind, String category, String origin, LocalDate day, BigDecimal amount, String description, UUID order, UUID confirmation, String provider,
                       String reason, UUID group, boolean issued) {
    }

    // ------------------------------------------------------------------ writing

    @Transactional
    public ExpenseResponse add(String actor, ExpenseRequest r) {
        locks.writer();
        UUID id = post(actor, r.category(), r.amount(), r.expenseDate(), r.description(), r.linkedOrderId(), r.linkedConfirmationId(), r.provider(), r.reason(), null, null);
        return get(id);
    }

    @Transactional
    public ExpenseResponse reverse(String actor, UUID id, String reason) {
        FreeText.noPhoneNumbers(reason, "the reason");
        locks.writer();
        Row original = loadForUndo(id);
        UUID reversal = reversal(actor, original, reason, null);
        return get(reversal);
    }

    @Transactional
    public ExpenseResponse correct(String actor, UUID id, CorrectionRequest c) {
        locks.writer();
        Row o = loadForUndo(id);
        String category = c.category() != null ? c.category() : o.category();
        BigDecimal amount = c.amount() != null ? c.amount() : o.amount();
        LocalDate day = c.expenseDate() != null ? c.expenseDate() : o.day();
        String description = c.description() != null ? c.description() : o.description();
        UUID order = c.linkedOrderId() != null ? c.linkedOrderId() : o.order();
        UUID confirmation = c.linkedConfirmationId() != null ? c.linkedConfirmationId() : o.confirmation();
        String provider = c.provider() != null ? c.provider() : o.provider();
        if (category.equals(o.category()) && amount.compareTo(o.amount()) == 0 && day.equals(o.day()) && description.equals(o.description()) && Objects.equals(order, o.order())
                && Objects.equals(confirmation, o.confirmation()) && Objects.equals(provider, o.provider())) {
            throw new FinanceProblem(HttpStatus.BAD_REQUEST, "correction-changes-nothing", "This correction changes nothing: the heading, amount, day and details are as they were. Say what was wrong");
        }
        UUID group = UUID.randomUUID();
        reversal(actor, o, c.reason(), group);
        UUID fresh = post(actor, category, amount, day, description, order, confirmation, provider, c.reason(), group, o);
        return get(fresh);
    }

    /** A new expense: validated, posted to the ledger and recorded. {@code replacing} is the expense being corrected (its own posted fare does not block its replacement). */
    private UUID post(String actor, String categoryCode, BigDecimal amount, LocalDate day, String description, UUID order, UUID confirmation, String provider, String reason, UUID group,
                      Row replacing) {
        Category category = category(categoryCode);
        if (category == null) {
            throw new FinanceProblem(HttpStatus.BAD_REQUEST, "unknown-category", "There is no expense heading '" + categoryCode + "': see the list of headings");
        }
        if (!category.handKeyable()) {
            throw new FinanceProblem(HttpStatus.CONFLICT, "category-posted-from-source", "'" + category.label() + "' is posted by the system from the other services' records and cannot be typed in: "
                    + "a payment charge comes from the payment, a bill for the month from the consignment");
        }
        if (amount.signum() <= 0) {
            throw new FinanceProblem(HttpStatus.BAD_REQUEST, "invalid-amount", "An expense is more than K0.00");
        }
        LocalDate today = calendar.today(clock.instant());
        if (day.isAfter(today)) {
            throw new FinanceProblem(HttpStatus.BAD_REQUEST, "invalid-expense-date", "An expense cannot be dated in the future (today is " + today + "): type it on the day it is spent");
        }
        String text = description.strip();
        FreeText.noPhoneNumbers(text, "the description");
        FreeText.noPhoneNumbers(reason, "the reason");
        provider = FreeText.providerCode(provider);
        if ("OTHER".equals(category.code()) && text.length() < 5) {
            throw new FinanceProblem(HttpStatus.BAD_REQUEST, "invalid-description", "'Other' needs a description of at least five characters, so the bookkeeper can tell what it was");
        }
        if (category.needsOrder()) {
            requireDispatchedOrder(order, replacing);
        }
        if (category.needsProvider() && (provider == null || provider.isBlank())) {
            throw new FinanceProblem(HttpStatus.BAD_REQUEST, "provider-required", "'" + category.label() + "' names the payment provider it is for (so it can be checked against the provider's statement)");
        }
        if (category.needsConfirmation() && (confirmation == null || reason == null || reason.isBlank())) {
            throw new FinanceProblem(HttpStatus.BAD_REQUEST, "confirmation-and-reason-required", "'" + category.label() + "' names the payment confirmation it adjusts and says why");
        }
        boolean issued = issued(day);
        if (issued && (reason == null || reason.isBlank())) {
            throw new FinanceProblem(HttpStatus.BAD_REQUEST, "reason-required-for-issued-month", "The month of " + day + " was already reported: say why this is entered now, and the report will show it was typed in after the fact");
        }
        UUID id = UUID.randomUUID();
        Entry entry = Entry.of("exp:" + id, "EXPENSE", amount, day.atStartOfDay(BusinessCalendar.ZONE).toInstant(), day, "EXPENSE", actor).category(category.code());
        if (order != null) {
            entry.order(order);
        }
        if (confirmation != null) {
            entry.confirmation(confirmation);
        }
        long entryId = ledger.post(entry).entryId();
        jdbc.update("""
                INSERT INTO expense (expense_id, ledger_entry_id, kind, category, description, provider, linked_order_id, linked_confirmation_id, correction_group, reason, in_issued_month, origin,
                                     created_by) VALUES (?, ?, 'ADD', ?, ?, ?, ?, ?, ?, ?, ?, 'KEYED', ?)""",
                id, entryId, category.code(), text, provider, order, confirmation, group, reason, issued, actor);
        return id;
    }

    private void requireDispatchedOrder(UUID order, Row replacing) {
        if (order == null) {
            throw new FinanceProblem(HttpStatus.BAD_REQUEST, "order-required", "A courier fare names the order it is for");
        }
        if (jdbc.queryForObject("SELECT count(*) FROM order_ref WHERE order_id = ?", Long.class, order) == 0) {
            throw new FinanceProblem(HttpStatus.CONFLICT, "order-not-known-yet", "Finance has not heard of order " + order + " yet: try again in a moment, or check the order number");
        }
        if (jdbc.queryForObject("SELECT count(*) FROM dispatch_fact WHERE order_id = ?", Long.class, order) == 0) {
            throw new FinanceProblem(HttpStatus.CONFLICT, "order-not-dispatched-yet", "Order " + order + " has not been dispatched yet: a courier fare is entered after the parcel has gone");
        }
        locks.order(order);
        Long standing = jdbc.queryForObject("SELECT count(*) FROM expense x JOIN ledger_entry l ON l.entry_id = x.ledger_entry_id WHERE x.category = 'COURIER_FARE' AND x.kind = 'ADD' "
                + "AND x.linked_order_id = ? AND NOT EXISTS (SELECT 1 FROM ledger_entry r WHERE r.reverses_entry_id = l.entry_id)", Long.class, order);
        if (standing != null && standing > 0) {
            throw new FinanceProblem(HttpStatus.CONFLICT, "fare-already-posted", "A fare for order " + order + " already stands: correct that one instead, so it is not counted twice");
        }
        Long posted = jdbc.query("SELECT entry_id FROM ledger_entry l WHERE entry_key = ? AND NOT EXISTS (SELECT 1 FROM ledger_entry r WHERE r.reverses_entry_id = l.entry_id)",
                (rs, n) -> rs.getLong(1), "fare:" + order).stream().findFirst().orElse(null);
        if (posted != null) {
            throw new FinanceProblem(HttpStatus.CONFLICT, "fare-already-posted", "The fare for order " + order + " was already posted from the dispatch record: correct that one instead, so it is not counted twice");
        }
    }

    /** Takes an expense back: a negative row of the same heading and day, with the reason. */
    private UUID reversal(String actor, Row o, String reason, UUID group) {
        boolean issued = issued(o.day());
        UUID id = UUID.randomUUID();
        Entry entry = Entry.of("exp-rev:" + id, "EXPENSE", o.amount().negate(), o.day().atStartOfDay(BusinessCalendar.ZONE).toInstant(), o.day(), "EXPENSE", actor).category(o.category())
                .reverses(o.entryId());
        if (o.order() != null) {
            entry.order(o.order());
        }
        if (o.confirmation() != null) {
            entry.confirmation(o.confirmation());
        }
        long entryId;
        try {
            entryId = ledger.post(entry).entryId();
        } catch (org.springframework.dao.DuplicateKeyException raced) {
            throw new FinanceProblem(HttpStatus.CONFLICT, "expense-already-reversed", "That expense has just been reversed by someone else");
        }
        jdbc.update("""
                INSERT INTO expense (expense_id, ledger_entry_id, kind, category, description, provider, linked_order_id, linked_confirmation_id, correction_group, reason, in_issued_month, origin,
                                     created_by) VALUES (?, ?, 'REVERSAL', ?, ?, ?, ?, ?, ?, ?, ?, 'KEYED', ?)""",
                id, entryId, o.category(), o.description(), o.provider(), o.order(), o.confirmation(), group, reason, issued, actor);
        return id;
    }

    private Row loadForUndo(UUID id) {
        Row row = find(id);
        if (row == null) {
            throw new FinanceProblem(HttpStatus.NOT_FOUND, "expense-not-found", "There is no expense " + id);
        }
        if ("REVERSAL".equals(row.kind())) {
            throw new FinanceProblem(HttpStatus.CONFLICT, "not-reversible", "That row is itself a taking-back: to put the expense back, add it again");
        }
        if (jdbc.queryForObject("SELECT count(*) FROM ledger_entry WHERE reverses_entry_id = ?", Long.class, row.entryId()) > 0) {
            throw new FinanceProblem(HttpStatus.CONFLICT, "expense-already-reversed", "That expense was already taken back");
        }
        if ("POSTED".equals(row.origin()) && "CONSIGNMENT_PERIOD_COST".equals(row.category())) {
            throw new FinanceProblem(HttpStatus.CONFLICT, "expense-posted-from-source", "A bill for the month is posted from its consignment: correct it on the consignment (amend the consignment's costs), not here");
        }
        return row;
    }

    // ------------------------------------------------------------------ reading

    public ExpenseResponse get(UUID id) {
        List<ExpenseResponse> found = query("WHERE e.expense_id = ?", List.of(id), 0, 1);
        if (found.isEmpty()) {
            throw new FinanceProblem(HttpStatus.NOT_FOUND, "expense-not-found", "There is no expense " + id);
        }
        return found.get(0);
    }

    public ExpensePage list(String month, String category, String source, boolean includeReversed, int page, int size) {
        if (page < 0 || size < 1 || size > MAX_SIZE) {
            throw new FinanceProblem(HttpStatus.BAD_REQUEST, "invalid-page", "page is 0 or more and size is 1 to " + MAX_SIZE);
        }
        StringBuilder where = new StringBuilder("WHERE TRUE");
        List<Object> args = new ArrayList<>();
        if (month != null) {
            LocalDate first = parseMonth(month);
            where.append(" AND l.effect_month = ?");
            args.add(first);
        }
        if (category != null && !category.isBlank()) {
            where.append(" AND e.category = ?");
            args.add(category);
        }
        if (source != null && !source.isBlank()) {
            if (!"KEYED".equals(source) && !"POSTED".equals(source)) {
                throw new FinanceProblem(HttpStatus.BAD_REQUEST, "invalid-source", "source is KEYED or POSTED");
            }
            where.append(" AND e.origin = ?");
            args.add(source);
        }
        if (!includeReversed) {
            where.append(" AND e.kind = 'ADD' AND NOT EXISTS (SELECT 1 FROM ledger_entry r WHERE r.reverses_entry_id = l.entry_id)");
        }
        long total = jdbc.queryForObject("SELECT count(*) FROM expense e JOIN ledger_entry l ON l.entry_id = e.ledger_entry_id " + where, Long.class, args.toArray());
        return new ExpensePage(query(where.toString(), args, page, size), page, size, total);
    }

    public List<ExpenseCategoryResponse> categories() {
        return jdbc.query("SELECT code, label, hand_keyable, posted, needs_order, needs_provider, needs_confirmation FROM expense_category ORDER BY label",
                (rs, n) -> new ExpenseCategoryResponse(rs.getString(1), rs.getString(2), rs.getBoolean(3), rs.getBoolean(4), rs.getBoolean(5), rs.getBoolean(6), rs.getBoolean(7)));
    }

    private List<ExpenseResponse> query(String where, List<?> args, int page, int size) {
        List<Object> all = new ArrayList<>(args);
        all.add(size);
        all.add((long) page * size);
        return jdbc.query("""
                SELECT e.expense_id, e.category, c.label, l.amount, l.effect_day, e.description, e.linked_order_id, e.linked_confirmation_id, e.provider, e.reason, e.kind, e.origin,
                       e.correction_group, e.in_issued_month, e.created_by, e.created_at, l.recorded_at,
                       (SELECT x.expense_id FROM ledger_entry r JOIN expense x ON x.ledger_entry_id = r.entry_id WHERE r.reverses_entry_id = l.entry_id) AS reversed_by
                  FROM expense e JOIN ledger_entry l ON l.entry_id = e.ledger_entry_id JOIN expense_category c ON c.code = e.category
                """ + where + " ORDER BY l.recorded_at DESC, l.entry_id DESC LIMIT ? OFFSET ?",
                (rs, n) -> {
                    UUID reversedBy = rs.getObject("reversed_by", UUID.class);
                    String kind = rs.getString("kind");
                    String status = "REVERSAL".equals(kind) ? "REVERSAL" : reversedBy != null ? "REVERSED" : "ACTIVE";
                    return new ExpenseResponse(rs.getObject("expense_id", UUID.class), rs.getString("category"), rs.getString("label"), rs.getBigDecimal("amount"),
                            rs.getDate("effect_day").toLocalDate(), rs.getString("description"), rs.getObject("linked_order_id", UUID.class), rs.getObject("linked_confirmation_id", UUID.class),
                            rs.getString("provider"), rs.getString("reason"), kind, rs.getString("origin"), status, reversedBy, rs.getObject("correction_group", UUID.class),
                            rs.getBoolean("in_issued_month"), rs.getString("created_by"), rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("recorded_at").toInstant());
                }, all.toArray());
    }

    // ------------------------------------------------------------------ helpers

    private Row find(UUID id) {
        List<Row> found = jdbc.query("""
                SELECT e.expense_id, l.entry_id, e.kind, e.category, e.origin, l.effect_day, l.amount, e.description, e.linked_order_id, e.linked_confirmation_id, e.provider, e.reason,
                       e.correction_group, e.in_issued_month
                  FROM expense e JOIN ledger_entry l ON l.entry_id = e.ledger_entry_id WHERE e.expense_id = ?""",
                (rs, n) -> new Row(rs.getObject(1, UUID.class), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getDate(6).toLocalDate(), rs.getBigDecimal(7), rs.getString(8),
                        rs.getObject(9, UUID.class), rs.getObject(10, UUID.class), rs.getString(11), rs.getString(12), rs.getObject(13, UUID.class), rs.getBoolean(14)), id);
        return found.isEmpty() ? null : found.get(0);
    }

    private Category category(String code) {
        return jdbc.query("SELECT code, label, hand_keyable, posted, needs_order, needs_provider, needs_confirmation FROM expense_category WHERE code = ?",
                (rs, n) -> new Category(rs.getString(1), rs.getString(2), rs.getBoolean(3), rs.getBoolean(4), rs.getBoolean(5), rs.getBoolean(6), rs.getBoolean(7)), code).stream().findFirst().orElse(null);
    }

    /** Whether the month a day falls in has been reported (issued) at least once. */
    boolean issued(LocalDate day) {
        return jdbc.queryForObject("SELECT count(*) FROM report_issue WHERE month = ?", Long.class, BusinessCalendar.firstOfMonth(day)) > 0;
    }

    static LocalDate parseMonth(String month) {
        try {
            return YearMonth.parse(month).atDay(1);
        } catch (DateTimeParseException e) {
            throw new FinanceProblem(HttpStatus.BAD_REQUEST, "invalid-month", "A month is written year-month, for example 2026-10");
        }
    }
}
