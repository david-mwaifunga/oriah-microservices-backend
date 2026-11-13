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

    private final InventoryRepository inventories;
    private final StockLedger ledger;
    private final Clock clock;

    public StockService(InventoryRepository inventories, StockLedger ledger, Clock clock) {
        this.inventories = inventories;
        this.ledger = ledger;
        this.clock = clock;
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

    private static String digest(UUID variantId, int qty, Instant occurredAt) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest((variantId + "|" + qty + "|" + occurredAt).getBytes(StandardCharsets.UTF_8));
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
