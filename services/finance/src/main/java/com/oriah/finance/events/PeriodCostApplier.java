package com.oriah.finance.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.oriah.events.EventJson;
import com.oriah.finance.config.BusinessCalendar;
import com.oriah.finance.events.inbound.PeriodCosts;
import com.oriah.finance.events.inbound.PeriodCosts.Bill;
import com.oriah.finance.ledger.AnomalyService;
import com.oriah.finance.ledger.AnomalyService.Severity;
import com.oriah.finance.ledger.ExpenseRecords;
import com.oriah.finance.ledger.LedgerWriter;
import com.oriah.finance.ledger.LedgerWriter.Entry;
import com.oriah.finance.ledger.Locks;

import tools.jackson.core.JacksonException;

/**
 * Applies {@code consignment.period_costs}: the bills for the month one allocation revision of a consignment changed. Each bill in {@code costs} is an expense of the day
 * the event gives it; each bill in {@code replaces} (the previous revision's, taken back by a correction) is the same amount, negative, on the day the event gives it (the
 * day of the correction), so a month already reported keeps its figure and the correction lands in the month it is made.
 *
 * <p>Every row is keyed by {@code (consignment, revision, position)}, so the rows commute: the receipt and its corrections can arrive in any order and be announced any number
 * of times with the same totals. Duty, surtax and import VAT are never a bill for the month: one that is announced is booked all the same (the money was spent) and raised
 * as an error that blocks reporting until someone has looked, because it would count against profit as an expense instead of through the cost of goods. A date more
 * than a year from the receipt is booked and raised as a warning.
 */
@Component
public class PeriodCostApplier {

    private static final Set<String> CUSTOMS = Set.of("DUTY", "SURTAX", "IMPORT_VAT");
    private static final long ODD_DAYS = 400;
    static final String DESCRIPTION = "Bill for the month on a consignment";
    static final String CORRECTED = "bill corrected on the consignment";

    private final Locks locks;
    private final LedgerWriter ledger;
    private final AnomalyService anomalies;
    private final ExpenseRecords expenses;
    private final BusinessCalendar calendar;

    public PeriodCostApplier(Locks locks, LedgerWriter ledger, AnomalyService anomalies, ExpenseRecords expenses, BusinessCalendar calendar) {
        this.locks = locks;
        this.ledger = ledger;
        this.anomalies = anomalies;
        this.expenses = expenses;
        this.calendar = calendar;
    }

    @Transactional
    public void apply(EventContext ctx, byte[] payload) {
        PeriodCosts p = read(payload);
        validate(p);
        locks.writer();

        String prefix = "period:" + p.consignmentId() + ":" + p.revision() + ":";
        for (Bill bill : orEmpty(p.replaces())) {
            post(ctx, p, prefix + "undo:" + bill.position(), bill, bill.amount().negate(), "REVERSAL", CORRECTED);
        }
        for (Bill bill : orEmpty(p.costs())) {
            post(ctx, p, prefix + "add:" + bill.position(), bill, bill.amount(), "ADD", null);
            if (CUSTOMS.contains(bill.costType())) {
                anomalies.raise("customs-as-period", p.consignmentId().toString(), Severity.ERROR,
                        Map.of("consignmentId", p.consignmentId().toString(), "costType", bill.costType()), ctx.eventId());
            }
        }
        if (p.receivedAt() != null) {
            LocalDate receiptDay = calendar.dayOf(p.receivedAt());
            for (Bill bill : orEmpty(p.costs())) {
                if (Math.abs(ChronoUnit.DAYS.between(receiptDay, bill.effectiveOn())) > ODD_DAYS) {
                    anomalies.raise("period-cost-date-odd", p.consignmentId().toString(), Severity.WARN,
                            Map.of("consignmentId", p.consignmentId().toString(), "position", bill.position()), ctx.eventId());
                }
            }
        }
    }

    private void post(EventContext ctx, PeriodCosts p, String key, Bill bill, BigDecimal amount, String kind, String reason) {
        Instant at = bill.effectiveOn().atStartOfDay(BusinessCalendar.ZONE).toInstant();
        var posted = ledger.post(Entry.of(key, "EXPENSE", amount, at, bill.effectiveOn(), "PERIOD_COST", ctx.actor()).category("CONSIGNMENT_PERIOD_COST").reason(bill.costType())
                .sourceRecordedAt(p.recordedAt() != null ? p.recordedAt() : ctx.recordedAt()).event(ctx.eventId(), ctx.producer(), ctx.outboxId()).consignment(p.consignmentId(), null));
        if (posted.inserted()) {
            expenses.posted(posted.entryId(), kind, "CONSIGNMENT_PERIOD_COST", DESCRIPTION, reason, null, null);
        }
    }

    private static PeriodCosts read(byte[] payload) {
        try {
            return EventJson.mapper().readValue(payload, PeriodCosts.class);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("consignment.period_costs cannot be read: " + e.getOriginalMessage(), e);
        }
    }

    private static void validate(PeriodCosts p) {
        if (p.consignmentId() == null) {
            throw new IllegalArgumentException("consignment.period_costs has no consignmentId");
        }
        if (!"ZMW".equals(p.currency())) {
            throw new IllegalArgumentException("consignment.period_costs is in ZMW only, was " + p.currency());
        }
        if (p.revision() < 1) {
            throw new IllegalArgumentException("a revision is numbered from 1, was " + p.revision());
        }
        for (Bill bill : concat(p.costs(), p.replaces())) {
            if (bill.position() < 1 || bill.costType() == null || bill.costType().isBlank() || bill.effectiveOn() == null || bill.amount() == null) {
                throw new IllegalArgumentException("a bill names its place, its type, its amount and the day it counts on");
            }
            SaleApplier.ngwee(bill.amount(), "a bill");
            if (bill.amount().signum() <= 0) {
                throw new IllegalArgumentException("a bill is more than K0.00 (one of K0.00 is never announced), was " + bill.amount().toPlainString());
            }
        }
    }

    private static List<Bill> orEmpty(List<Bill> bills) {
        return bills == null ? List.of() : bills;
    }

    private static List<Bill> concat(List<Bill> a, List<Bill> b) {
        List<Bill> all = new java.util.ArrayList<>(orEmpty(a));
        all.addAll(orEmpty(b));
        return all;
    }
}
