package com.oriah.procurement.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.oriah.events.EventDraft;
import com.oriah.events.OutboxWriter;
import com.oriah.procurement.allocation.ConsignmentCosting;
import com.oriah.procurement.allocation.ConsignmentCosting.LineView;
import com.oriah.procurement.dto.ConsignmentResponse;
import com.oriah.procurement.model.Consignment;
import com.oriah.procurement.model.ConsignmentLine;
import com.oriah.procurement.model.CostLine;
import com.oriah.procurement.model.CostTreatment;
import com.oriah.procurement.model.Supplier;

/**
 * Writes procurement's facts to its outbox (shared/oriah-events). The relay publishes them to
 * {@value #TOPIC} in the order they were written.
 *
 * <p>Two facts are published: a consignment line has landed ({@code consignment.received}), and a
 * received consignment's cost was corrected so a line now costs something else
 * ({@code consignment.amended}, Phase 5: the next event of the same line, only for the lines the
 * correction changed). A cost that is a bill for the month ({@code PERIOD}) is announced to finance as
 * {@code consignment.period_costs} (Phase 10, {@link ConsignmentPeriodCosts}) when a revision changes it.
 */
@Component
public class ProcurementEvents {

    public static final String TOPIC = "oriah.procurement";
    public static final String AGGREGATE = "consignment_line";
    public static final String CONSIGNMENT_RECEIVED = "consignment.received";
    public static final String CONSIGNMENT_AMENDED = "consignment.amended";
    public static final String PERIOD_COSTS = ConsignmentPeriodCosts.TYPE;
    static final int SCHEMA_VERSION = 1;

    /** Lusaka's calendar decides which month a bill belongs to. */
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Africa/Lusaka");

    private final OutboxWriter outbox;
    private final JdbcTemplate jdbc;

    public ProcurementEvents(OutboxWriter outbox, JdbcTemplate jdbc) {
        this.outbox = outbox;
        this.jdbc = jdbc;
    }

    /**
     * Announces the bills for the month that one allocation revision changed, if it changed any, and marks the revision as looked at, in the caller's transaction
     * (the marker commits with the event, so the back-fill and the live path can never both announce a revision). Nothing is announced when the revision's bills
     * are the previous revision's; a bill of K0.00 is no bill.
     *
     * <p>Revision 1 books each bill on the day it was incurred; a correction takes back the previous revision's bills and books this revision's, both on the day of
     * the correction (a month already reported keeps its figure). This is the caller's last write.
     *
     * @param amendedAt  null for revision 1
     * @param reason     null for revision 1
     * @param costs      this revision's cost lines
     * @param previous   the previous revision's cost lines; empty for revision 1
     * @param recordedAt when the revision was recorded
     * @return whether an event was appended
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean periodCosts(Consignment consignment, int revision, Instant amendedAt, String reason, List<CostLine> costs, List<CostLine> previous,
                               Instant recordedAt, String actor) {
        if (jdbc.update("INSERT INTO period_cost_event (consignment_id, revision, emitted) VALUES (?, ?, false) ON CONFLICT DO NOTHING",
                consignment.id(), revision) == 0) {
            return false;   // already looked at: by the live path or by the back-fill
        }
        List<CostLine> bills = bills(costs);
        List<CostLine> before = bills(previous);
        boolean receipt = revision == 1;
        if (receipt ? bills.isEmpty() : sameBills(bills, before)) {
            return false;
        }
        LocalDate onCorrection = amendedAt == null ? null : amendedAt.atZone(BUSINESS_ZONE).toLocalDate();
        LocalDate receiptDay = consignment.receivedAt().atZone(BUSINESS_ZONE).toLocalDate();
        List<ConsignmentPeriodCosts.Bill> booked = new ArrayList<>();
        for (CostLine bill : bills) {
            LocalDate day = receipt ? (bill.incurredOn() != null ? bill.incurredOn() : receiptDay) : onCorrection;
            booked.add(new ConsignmentPeriodCosts.Bill(bill.position(), bill.costType().name(), bill.amount().setScale(2), day));
        }
        List<ConsignmentPeriodCosts.Bill> taken = new ArrayList<>();
        if (!receipt) {
            for (CostLine bill : before) {
                taken.add(new ConsignmentPeriodCosts.Bill(bill.position(), bill.costType().name(), bill.amount().setScale(2), onCorrection));
            }
        }
        ConsignmentPeriodCosts payload = new ConsignmentPeriodCosts(consignment.id(), ConsignmentResponse.reference(consignment.refNo()), revision,
                ConsignmentResponse.CURRENCY, consignment.receivedAt(), amendedAt, reason, booked, taken, recordedAt);
        jdbc.update("UPDATE period_cost_event SET emitted = true WHERE consignment_id = ? AND revision = ?", consignment.id(), revision);
        outbox.append(new EventDraft(ConsignmentPeriodCosts.AGGREGATE, consignment.id().toString(), PERIOD_COSTS, SCHEMA_VERSION, TOPIC,
                receipt ? consignment.receivedAt() : amendedAt, actor, payload));
        return true;
    }

    /** The bills for the month: PERIOD cost lines of more than K0.00, in the order they were keyed. */
    private static List<CostLine> bills(List<CostLine> costs) {
        return costs.stream().filter(c -> c.treatment() == CostTreatment.PERIOD && c.amount().signum() > 0)
                .sorted(Comparator.comparingInt(CostLine::position)).toList();
    }

    /** Whether two sets of bills are the same bills: the same types and amounts (a bill's date and place in the list do not make a different bill). */
    private static boolean sameBills(List<CostLine> a, List<CostLine> b) {
        return key(a).equals(key(b));
    }

    private static List<String> key(List<CostLine> bills) {
        return bills.stream().map(c -> c.costType().name() + ":" + c.amount().setScale(2).toPlainString()).sorted().toList();
    }

    /**
     * Appends one {@code consignment.received} per line, in line order, in the caller's
     * transaction: the facts and the receipt commit together or not at all. The caller flushes
     * its own changes first; this is its last write.
     *
     * @param suppliers the suppliers the lines name, by id
     * @param actor     {@code user:<subject>} for the person who received it
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void consignmentReceived(Consignment consignment, ConsignmentCosting.Outcome outcome,
                                    Map<UUID, Supplier> suppliers, String actor) {
        if (!outcome.ready()) {
            throw new IllegalArgumentException("consignment.received describes a consignment that was costed");
        }
        String reference = ConsignmentResponse.reference(consignment.refNo());
        for (ConsignmentLine line : consignment.lines()) {
            LineView costed = outcome.lines().stream().filter(l -> l.lineId().equals(line.id())).findFirst()
                    .orElseThrow(() -> new IllegalStateException("line " + line.id() + " was not costed"));
            Supplier supplier = line.supplierId() == null ? null : suppliers.get(line.supplierId());
            if (line.supplierId() != null && supplier == null) {
                throw new IllegalStateException("line " + line.id() + " names supplier " + line.supplierId() + " that is gone");
            }
            ConsignmentReceived payload = new ConsignmentReceived(consignment.id(), reference, line.id(),
                    line.variantId(), line.sku(), line.productTitle(), line.variantTitle(), line.kind(),
                    supplier == null ? null : supplier.id(), supplier == null ? null : supplier.name(),
                    consignment.receivedAt(), consignment.isEstimated(), ConsignmentResponse.CURRENCY,
                    line.quantity(), costed.goodsTotal(), costed.sharedCost(), costed.landedTotal(),
                    costed.slices().stream().map(s -> new ConsignmentReceived.Slice(s.quantity(), s.unitCost())).toList(),
                    line.position());
            outbox.append(new EventDraft(AGGREGATE, line.id().toString(), CONSIGNMENT_RECEIVED, SCHEMA_VERSION, TOPIC,
                    consignment.receivedAt(), actor, payload));
        }
    }

    /**
     * Appends one {@code consignment.amended} for each line named in {@code changedLines}, in line order, in the
     * caller's transaction: the correction and what it tells inventory commit together or not at all. A line that is
     * not named gets nothing. This is the caller's last write.
     *
     * @param outcome      the corrected allocation, as recorded
     * @param estimated    the consignment's estimate flag as of this revision
     * @param changedLines the lines whose figures (or estimate flag) the correction changed
     * @param amendedAt    when it was corrected: the event's own time
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void consignmentAmended(Consignment consignment, ConsignmentCosting.Outcome outcome, Map<UUID, Supplier> suppliers,
                                   String actor, int revision, String reason, boolean estimated, Set<UUID> changedLines,
                                   Instant amendedAt) {
        if (!outcome.ready()) {
            throw new IllegalArgumentException("consignment.amended describes a consignment that was costed");
        }
        String reference = ConsignmentResponse.reference(consignment.refNo());
        for (ConsignmentLine line : consignment.lines()) {
            if (!changedLines.contains(line.id())) {
                continue;
            }
            LineView costed = outcome.lines().stream().filter(l -> l.lineId().equals(line.id())).findFirst()
                    .orElseThrow(() -> new IllegalStateException("line " + line.id() + " was not costed"));
            Supplier supplier = line.supplierId() == null ? null : suppliers.get(line.supplierId());
            if (line.supplierId() != null && supplier == null) {
                throw new IllegalStateException("line " + line.id() + " names supplier " + line.supplierId() + " that is gone");
            }
            ConsignmentAmended payload = new ConsignmentAmended(consignment.id(), reference, line.id(),
                    line.variantId(), line.sku(), line.productTitle(), line.variantTitle(), line.kind(),
                    supplier == null ? null : supplier.id(), supplier == null ? null : supplier.name(),
                    consignment.receivedAt(), estimated, ConsignmentResponse.CURRENCY,
                    line.quantity(), costed.goodsTotal(), costed.sharedCost(), costed.landedTotal(),
                    costed.slices().stream().map(s -> new ConsignmentReceived.Slice(s.quantity(), s.unitCost())).toList(),
                    line.position(), revision, reason, amendedAt);
            outbox.append(new EventDraft(AGGREGATE, line.id().toString(), CONSIGNMENT_AMENDED, SCHEMA_VERSION, TOPIC,
                    amendedAt, actor, payload));
        }
    }
}
