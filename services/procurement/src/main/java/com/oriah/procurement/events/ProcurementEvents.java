package com.oriah.procurement.events;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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
import com.oriah.procurement.model.Supplier;

/**
 * Writes procurement's facts to its outbox (shared/oriah-events). The relay publishes them to
 * {@value #TOPIC} in the order they were written.
 *
 * <p>Two facts are published: a consignment line has landed ({@code consignment.received}), and a
 * received consignment's cost was corrected so a line now costs something else
 * ({@code consignment.amended}, Phase 5: the next event of the same line, only for the lines the
 * correction changed). A cost that is a bill for the month ({@code PERIOD}) gets its own event with
 * the profit report (Phase 10); until then the month's bill is kept in the recorded allocation inputs
 * and is not an event.
 */
@Component
public class ProcurementEvents {

    public static final String TOPIC = "oriah.procurement";
    public static final String AGGREGATE = "consignment_line";
    public static final String CONSIGNMENT_RECEIVED = "consignment.received";
    public static final String CONSIGNMENT_AMENDED = "consignment.amended";
    static final int SCHEMA_VERSION = 1;

    private final OutboxWriter outbox;

    public ProcurementEvents(OutboxWriter outbox) {
        this.outbox = outbox;
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
