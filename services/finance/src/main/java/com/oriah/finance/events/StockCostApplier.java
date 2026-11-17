package com.oriah.finance.events;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.oriah.events.EventJson;
import com.oriah.finance.config.BusinessCalendar;
import com.oriah.finance.events.inbound.StockCost;
import com.oriah.finance.ledger.AnomalyService;
import com.oriah.finance.ledger.AnomalyService.Severity;
import com.oriah.finance.ledger.LedgerWriter;
import com.oriah.finance.ledger.LedgerWriter.Entry;
import com.oriah.finance.ledger.Locks;

import tools.jackson.core.JacksonException;

/**
 * Applies {@code stock.cost_recorded}: one row of the stock ledger, what it released. A sale (or a box swap) is cost of goods or of packaging; stock written off or given
 * away is booked at its cost under the reason it left for; a take-back is booked inside the reason it takes back, on its own day; a cost correction is the old row reversed
 * and the new one written, each on the day of ITS sale, so every month moves by exactly its own units' corrected cost. The signed sum is what the ledger holds in force.
 *
 * <p>The product is the one the event names, frozen on the row; an event that names none is booked to "not tied to a product yet" and raised, never guessed. A piece that cost
 * K0.00 is still a row (an estimated K0.00 lot that is sold must still count as an estimated sale). A reason that is not a cost (a receipt, a lot transfer) or that nobody
 * defined is refused, so it can never be silently dropped from the books.
 *
 * <p><b>Returned pieces (Phase 12).</b> A piece that came back is a take-back of its sale (reason {@code RETURN}): its cost leaves cost of goods on the day it came back. If
 * it is then found unsellable it is stock written off ({@code WRITE_OFF_RETURNED}), at the cost of the lot it went back to: counted once, as a write-off, never as cost of
 * goods. If it is moved to a marked-down listing of its own ({@code RELISTED_MARKED_DOWN}) no cost is recognised at all: the piece only changed shelf, and its cost is
 * released when the marked-down piece is sold. Inventory does not announce that move; if one ever arrives it is applied as nothing, on purpose, never refused.
 */
@Component
public class StockCostApplier {

    private static final Set<String> COST_OF_SALE = Set.of("SALE", "BOX_SWAPPED", "RETURN", "CANCELLED_BEFORE_DISPATCH");
    private static final Set<String> STOCK_OUT = Set.of("WRITE_OFF_LOST", "WRITE_OFF_STOLEN", "WRITE_OFF_BROKEN", "GIVEN_AWAY_MODEL", "GIVEN_AWAY_INFLUENCER", "GIVEN_AWAY_GIFT",
            "GIVEN_AWAY_PRIZE", "WRITE_OFF_RETURNED");
    /** A returned piece moved to its own marked-down listing: it changed shelf, and released no cost. */
    private static final String MOVES_NO_COST = "RELISTED_MARKED_DOWN";
    private static final Set<String> ROLES = Set.of("ORIGINAL", "RECUT_REVERSAL", "RECUT_REPLACEMENT");

    private final Locks locks;
    private final LedgerWriter ledger;
    private final AnomalyService anomalies;
    private final BusinessCalendar calendar;

    public StockCostApplier(Locks locks, LedgerWriter ledger, AnomalyService anomalies, BusinessCalendar calendar) {
        this.locks = locks;
        this.ledger = ledger;
        this.anomalies = anomalies;
        this.calendar = calendar;
    }

    @Transactional
    public void apply(EventContext ctx, byte[] payload) {
        StockCost c = read(payload);
        validate(c);
        if (MOVES_NO_COST.equals(c.reason())) {
            return;
        }
        String account;
        String reason = null;
        if (COST_OF_SALE.contains(c.reason())) {
            account = "SELLABLE".equals(c.kind()) ? "COGS_GOODS" : "COGS_PACKAGING";
        } else if (STOCK_OUT.contains(c.reason())) {
            account = "STOCK_OUT";
            reason = c.reason();
        } else if ("TAKEN_BACK".equals(c.reason())) {
            account = "STOCK_OUT";
            reason = c.reversesReason() != null && STOCK_OUT.contains(c.reversesReason()) ? c.reversesReason() : "TAKEN_BACK";
        } else {
            throw new IllegalArgumentException("a stock movement of reason " + c.reason() + " is not a cost finance books (a receipt or a lot transfer moves no cost)");
        }
        BigDecimal amount = c.unitCost().multiply(BigDecimal.valueOf(-(long) c.qtyDelta()));

        locks.writer();
        Entry entry = Entry.of("mv:" + c.movementId(), account, amount, ctx.occurredAt(), calendar.dayOf(ctx.occurredAt()), "STOCK_COST", ctx.actor()).reason(reason)
                .sourceRecordedAt(c.recordedAt() != null ? c.recordedAt() : ctx.recordedAt()).event(ctx.eventId(), ctx.producer(), ctx.outboxId()).stockAction(c.actionId())
                .movement(c.movementId()).consignment(null, c.consignmentLineId()).variant(c.variantId()).product(c.productId()).stockKind(c.kind()).estimated(c.estimated());
        if ("SALE".equals(c.actionKind())) {
            entry.saleLine(c.actionId());
        }
        ledger.post(entry);
        if (c.productId() == null) {
            anomalies.raise("unattributed-product", c.actionId().toString(), Severity.ERROR, Map.of("actionId", c.actionId().toString(), "movementId", c.movementId()), ctx.eventId());
        }
    }

    private static StockCost read(byte[] payload) {
        try {
            return EventJson.mapper().readValue(payload, StockCost.class);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("stock.cost_recorded cannot be read: " + e.getOriginalMessage(), e);
        }
    }

    private static void validate(StockCost c) {
        if (c.movementId() == null || c.actionId() == null) {
            throw new IllegalArgumentException("stock.cost_recorded names its movement and its action");
        }
        if (!"ZMW".equals(c.currency())) {
            throw new IllegalArgumentException("stock.cost_recorded is in ZMW only, was " + c.currency());
        }
        if (c.role() == null || !ROLES.contains(c.role())) {
            throw new IllegalArgumentException("a row that moves a cost is ORIGINAL, RECUT_REVERSAL or RECUT_REPLACEMENT, was " + c.role());
        }
        if (!"SELLABLE".equals(c.kind()) && !"PACKAGING".equals(c.kind())) {
            throw new IllegalArgumentException("stock is SELLABLE or PACKAGING, was " + c.kind());
        }
        if (c.qtyDelta() == 0) {
            throw new IllegalArgumentException("a stock movement moves at least one piece");
        }
        if (c.unitCost() == null) {
            throw new IllegalArgumentException("stock.cost_recorded has no unitCost");
        }
        SaleApplier.ngwee(c.unitCost(), "a unit cost");
        if (c.unitCost().signum() < 0) {
            throw new IllegalArgumentException("a unit cost is not below nothing, was " + c.unitCost().toPlainString());
        }
        if (c.reason() == null) {
            throw new IllegalArgumentException("stock.cost_recorded has no reason");
        }
    }
}
