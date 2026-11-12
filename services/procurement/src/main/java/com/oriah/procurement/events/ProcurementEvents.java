package com.oriah.procurement.events;

import java.util.Map;
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
 * <p>Only one fact is published so far: a consignment line has landed. Amending a received
 * consignment ({@code consignment.amended}) comes with inventory (Phase 5), and a cost that is a
 * bill for the month ({@code PERIOD}) gets its own event with the profit report (Phase 10); until
 * then the month's bill is kept in the recorded allocation inputs and is not an event.
 */
@Component
public class ProcurementEvents {

    public static final String TOPIC = "oriah.procurement";
    public static final String AGGREGATE = "consignment_line";
    public static final String CONSIGNMENT_RECEIVED = "consignment.received";
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
                    costed.slices().stream().map(s -> new ConsignmentReceived.Slice(s.quantity(), s.unitCost())).toList());
            outbox.append(new EventDraft(AGGREGATE, line.id().toString(), CONSIGNMENT_RECEIVED, SCHEMA_VERSION, TOPIC,
                    consignment.receivedAt(), actor, payload));
        }
    }
}
