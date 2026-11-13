package com.oriah.inventory.service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

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
 * <p>Every change to the stock of a variant takes that variant's lock first (an advisory lock, which works before the
 * stock row exists), makes its change to the lots and the ledger, and ends by checking the invariants: each lot holds
 * what its ledger rows add up to, and the lots hold exactly the stock count. A change that would break them throws, and
 * the transaction rolls back whole.
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
                                 int available, boolean replay) {
    }

    /** A take-back as it was recorded, how many of the write-off are still out, and the stock counts after it. */
    public record TakeBackResult(UUID actionId, UUID writeOffActionId, UUID variantId, int qty, Instant occurredAt, int outstanding,
                                 int onHand, int reserved, int available, boolean replay) {
    }

    /** One write-off or give-away, with what has come back. No cost: reading cost is a different capability. */
    public record WriteOffRow(UUID actionId, UUID variantId, String sku, String productTitle, String variantTitle, StockReason reason, int qty,
                              int takenBack, int outstanding, Instant occurredAt, Instant recordedAt, String actor, String note) {
    }

    private final InventoryRepository inventories;
    private final StockLedger ledger;
    private final Clock clock;
    private final JdbcTemplate jdbc;

    public StockService(InventoryRepository inventories, StockLedger ledger, Clock clock, JdbcTemplate jdbc) {
        this.inventories = inventories;
        this.ledger = ledger;
        this.clock = clock;
        this.jdbc = jdbc;
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
        UUID variantId = snapshot.variantId();
        ledger.lockVariant(variantId);

        if (ledger.actionExists(snapshot.consignmentLineId()) || ledger.lineHasLots(snapshot.consignmentLineId())) {
            throw new IllegalStateException("consignment line " + snapshot.consignmentLineId() + " was already received");
        }
        Inventory inventory = inventories.findById(variantId).orElse(null);
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
                    snapshot.supplierId(), snapshot.supplierName(), receivedAt, tiebreak, ++position, 1, slice.quantity(),
                    slice.quantity(), slice.unitCost(), snapshot.isEstimated()));
            ledger.insertMovement(variantId, snapshot.consignmentLineId(), lotId, StockReason.CONSIGNMENT_RECEIVED, "ORIGINAL",
                    slice.quantity(), slice.unitCost(), snapshot.isEstimated(), snapshot.kind(), null, null, receivedAt, context.actor());
        }

        // A receipt older than stock already sold from newer lots re-cuts those sales to take it first.
        int recut = ledger.conform(variantId, context.actor());
        ledger.verify(variantId, inventory.onHand());
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
        Inventory inventory = inventories.findById(variantId).orElse(null);
        if (inventory == null) {
            return Optional.empty();
        }
        String digest = digest(variantId, qty, occurredAt);
        Optional<StockLedger.ActionRow> existing = ledger.findAction(saleLineId);
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
        Inventory inventory = inventories.findById(variantId).orElse(null);
        if (inventory == null) {
            return Optional.empty();
        }
        String digest = digest(variantId, qty, reason, occurredAt, note, null);
        Optional<StockLedger.ActionRow> existing = ledger.findAction(requestId);
        if (existing.isPresent()) {
            StockLedger.ActionRow was = existing.get();
            boolean same = was.variantId().equals(variantId) && was.kind().matches("WRITE_OFF|GIVE_AWAY") && was.qty() == qty
                    && was.reason() == reason && digest.equals(was.digest());
            if (!same) {
                throw new IllegalStateException("request " + requestId + " was already used for a different request");
            }
            return Optional.of(new WriteOffResult(was.id(), variantId, was.reason(), was.qty(), was.occurredAt(), inventory.onHand(),
                    inventory.reserved(), inventory.available(), true));
        }
        Instant now = clock.instant();
        Instant when = MovementTimes.resolve(occurredAt, now, null);
        inventory.writeOff(qty);
        inventories.saveAndFlush(inventory);
        ledger.insertAction(requestId, variantId, reason.category() == StockReason.Category.WRITE_OFF ? "WRITE_OFF" : "GIVE_AWAY", reason,
                qty, when, actor, note, null, digest);
        ledger.conform(variantId, actor);
        ledger.verify(variantId, inventory.onHand());
        // Stock cannot have left before it arrived: checked against the lots it actually came out of.
        MovementTimes.resolve(when, now, ledger.latestReceivedAmongLiveRows(requestId));
        log.info("{} of {} (action {}): {} left", reason, variantId, requestId, inventory.onHand());
        return Optional.of(new WriteOffResult(requestId, variantId, reason, qty, when, inventory.onHand(), inventory.reserved(),
                inventory.available(), false));
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
        Inventory inventory = inventories.findById(variantId).orElseThrow(
                () -> new IllegalStateException("the stock row of variant " + variantId + " is gone"));
        String digest = digest(variantId, qty, StockReason.TAKEN_BACK, occurredAt, note, writeOffActionId);
        Optional<StockLedger.ActionRow> existing = ledger.findAction(requestId);
        int outstandingBefore = original.qty() - ledger.takenBack(writeOffActionId);
        if (existing.isPresent()) {
            StockLedger.ActionRow was = existing.get();
            boolean same = was.kind().equals("TAKE_BACK") && writeOffActionId.equals(was.reversesActionId()) && was.qty() == qty
                    && digest.equals(was.digest());
            if (!same) {
                throw new IllegalStateException("request " + requestId + " was already used for a different request");
            }
            return Optional.of(new TakeBackResult(was.id(), writeOffActionId, variantId, was.qty(), was.occurredAt(), outstandingBefore,
                    inventory.onHand(), inventory.reserved(), inventory.available(), true));
        }
        if (qty > outstandingBefore) {
            throw InsufficientStockException.cannot("take back", qty, outstandingBefore, "outstanding");
        }
        Instant now = clock.instant();
        Instant when = MovementTimes.resolve(occurredAt, now, null);
        if (occurredAt != null && occurredAt.isBefore(original.occurredAt())) {
            throw new IllegalArgumentException("occurredAt " + occurredAt + " is before the write-off it takes back (" + original.occurredAt() + ")");
        }
        inventory.takeBack(qty);
        inventories.saveAndFlush(inventory);
        ledger.insertAction(requestId, variantId, "TAKE_BACK", StockReason.TAKEN_BACK, qty, when, actor, note, writeOffActionId, digest);
        ledger.conform(variantId, actor);
        ledger.verify(variantId, inventory.onHand());
        MovementTimes.resolve(when, now, ledger.latestReceivedAmongLiveRows(requestId));
        log.info("Took back {} of {} from action {} ({} still out)", qty, variantId, writeOffActionId, outstandingBefore - qty);
        return Optional.of(new TakeBackResult(requestId, writeOffActionId, variantId, qty, when, outstandingBefore - qty,
                inventory.onHand(), inventory.reserved(), inventory.available(), false));
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

    /** The variant's current lots, oldest first (the order FIFO draws in). */
    @Transactional(readOnly = true)
    public List<HeldLot> heldLots(UUID variantId) {
        return ledger.heldLots(variantId).stream()
                .map(l -> new HeldLot(l.id(), l.variantId(), l.consignmentLineId(), l.slicePosition(), l.qtyReceived(), l.qtyRemaining(),
                        l.unitCost(), l.receivedAt(), l.estimated()))
                .toList();
    }
}
