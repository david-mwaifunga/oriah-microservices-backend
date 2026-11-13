package com.oriah.inventory.service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.oriah.inventory.events.ReceiptSnapshot;
import com.oriah.inventory.exception.InsufficientStockException;
import com.oriah.inventory.ledger.MovementTimes;
import com.oriah.inventory.ledger.StockReason;
import com.oriah.inventory.model.Inventory;
import com.oriah.inventory.repository.InventoryRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Stock with its cost: what a received consignment line becomes, and (in the next steps) what drains it.
 *
 * <p>A receipt or a correction takes the variant's advisory lock (which works before the stock row exists) and the stock
 * row's own lock; a sale, write-off or take-back is optimistic (the stock row is written first, so of two changes racing
 * on one row exactly one applies and the other is told to retry). Every change makes its change to the lots and the
 * ledger, and ends by checking the invariants: each lot holds what its ledger rows add up to, and the lots hold exactly
 * the stock count. A change that would break them throws, and the transaction rolls back whole.
 */
@Service
@Slf4j
public class StockService {

    /** What the event envelope adds to a receipt: who, when, and where it sits in the producer's log. */
    public record EventContext(UUID eventId, long outboxId, String actor, Instant occurredAt) {
    }

    /** A lot as it is held now, oldest first. */
    public record HeldLot(UUID id, UUID variantId, UUID consignmentLineId, int slicePosition, int qtyReceived, int qtyRemaining,
                          BigDecimal unitCost, Instant receivedAt, boolean estimated) {
    }

    /** A write-off or give-away as it was recorded, and the stock counts after it. {@code replay}: this was a repeat. */
    public record WriteOffResult(UUID actionId, UUID variantId, StockReason reason, int qty, Instant occurredAt, int onHand, int reserved,
                                 int available, int committed, List<BoxCommitmentService.CutCommitment> cutCommitments, boolean replay) {
    }

    /** A take-back as it was recorded, how many of the write-off are still out, and the stock counts after it. */
    public record TakeBackResult(UUID actionId, UUID writeOffActionId, UUID variantId, int qty, Instant occurredAt, int outstanding,
                                 int onHand, int reserved, int available, int committed, boolean replay) {
    }

    /** One write-off or give-away, with what has come back. No cost: reading cost is a different capability. */
    public record WriteOffRow(UUID actionId, UUID variantId, String sku, String productTitle, String variantTitle, StockReason reason, int qty,
                              int takenBack, int outstanding, Instant occurredAt, Instant recordedAt, String actor, String note) {
    }

    /** A stock count that disagrees with what its lots hold. */
    public record CountMismatch(UUID variantId, int onHand, int heldInLots, int difference) {
    }

    /** A lot whose remaining quantity is not what its ledger rows add up to. */
    public record LedgerMismatch(UUID lotId, UUID variantId, int qtyRemaining, int ledgerSum) {
    }

    /** A box whose set-aside count is not what the products' records add up to. */
    public record CommitmentMismatch(UUID variantId, int committed, int sumOfCommitments) {
    }

    /**
     * The one-count-not-three check: every stock count against its lots, every lot against its ledger, and every box's set-aside count
     * against the products' records. Counts only, no cost.
     */
    public record ReconciliationReport(int checkedVariants, List<CountMismatch> countMismatches, List<LedgerMismatch> ledgerMismatches,
                                       List<CommitmentMismatch> commitmentMismatches) {
    }

    private final InventoryRepository inventories;
    private final StockLedger ledger;
    private final Clock clock;
    private final JdbcTemplate jdbc;
    private final EntityManager entityManager;
    private final BoxCommitmentService commitments;

    public StockService(InventoryRepository inventories, StockLedger ledger, Clock clock, JdbcTemplate jdbc, EntityManager entityManager,
                        BoxCommitmentService commitments) {
        this.inventories = inventories;
        this.ledger = ledger;
        this.clock = clock;
        this.jdbc = jdbc;
        this.entityManager = entityManager;
        this.commitments = commitments;
    }

    /**
     * A consignment line has landed: one lot per slice, the stock count up by the quantity, a ledger row per lot.
     *
     * @throws IllegalArgumentException the snapshot breaks a promise it makes (never repaired: the event is refused)
     * @throws IllegalStateException    the line was already received, the variant's kind disagrees, or the stock row
     *                                  disagrees with its lots
     */
    @Transactional
    public void applyReceipt(ReceiptSnapshot snapshot, EventContext context) {
        snapshot.validate();
        receive(snapshot, context, 1);
    }

    /**
     * A consignment line's cost was corrected: a new revision, restated in full. The line's units are laid over the NEW
     * unit costs, oldest first, so every sale, write-off and give-away that already drained those units is re-cut at its
     * own date, what is still held is re-valued, and the old lots are kept, marked replaced. The stock count does not
     * move, and nothing is an expense: cost simply goes where it belongs.
     *
     * <p>An amendment that arrives BEFORE the receipt it corrects (the live and replay streams interleave) is a complete
     * snapshot, so it makes the stock itself, at the corrected costs; the receipt, when it comes, is skipped.
     *
     * @throws IllegalArgumentException the snapshot breaks a promise, or changes the quantity
     * @throws IllegalStateException    the revision is not after the line's current one
     */
    @Transactional
    public void applyAmendment(ReceiptSnapshot snapshot, EventContext context) {
        snapshot.validateAmendment();
        UUID variantId = snapshot.variantId();
        ledger.lockVariant(variantId);
        List<StockLedger.LineLot> current = ledger.currentLotsOfLine(snapshot.consignmentLineId());
        if (current.isEmpty()) {
            receive(snapshot, context, snapshot.revision());
            return;
        }
        StockLedger.LineLot first = current.get(0);
        if (snapshot.revision() <= first.revision()) {
            throw new IllegalStateException("amendment revision " + snapshot.revision() + " is not after the line's current revision " + first.revision());
        }
        int heldUnits = current.stream().mapToInt(StockLedger.LineLot::qtyReceived).sum();
        if (heldUnits != snapshot.quantity()) {
            throw new IllegalArgumentException("an amendment cannot change the quantity: the line has " + heldUnits + " units, the amendment says "
                    + snapshot.quantity());
        }
        if (!first.variantId().equals(variantId) || !first.kind().equals(snapshot.kind())) {
            throw new IllegalArgumentException("an amendment cannot change the variant or its kind");
        }
        if (sameCosts(current, snapshot)) {
            log.info("Amendment {} of line {} changes no cost: nothing to do", snapshot.revision(), snapshot.consignmentLineId());
            return;
        }
        Inventory inventory = inventories.findById(variantId)
                .orElseThrow(() -> new IllegalStateException("the stock row of variant " + variantId + " is gone"));
        // A correction changes lots, not the count, so nothing would move the stock row's version on its own: force it, so a
        // sale racing the correction is refused (409, retry) rather than drawing from lots that are being replaced.
        entityManager.lock(inventory, LockModeType.PESSIMISTIC_FORCE_INCREMENT);
        ledger.verify(variantId, inventory.onHand());

        // The producer's own time for the correction: authoritative, so not checked against this clock (a replay brings it later).
        Instant amendedAt = snapshot.amendedAtInstant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        ledger.insertAction(context.eventId(), variantId, "CORRECTION", StockReason.COST_CORRECTED, snapshot.quantity(), amendedAt, context.actor(),
                snapshot.reason(), null, null);
        Set<UUID> staged = new LinkedHashSet<>();
        current.forEach(l -> staged.add(l.id()));
        int position = 0;
        for (ReceiptSnapshot.Slice slice : snapshot.slices()) {
            UUID lotId = UUID.randomUUID();
            // What the line is (its time, place in its receipt, names, supplier) is copied from the lots it replaces, as at the
            // receipt: a renamed supplier or product does not rewrite history.
            ledger.insertLot(new StockLedger.NewLot(lotId, variantId, first.kind(), first.sku(), first.productTitle(), first.variantTitle(),
                    first.consignmentId(), first.consignmentReference(), snapshot.consignmentLineId(), first.supplierId(), first.supplierName(),
                    first.receivedAt(), first.fifoTiebreak(), ++position, snapshot.revision(), slice.quantity(), slice.quantity(), slice.unitCost(),
                    snapshot.isEstimated()));
            ledger.insertMovement(variantId, context.eventId(), lotId, StockReason.COST_CORRECTED, "LOT_OPEN", slice.quantity(), slice.unitCost(),
                    snapshot.isEstimated(), first.kind(), null, null, amendedAt, context.actor());
        }
        int recut = ledger.conform(variantId, context.actor(), staged);
        ledger.closeLots(variantId, staged, context.eventId(), StockReason.COST_CORRECTED, amendedAt, context.actor());
        ledger.verify(variantId, inventory.onHand());
        ledger.verifyLineValuation(snapshot.consignmentLineId(), snapshot.landedTotal());
        log.info("Amended line {} of {} to revision {}: {} action(s) re-cut", snapshot.consignmentLineId(), snapshot.consignmentReference(),
                snapshot.revision(), recut);
    }

    private static boolean sameCosts(List<StockLedger.LineLot> current, ReceiptSnapshot snapshot) {
        if (current.size() != snapshot.slices().size()) {
            return false;
        }
        for (int i = 0; i < current.size(); i++) {
            StockLedger.LineLot lot = current.get(i);
            ReceiptSnapshot.Slice slice = snapshot.slices().get(i);
            if (lot.qtyReceived() != slice.quantity() || lot.unitCost().compareTo(slice.unitCost()) != 0 || lot.estimated() != snapshot.isEstimated()) {
                return false;
            }
        }
        return true;
    }

    /** The receipt itself: shared by a receipt and by an amendment that arrives as the first thing known about a line. */
    private void receive(ReceiptSnapshot snapshot, EventContext context, int revision) {
        UUID variantId = snapshot.variantId();
        ledger.lockVariant(variantId);

        if (ledger.actionExists(snapshot.consignmentLineId()) || ledger.lineHasLots(snapshot.consignmentLineId())) {
            throw new IllegalStateException("consignment line " + snapshot.consignmentLineId() + " was already received");
        }
        // Read WITH its row lock, before the check below: a sale, write-off or take-back that commits between a plain read
        // and the check would make a valid receipt look corrupt, and a corrupt-looking receipt is parked for good. Locked,
        // such a change either commits first (and is seen) or waits, and then finds the row's version moved (a retry).
        Inventory inventory = inventories.findByIdForUpdate(variantId).orElse(null);
        if (inventory == null) {
            inventory = Inventory.open(variantId, snapshot.kind());
        } else {
            if (!inventory.kind().equals(snapshot.kind())) {
                throw new IllegalStateException("variant " + variantId + " is " + inventory.kind()
                        + " and a receipt says its kind is " + snapshot.kind() + ": a kind never changes");
            }
            ledger.verify(variantId, inventory.onHand());
        }

        // The count moves first and is written at once: of two changes racing on one row the loser is refused here
        // (a concurrent-update 409), before it has touched a single lot.
        inventory.receive(snapshot.quantity());
        inventory = inventories.saveAndFlush(inventory);

        Instant receivedAt = snapshot.receivedAtInstant();
        ledger.insertAction(snapshot.consignmentLineId(), variantId, "RECEIPT", StockReason.CONSIGNMENT_RECEIVED, snapshot.quantity(),
                receivedAt, context.actor(), null, null, null);
        long tiebreak = snapshot.linePosition() != null ? snapshot.linePosition() : context.outboxId();
        int position = 0;
        for (ReceiptSnapshot.Slice slice : snapshot.slices()) {
            UUID lotId = UUID.randomUUID();
            ledger.insertLot(new StockLedger.NewLot(lotId, variantId, snapshot.kind(), snapshot.sku(), snapshot.productTitle(),
                    snapshot.variantTitle(), snapshot.consignmentId(), snapshot.consignmentReference(), snapshot.consignmentLineId(),
                    snapshot.supplierId(), snapshot.supplierName(), receivedAt, tiebreak, ++position, revision, slice.quantity(),
                    slice.quantity(), slice.unitCost(), snapshot.isEstimated()));
            ledger.insertMovement(variantId, snapshot.consignmentLineId(), lotId, StockReason.CONSIGNMENT_RECEIVED, "ORIGINAL",
                    slice.quantity(), slice.unitCost(), snapshot.isEstimated(), snapshot.kind(), null, null, receivedAt, context.actor());
        }

        // A receipt older than stock already sold from newer lots re-cuts those sales to take it first.
        int recut = ledger.conform(variantId, context.actor());
        ledger.verify(variantId, inventory.onHand());
        ledger.verifyLineValuation(snapshot.consignmentLineId(), snapshot.landedTotal());
        log.info("Received line {} of {}: {} units of {} in {} lot(s); {} sale(s) re-cut", snapshot.consignmentLineId(),
                snapshot.consignmentReference(), snapshot.quantity(), variantId, snapshot.slices().size(), recut);
    }

    /**
     * Units reserved for a sale are consumed: the count and the reservation fall, the OLDEST lots are drained, and the
     * ledger gets one row per lot drained, all in this transaction.
     *
     * <p>Idempotent per {@code saleLineId}: the same sale line with the same request answers again and takes nothing; a
     * sale line already used for a different request is refused (409). It uses optimistic locking, like every
     * reserve and release: of two changes racing on one row exactly one applies and the other is told to retry.
     *
     * @param occurredAt when the sale happened; null means now; never in the future, and never refused for being old
     * @return the stock row after the change, or empty if the variant has no stock row (a 404)
     * @throws InsufficientStockException when more than is reserved is asked for
     * @throws IllegalStateException      when the sale line was already used for something else
     */
    @Transactional
    public Optional<Inventory> consume(UUID variantId, int qty, UUID saleLineId, Instant occurredAt, String actor) {
        String digest = digest(variantId, qty, occurredAt);
        // The repeat check first, the row after: a repeat is answered with the stock as it is AFTER the original (read the
        // other way round, the original could commit in between and the answer would carry the count from before it).
        Optional<StockLedger.ActionRow> existing = ledger.findAction(saleLineId);
        Inventory inventory = inventories.findById(variantId).orElse(null);
        if (inventory == null) {
            return Optional.empty();
        }
        if (existing.isPresent()) {
            StockLedger.ActionRow was = existing.get();
            if (was.variantId().equals(variantId) && "SALE".equals(was.kind()) && was.qty() == qty && digest.equals(was.digest())) {
                return Optional.of(inventory);
            }
            throw new IllegalStateException("sale line " + saleLineId + " was already used for a different request");
        }
        Instant when = MovementTimes.resolve(occurredAt, clock.instant(), null);
        inventory.confirm(qty);
        // Written at once: of two sales racing on one row the loser is refused here (a concurrent-update 409), before it
        // has touched a single lot.
        inventories.saveAndFlush(inventory);
        ledger.insertAction(saleLineId, variantId, "SALE", StockReason.SALE, qty, when, actor, null, null, digest);
        ledger.conform(variantId, actor);
        ledger.verify(variantId, inventory.onHand());
        log.info("Sold {} of {} for sale line {} ({} left)", qty, variantId, saleLineId, inventory.onHand());
        return Optional.of(inventory);
    }

    /**
     * A sale of boxes that were set aside for a product and never reserved (the box swapped in after payment is one): the product's record
     * and the stock count both fall by what was sold, the OLDEST lots of the box are drained, and the ledger gets one row per lot, all in
     * this transaction. A sale of a set-aside box that only lowered the count would leave the promise outliving the box it was for.
     *
     * <p>An internal stock operation, not an endpoint: the order system calls it (Phase 7) and never a person. Idempotent per
     * {@code saleLineId} like {@link #consume}.
     *
     * @return the stock row after the change, or empty if the box has no stock row
     * @throws InsufficientStockException when the product has fewer than {@code qty} set aside on this box
     * @throws IllegalStateException      when the sale line was already used for something else
     */
    @Transactional
    public Optional<Inventory> consumeCommitted(UUID productId, UUID variantId, int qty, UUID saleLineId, Instant occurredAt, String actor) {
        String digest = digest(variantId + "|" + qty + "|" + occurredAt + "|set-aside for " + productId);
        Optional<StockLedger.ActionRow> existing = ledger.findAction(saleLineId);
        Inventory inventory = inventories.findById(variantId).orElse(null);
        if (inventory == null) {
            return Optional.empty();
        }
        if (existing.isPresent()) {
            StockLedger.ActionRow was = existing.get();
            if (was.variantId().equals(variantId) && "SALE".equals(was.kind()) && was.qty() == qty && digest.equals(was.digest())) {
                return Optional.of(inventory);
            }
            throw new IllegalStateException("sale line " + saleLineId + " was already used for a different request");
        }
        Instant when = MovementTimes.resolve(occurredAt, clock.instant(), null);
        inventory.consumeCommitted(qty);
        inventories.saveAndFlush(inventory);
        commitments.consumeFromCommitment(productId, variantId, qty, saleLineId, actor, when);
        ledger.insertAction(saleLineId, variantId, "SALE", StockReason.SALE, qty, when, actor, null, null, digest);
        ledger.conform(variantId, actor);
        ledger.verify(variantId, inventory.onHand());
        commitments.verifyBox(variantId);
        log.info("Sold {} set-aside boxes {} for product {} (sale line {}, {} left)", qty, variantId, productId, saleLineId, inventory.onHand());
        return Optional.of(inventory);
    }

    /**
     * Stock leaves with no sale: written off (lost, stolen, broken) or given away (to a model, an influencer, as a gift
     * or a prize). It takes the OLDEST lots at their cost and releases that cost into the month it happened, as a ledger
     * row with a reason of its own. Only stock nobody has reserved can go.
     *
     * <p>{@code requestId} makes a retry safe: the same request answers again (with the time the first one recorded)
     * and takes nothing; the same id for anything else is refused (409).
     *
     * @param occurredAt when it happened; null means now; never in the future, and never before the stock it takes
     *                   arrived
     * @return the result, or empty if the variant has no stock row (a 404)
     * @throws IllegalArgumentException   the reason is not a write-off or give-away reason, or the date breaks the rule
     * @throws InsufficientStockException more than is available
     * @throws IllegalStateException      the request id was already used for something else
     */
    @Transactional
    public Optional<WriteOffResult> writeOff(UUID requestId, UUID variantId, int qty, StockReason reason, Instant occurredAt, String note,
                                             String actor) {
        if (reason == null || !reason.isWriteOffOrGiveAway()) {
            throw new IllegalArgumentException("a write-off or give-away needs one of the write-off or give-away reasons, was " + reason);
        }
        String digest = digest(variantId, qty, reason, occurredAt, note, null);
        // The repeat check first, the row after (see consume).
        Optional<StockLedger.ActionRow> existing = ledger.findAction(requestId);
        Inventory inventory = inventories.findById(variantId).orElse(null);
        if (inventory == null) {
            return Optional.empty();
        }
        if (existing.isPresent()) {
            StockLedger.ActionRow was = existing.get();
            boolean same = was.variantId().equals(variantId) && was.kind().matches("WRITE_OFF|GIVE_AWAY") && was.qty() == qty
                    && was.reason() == reason && digest.equals(was.digest());
            if (!same) {
                throw new IllegalStateException("request " + requestId + " was already used for a different request");
            }
            return Optional.of(new WriteOffResult(was.id(), variantId, was.reason(), was.qty(), was.occurredAt(), inventory.onHand(),
                    inventory.reserved(), inventory.available(), inventory.committed(), commitments.cutsOf(was.id()), true));
        }
        Instant now = clock.instant();
        Instant when = MovementTimes.resolve(occurredAt, now, null);
        // Free boxes go first; only for what free cannot absorb are boxes set aside for products taken, and the products named.
        int cut = inventory.writeOff(qty);
        inventories.saveAndFlush(inventory);
        List<BoxCommitmentService.CutCommitment> cuts = cut > 0 ? commitments.cutForWriteOff(variantId, cut, requestId, actor, when) : List.of();
        ledger.insertAction(requestId, variantId, reason.category() == StockReason.Category.WRITE_OFF ? "WRITE_OFF" : "GIVE_AWAY", reason,
                qty, when, actor, note, null, digest);
        ledger.conform(variantId, actor);
        ledger.verify(variantId, inventory.onHand());
        commitments.verifyBox(variantId);
        // Stock cannot have left before it arrived: checked against the lots it actually came out of.
        MovementTimes.resolve(when, now, ledger.latestReceivedAmongLiveRows(requestId));
        log.info("{} of {} (action {}): {} left", reason, variantId, requestId, inventory.onHand());
        return Optional.of(new WriteOffResult(requestId, variantId, reason, qty, when, inventory.onHand(), inventory.reserved(),
                inventory.available(), inventory.committed(), cuts, false));
    }

    /**
     * Some of what was written off or given away comes back: the units return to the lots they came out of, newest
     * first, at the cost they left at, and only as many as are still out.
     *
     * @return the result, or empty if there is no such write-off or give-away (a 404)
     * @throws IllegalStateException      the action is not a write-off or give-away, or the request id was already used
     * @throws InsufficientStockException more than is still out
     * @throws IllegalArgumentException   the date is before the write-off it takes back, or in the future
     */
    @Transactional
    public Optional<TakeBackResult> takeBack(UUID requestId, UUID writeOffActionId, int qty, Instant occurredAt, String note, String actor) {
        StockLedger.ActionRow original = ledger.findAction(writeOffActionId).orElse(null);
        if (original == null) {
            return Optional.empty();
        }
        if (!original.kind().equals("WRITE_OFF") && !original.kind().equals("GIVE_AWAY")) {
            throw new IllegalStateException("action " + writeOffActionId + " is a " + original.kind()
                    + ", not a write-off or a give-away: only those can be taken back");
        }
        UUID variantId = original.variantId();
        String digest = digest(variantId, qty, StockReason.TAKEN_BACK, occurredAt, note, writeOffActionId);
        // The repeat check first, the row after (see consume).
        Optional<StockLedger.ActionRow> existing = ledger.findAction(requestId);
        Inventory inventory = inventories.findById(variantId).orElseThrow(
                () -> new IllegalStateException("the stock row of variant " + variantId + " is gone"));
        int outstandingBefore = original.qty() - ledger.takenBack(writeOffActionId);
        if (existing.isPresent()) {
            StockLedger.ActionRow was = existing.get();
            boolean same = was.kind().equals("TAKE_BACK") && writeOffActionId.equals(was.reversesActionId()) && was.qty() == qty
                    && digest.equals(was.digest());
            if (!same) {
                throw new IllegalStateException("request " + requestId + " was already used for a different request");
            }
            return Optional.of(new TakeBackResult(was.id(), writeOffActionId, variantId, was.qty(), was.occurredAt(), outstandingBefore,
                    inventory.onHand(), inventory.reserved(), inventory.available(), inventory.committed(), true));
        }
        if (qty > outstandingBefore) {
            throw InsufficientStockException.cannot("take back", qty, outstandingBefore, "outstanding");
        }
        Instant now = clock.instant();
        Instant when = MovementTimes.resolve(occurredAt, now, null);
        if (when.isBefore(original.occurredAt())) {
            if (occurredAt != null) {
                throw new IllegalArgumentException("occurredAt " + occurredAt + " is before the write-off it takes back (" + original.occurredAt() + ")");
            }
            // No date was given, so "now": but a write-off may have been accepted a little ahead of this clock (the
            // allowance for clocks that disagree), and stock cannot come back before it left. A date nobody chose is
            // moved up to the write-off's own, never refused (and never left to put the restoration in an earlier month).
            when = original.occurredAt();
        }
        inventory.takeBack(qty);
        inventories.saveAndFlush(inventory);
        ledger.insertAction(requestId, variantId, "TAKE_BACK", StockReason.TAKEN_BACK, qty, when, actor, note, writeOffActionId, digest);
        ledger.conform(variantId, actor);
        ledger.verify(variantId, inventory.onHand());
        MovementTimes.resolve(when, now, ledger.latestReceivedAmongLiveRows(requestId));
        log.info("Took back {} of {} from action {} ({} still out)", qty, variantId, writeOffActionId, outstandingBefore - qty);
        return Optional.of(new TakeBackResult(requestId, writeOffActionId, variantId, qty, when, outstandingBefore - qty,
                inventory.onHand(), inventory.reserved(), inventory.available(), inventory.committed(), false));
    }

    /** Write-offs and give-aways, newest first, each with what has come back. Carries no cost. */
    @Transactional(readOnly = true)
    public Page<WriteOffRow> writeOffs(UUID variantId, int page, int size) {
        String where = " WHERE a.kind IN ('WRITE_OFF', 'GIVE_AWAY')" + (variantId == null ? "" : " AND a.variant_id = ?");
        Object[] filter = variantId == null ? new Object[0] : new Object[] {variantId};
        long total = jdbc.queryForObject("SELECT count(*) FROM stock_action a" + where, Long.class, filter);
        Object[] args = java.util.Arrays.copyOf(filter, filter.length + 2);
        args[filter.length] = size;
        args[filter.length + 1] = (long) page * size;
        List<WriteOffRow> rows = jdbc.query(WRITE_OFF_SELECT + where + " ORDER BY a.seq DESC LIMIT ? OFFSET ?", this::writeOffRow, args);
        return new PageImpl<>(rows, PageRequest.of(page, size), total);
    }

    @Transactional(readOnly = true)
    public Optional<WriteOffRow> findWriteOff(UUID actionId) {
        return jdbc.query(WRITE_OFF_SELECT + " WHERE a.kind IN ('WRITE_OFF', 'GIVE_AWAY') AND a.id = ?", this::writeOffRow, actionId).stream().findFirst();
    }

    private static final String WRITE_OFF_SELECT = """
            SELECT a.id, a.variant_id, a.reason, a.qty, a.occurred_at, a.recorded_at, a.actor, a.note,
                   coalesce((SELECT sum(t.qty) FROM stock_action t WHERE t.reverses_action_id = a.id), 0) AS taken_back,
                   (SELECT l.sku FROM cost_lot l WHERE l.variant_id = a.variant_id ORDER BY l.received_at DESC, l.id LIMIT 1) AS sku,
                   (SELECT l.product_title FROM cost_lot l WHERE l.variant_id = a.variant_id ORDER BY l.received_at DESC, l.id LIMIT 1) AS product_title,
                   (SELECT l.variant_title FROM cost_lot l WHERE l.variant_id = a.variant_id ORDER BY l.received_at DESC, l.id LIMIT 1) AS variant_title
              FROM stock_action a""";

    private WriteOffRow writeOffRow(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        int qty = rs.getInt("qty");
        int takenBack = rs.getInt("taken_back");
        return new WriteOffRow(rs.getObject("id", UUID.class), rs.getObject("variant_id", UUID.class), rs.getString("sku"),
                rs.getString("product_title"), rs.getString("variant_title"), StockReason.fromCode(rs.getString("reason")), qty, takenBack,
                qty - takenBack, rs.getTimestamp("occurred_at").toInstant(), rs.getTimestamp("recorded_at").toInstant(),
                rs.getString("actor"), rs.getString("note"));
    }

    private static String digest(UUID variantId, int qty, StockReason reason, Instant occurredAt, String note, UUID reverses) {
        return digest(variantId + "|" + qty + "|" + reason + "|" + occurredAt + "|" + note + "|" + reverses);
    }

    private static String digest(UUID variantId, int qty, Instant occurredAt) {
        return digest(variantId + "|" + qty + "|" + occurredAt);
    }

    private static String digest(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Checks that {@code on_hand} is the single count of a thing: for every stock row, the lots still current hold exactly
     * that many; and for every lot, what is left is what its ledger rows add up to. A clean system reports nothing wrong.
     *
     * @param variantId one variant, or null for all
     */
    @Transactional(readOnly = true)
    public ReconciliationReport reconcile(UUID variantId) {
        Object[] filter = variantId == null ? new Object[0] : new Object[] {variantId};
        String forVariant = variantId == null ? "" : " AND i.variant_id = ?";
        int checked = jdbc.queryForObject("SELECT count(*) FROM inventory i WHERE true" + forVariant, Integer.class, filter);
        List<CountMismatch> counts = jdbc.query("""
                SELECT i.variant_id, i.on_hand, coalesce(h.held, 0) AS held
                  FROM inventory i
                  LEFT JOIN (SELECT variant_id, sum(qty_remaining) AS held FROM cost_lot WHERE superseded_at IS NULL GROUP BY variant_id) h
                         ON h.variant_id = i.variant_id
                 WHERE i.on_hand <> coalesce(h.held, 0)""" + forVariant + " ORDER BY i.variant_id",
                (rs, n) -> new CountMismatch(rs.getObject("variant_id", UUID.class), rs.getInt("on_hand"), rs.getInt("held"),
                        rs.getInt("on_hand") - rs.getInt("held")), filter);
        String forLotVariant = variantId == null ? "" : " AND l.variant_id = ?";
        List<LedgerMismatch> ledgers = jdbc.query("""
                SELECT l.id, l.variant_id, l.qty_remaining, coalesce(m.sum, 0) AS ledger_sum
                  FROM cost_lot l
                  LEFT JOIN (SELECT lot_id, sum(qty_delta) AS sum FROM stock_movement GROUP BY lot_id) m ON m.lot_id = l.id
                 WHERE l.qty_remaining <> coalesce(m.sum, 0)""" + forLotVariant + " ORDER BY l.id",
                (rs, n) -> new LedgerMismatch(rs.getObject("id", UUID.class), rs.getObject("variant_id", UUID.class), rs.getInt("qty_remaining"),
                        rs.getInt("ledger_sum")), filter);
        List<CommitmentMismatch> setAside = jdbc.query("""
                SELECT i.variant_id, i.committed, coalesce(c.total, 0) AS total
                  FROM inventory i
                  LEFT JOIN (SELECT box_variant_id, sum(quantity) AS total FROM box_commitment GROUP BY box_variant_id) c ON c.box_variant_id = i.variant_id
                 WHERE i.committed <> coalesce(c.total, 0)""" + forVariant + " ORDER BY i.variant_id",
                (rs, n) -> new CommitmentMismatch(rs.getObject("variant_id", UUID.class), rs.getInt("committed"), rs.getInt("total")), filter);
        return new ReconciliationReport(checked, counts, ledgers, setAside);
    }

    /** The variant's current lots, oldest first (the order FIFO draws in). */
    @Transactional(readOnly = true)
    public List<HeldLot> heldLots(UUID variantId) {
        return ledger.heldLots(variantId).stream()
                .map(l -> new HeldLot(l.id(), l.variantId(), l.consignmentLineId(), l.slicePosition(), l.qtyReceived(), l.qtyRemaining(),
                        l.unitCost(), l.receivedAt(), l.estimated()))
                .toList();
    }
}
