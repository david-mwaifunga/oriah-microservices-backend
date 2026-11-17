package com.oriah.inventory.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.oriah.inventory.exception.CommitmentProblem;
import com.oriah.inventory.exception.InsufficientStockException;
import com.oriah.inventory.model.Inventory;
import com.oriah.inventory.repository.InventoryRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Boxes set aside for a product (CLIENT_DECISIONS 4.2).
 *
 * <h2>What it is</h2>
 *
 * A product normally goes out in one box (its default box, chosen in catalog). Setting boxes aside promises a quantity of that box to
 * the product, so that one product's boxes are not promised twice. The promise is a quantity, never a lot: it touches no cost lot, and
 * a sale still takes the oldest lot's cost. Each product's promise is a record of its own, so the right quantity is let go when it
 * should be, and a write-off of boxes can name the products it cut into.
 *
 * <h2>Letting go is judged by state, never by a step</h2>
 *
 * Inventory learns of a product from catalog's snapshots. After the newest snapshot applied, a product's boxes are let go when the
 * product is deleted, when it has been unpublished since they were set aside (catalog's count only goes up), or when its default box is
 * no longer the one they were set aside on. That is a function of the state the snapshot carries, not of the step between two
 * snapshots, so a snapshot that arrives late (the older one is discarded as stale), twice, or never (the next one carries the truth)
 * cannot lose a release. A snapshot that is dead-lettered and never followed by another leaves the boxes where they are: free looks lower
 * than it is, which is the safe direction.
 *
 * <h2>Locks, always in this order</h2>
 *
 * the product's state row, then the box's stock row, then the product's record. A write-off or a sale touches the box row first and the
 * records after, and never the state row, so no path takes them the other way round. Setting aside, releasing, the consumer's release and
 * every change to the count of a box take the box's row lock (pessimistically here, by the version check elsewhere), so two promises of
 * the last boxes take turns and the second finds them gone.
 *
 * <p>After every change the box's {@code committed} is checked to equal the sum of its records; a mismatch rolls the change back.
 */
@Service
@Slf4j
public class BoxCommitmentService {

    /** What inventory knows of a product, from catalog's snapshot. {@code kind} is SELLABLE or PACKAGING; an absent kind is SELLABLE. */
    public record ProductSnapshot(UUID productId, String kind, String status, UUID defaultPackagingVariantId, int unpublishCount, boolean deleted) {
    }

    /** One product's boxes. */
    public record Commitment(UUID id, UUID productId, UUID boxVariantId, int quantity, int held, int unpublishCount, Instant committedAt, String committedBy) {
    }

    /** One thing done to a product's boxes. */
    public record Change(long id, UUID commitmentId, UUID productId, UUID boxVariantId, String kind, int qtyDelta, int quantityAfter, UUID actionId,
                         UUID eventId, Instant occurredAt, String actor) {
    }

    /** The boxes a write-off took out of one product's set-aside. */
    public record CutCommitment(UUID productId, UUID boxVariantId, int cut) {
    }

    /** What the box looks like: held, set aside, reserved, free. */
    public record BoxCounts(int onHand, int committed, int reserved, int free) {
    }

    /** A saved commitment and the box it is on. */
    public record Saved(Commitment commitment, BoxCounts box) {
    }

    private final JdbcTemplate jdbc;
    private final InventoryRepository inventories;
    private final Clock clock;

    public BoxCommitmentService(JdbcTemplate jdbc, InventoryRepository inventories, Clock clock) {
        this.jdbc = jdbc;
        this.inventories = inventories;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- setting aside

    /**
     * Sets {@code quantity} boxes aside for the product, on its default box. Again for the same product, it REPLACES the record: more
     * takes from free, fewer gives back, the same changes nothing.
     *
     * @throws CommitmentProblem 409: {@code product-state-not-received}, {@code not-a-sellable-product}, {@code no-default-box},
     *                           {@code not-packaging}, {@code insufficient-free-packaging} (with how many can be set aside in all)
     * @throws IllegalArgumentException the quantity is not above zero
     */
    @Transactional
    public Saved set(UUID productId, int quantity, String actor) {
        if (quantity < 1) {
            throw new IllegalArgumentException("quantity must be at least 1 box (to let the boxes go, release them), was " + quantity);
        }
        ProductSnapshot state = lockState(productId).orElseThrow(() -> new CommitmentProblem(HttpStatus.CONFLICT, "product-state-not-received",
                "inventory has not yet heard of this product from the catalogue: any change to the product there sends it, then try again"));
        if (state.deleted() || !"SELLABLE".equals(state.kind())) {
            throw new CommitmentProblem(HttpStatus.CONFLICT, "not-a-sellable-product",
                    "boxes are set aside for a product that is for sale, and this one is " + (state.deleted() ? "deleted" : "packaging"));
        }
        UUID boxVariantId = state.defaultPackagingVariantId();
        if (boxVariantId == null) {
            throw new CommitmentProblem(HttpStatus.CONFLICT, "no-default-box",
                    "the product has no default box: choose one in the catalogue first, then set boxes aside");
        }
        Inventory box = inventories.findByIdForUpdate(boxVariantId).orElse(null);
        if (box == null) {
            throw notEnough(0);
        }
        if (!"PACKAGING".equals(box.kind())) {
            throw new CommitmentProblem(HttpStatus.CONFLICT, "not-packaging", "the product's default box is not packaging");
        }
        Optional<Commitment> existing = commitmentOf(productId);
        if (existing.isPresent() && !existing.get().boxVariantId().equals(boxVariantId)) {
            // Set aside on a box that is no longer the product's default: dead by the rule above, normally let go when the snapshot
            // arrived. Let go now (this product's own state row is already locked), so one record never straddles two boxes. The record is
            // read again under the old box's lock: an order may have held some of it since it was first read.
            Inventory oldBox = inventories.findByIdForUpdate(existing.get().boxVariantId()).orElse(null);
            Optional<Commitment> fresh = commitmentOf(productId);
            if (fresh.isPresent()) {
                releaseLocked(fresh.get(), oldBox, null, actor);
            }
            existing = Optional.empty();
        }
        if (existing.isPresent() && quantity < existing.get().held()) {
            throw new CommitmentProblem(HttpStatus.CONFLICT, "held-by-reservations",
                    "orders are holding " + existing.get().held() + " of this product's boxes right now: set aside at least that many, or wait until they are paid or let go")
                    .with("held", existing.get().held());
        }
        int current = existing.map(Commitment::quantity).orElse(0);
        int needed = quantity - current;
        if (needed > box.free()) {
            throw notEnough(box.free() + current);
        }
        Instant now = clock.instant();
        Commitment saved;
        if (existing.isEmpty()) {
            box.commit(quantity);
            inventories.saveAndFlush(box);
            saved = new Commitment(UUID.randomUUID(), productId, boxVariantId, quantity, 0, state.unpublishCount(), now, actor);
            jdbc.update("INSERT INTO box_commitment (id, product_id, box_variant_id, quantity, unpublish_count, committed_at, committed_by) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    saved.id(), productId, boxVariantId, quantity, saved.unpublishCount(), java.sql.Timestamp.from(now), actor);
            change(saved, "ALLOCATE", quantity, quantity, null, null, now, actor);
        } else if (needed == 0) {
            saved = existing.get();
        } else {
            if (needed > 0) {
                box.commit(needed);
            } else {
                box.uncommit(-needed);
            }
            inventories.saveAndFlush(box);
            Commitment was = existing.get();
            saved = new Commitment(was.id(), productId, boxVariantId, quantity, was.held(), was.unpublishCount(), was.committedAt(), was.committedBy());
            jdbc.update("UPDATE box_commitment SET quantity = ? WHERE id = ?", quantity, was.id());
            change(saved, "REPLACE", needed, quantity, null, null, now, actor);
        }
        verifyBox(boxVariantId);
        log.info("Boxes set aside for product {}: {} of {} (was {})", productId, quantity, boxVariantId, current);
        return new Saved(saved, counts(box));
    }

    private static CommitmentProblem notEnough(int available) {
        return new CommitmentProblem(HttpStatus.CONFLICT, "insufficient-free-packaging",
                "only " + available + " of this box can be set aside for the product: the rest are set aside for other products or held by orders")
                .with("available", available);
    }

    // ---------------------------------------------------------------- letting go

    /**
     * Lets the product's boxes go back to free (nothing leaves the building) and closes its record into history.
     *
     * @param eventId the product event that caused it, when one did
     * @return what was let go, or empty if the product had nothing set aside
     */
    @Transactional
    public Optional<Commitment> release(UUID productId, UUID eventId, String actor) {
        Optional<Commitment> existing = commitmentOf(productId);
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        // The box first, then the record again under its lock: of two ways of letting the same boxes go, only the first finds them.
        Inventory box = inventories.findByIdForUpdate(existing.get().boxVariantId()).orElse(null);
        Optional<Commitment> current = commitmentOf(productId);
        if (current.isEmpty()) {
            return Optional.empty();
        }
        releaseLocked(current.get(), box, eventId, actor);
        return current;
    }

    /** Lets a record's boxes go. The box's row is already locked by the caller (null if the box has no stock row, which a record never outlives). */
    private void releaseLocked(Commitment commitment, Inventory box, UUID eventId, String actor) {
        // Only what no order holds goes back to free; what orders hold stays in the box's reserved count, and each hold's line, when it is
        // let go or sold, finds no promise to return to (its boxes then go to free, or leave with the sale).
        int unheld = commitment.quantity() - commitment.held();
        if (box != null && unheld > 0) {
            box.uncommit(unheld);
            inventories.saveAndFlush(box);
        }
        jdbc.update("DELETE FROM box_commitment WHERE id = ?", commitment.id());
        change(commitment, "RELEASE", -commitment.quantity(), 0, null, eventId, clock.instant(), actor);
        verifyBox(commitment.boxVariantId());
        log.info("Boxes of product {} let go: {} of {}", commitment.productId(), commitment.quantity(), commitment.boxVariantId());
    }

    // ---------------------------------------------------------------- what catalog says

    /**
     * Keeps what catalog's newest snapshot says of a product, and lets its boxes go if that state says they are not needed (see the class
     * comment). Called by the product-event consumer inside the transaction that records the event as applied.
     */
    @Transactional
    public void applyProduct(ProductSnapshot snapshot, UUID eventId, String actor) {
        jdbc.update("""
                INSERT INTO product_state (product_id, kind, status, default_packaging_variant_id, unpublish_count, deleted, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, now())
                ON CONFLICT (product_id) DO UPDATE
                   SET kind = EXCLUDED.kind,
                       status = EXCLUDED.status,
                       default_packaging_variant_id = EXCLUDED.default_packaging_variant_id,
                       unpublish_count = GREATEST(product_state.unpublish_count, EXCLUDED.unpublish_count),
                       deleted = product_state.deleted OR EXCLUDED.deleted,
                       updated_at = now()""",
                snapshot.productId(), snapshot.kind(), snapshot.status(), snapshot.defaultPackagingVariantId(), snapshot.unpublishCount(), snapshot.deleted());
        ProductSnapshot state = lockState(snapshot.productId()).orElseThrow();
        Optional<Commitment> commitment = commitmentOf(state.productId());
        if (commitment.isPresent() && isDead(commitment.get(), state)) {
            Inventory box = inventories.findByIdForUpdate(commitment.get().boxVariantId()).orElse(null);
            Optional<Commitment> current = commitmentOf(state.productId());
            current.ifPresent(c -> releaseLocked(c, box, eventId, actor));
        }
    }

    /** Whether a product's boxes are no longer wanted, by the state of the newest snapshot. */
    static boolean isDead(Commitment commitment, ProductSnapshot state) {
        return state.deleted()
                || !"SELLABLE".equals(state.kind())
                || state.unpublishCount() > commitment.unpublishCount()
                || !Objects.equals(state.defaultPackagingVariantId(), commitment.boxVariantId());
    }

    // ---------------------------------------------------------------- the sale and the write-off

    /**
     * A write-off cut {@code cut} boxes out of what was set aside: newest records first, each cut recorded, a record cut to nothing
     * closed. Called by the write-off, inside its transaction, with the box's row already changed.
     *
     * @return the products that lost boxes, newest first
     */
    @Transactional
    public List<CutCommitment> cutForWriteOff(UUID boxVariantId, int cut, UUID actionId, String actor, Instant when) {
        List<Commitment> records = jdbc.query("""
                SELECT id, product_id, box_variant_id, quantity, held, unpublish_count, committed_at, committed_by
                  FROM box_commitment WHERE box_variant_id = ? ORDER BY committed_at DESC, id DESC FOR UPDATE""", this::commitment, boxVariantId);
        List<CutCommitment> cuts = new ArrayList<>();
        int remaining = cut;
        for (Commitment record : records) {
            if (remaining == 0) {
                break;
            }
            int take = Math.min(remaining, record.quantity() - record.held());
            if (take <= 0) {
                continue;
            }
            int after = record.quantity() - take;
            if (after == 0) {
                jdbc.update("DELETE FROM box_commitment WHERE id = ?", record.id());
            } else {
                jdbc.update("UPDATE box_commitment SET quantity = ? WHERE id = ?", after, record.id());
            }
            change(record, "WRITE_OFF_CUT", -take, after, actionId, null, when, actor);
            cuts.add(new CutCommitment(record.productId(), boxVariantId, take));
            remaining -= take;
        }
        if (remaining > 0) {
            throw new IllegalStateException("a write-off cut " + cut + " boxes out of what was set aside on " + boxVariantId
                    + " but the records hold only " + (cut - remaining) + ": the count and the records disagree");
        }
        return cuts;
    }

    /** The cuts a write-off made, as recorded, for answering a repeat of it. */
    @Transactional(readOnly = true)
    public List<CutCommitment> cutsOf(UUID actionId) {
        return jdbc.query("SELECT product_id, box_variant_id, -qty_delta AS cut FROM box_commitment_change WHERE action_id = ? AND kind = 'WRITE_OFF_CUT' ORDER BY id",
                (rs, n) -> new CutCommitment(rs.getObject("product_id", UUID.class), rs.getObject("box_variant_id", UUID.class), rs.getInt("cut")), actionId);
    }

    /**
     * A sale of boxes that were set aside for the product and never reserved lowers its record by what was sold; the record closes if that
     * was the last of it. Called by the sale, inside its transaction.
     *
     * @throws InsufficientStockException if the product has fewer than {@code qty} set aside on this box
     */
    @Transactional
    public void consumeFromCommitment(UUID productId, UUID boxVariantId, int qty, UUID actionId, String actor, Instant when) {
        Commitment record = jdbc.query("""
                SELECT id, product_id, box_variant_id, quantity, held, unpublish_count, committed_at, committed_by
                  FROM box_commitment WHERE product_id = ? FOR UPDATE""", this::commitment, productId).stream().findFirst().orElse(null);
        if (record == null || !record.boxVariantId().equals(boxVariantId)) {
            throw InsufficientStockException.cannot("sell", qty, 0, "set aside for product " + productId);
        }
        if (qty > record.quantity() - record.held()) {
            throw InsufficientStockException.cannot("sell", qty, record.quantity() - record.held(), "set aside for product " + productId
                    + " and not held by an order");
        }
        int after = record.quantity() - qty;
        if (after == 0) {
            jdbc.update("DELETE FROM box_commitment WHERE id = ?", record.id());
        } else {
            jdbc.update("UPDATE box_commitment SET quantity = ? WHERE id = ?", after, record.id());
        }
        change(record, "CONSUME", -qty, after, actionId, null, when, actor);
    }

    // ---------------------------------------------------------------- orders holding set-aside boxes (Phase 7)

    /** What a hold drew from a product's promise: the promise it came from (null if none) and how many boxes. */
    public record Draw(UUID commitmentId, int fromCommitted) {
    }

    /**
     * An order's box line takes boxes from the product's promise first: up to what is promised and not yet held by another order. The
     * promise stays whole ({@code quantity}); {@code held} says how much of it orders hold right now. The caller moves the same number
     * from {@code committed} to {@code reserved} on the box's row, which it has locked.
     *
     * @return the promise the boxes came from and how many (none if the product has no promise on this box)
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public Draw drawForReservation(UUID productId, UUID boxVariantId, int qty) {
        Commitment record = lockedCommitmentOf(productId).orElse(null);
        if (record == null || !record.boxVariantId().equals(boxVariantId)) {
            return new Draw(null, 0);
        }
        int taken = Math.min(qty, record.quantity() - record.held());
        if (taken <= 0) {
            return new Draw(null, 0);
        }
        jdbc.update("UPDATE box_commitment SET held = held + ? WHERE id = ?", taken, record.id());
        return new Draw(record.id(), taken);
    }

    /**
     * A hold that came from a promise lets go: the boxes return to the promise if it is still there.
     *
     * @return how many went back into the promise; 0 if the promise has been let go since (the caller then frees the boxes)
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public int restoreHeld(UUID commitmentId, int qty) {
        Commitment record = lockedCommitmentById(commitmentId).orElse(null);
        if (record == null) {
            return 0;
        }
        int back = Math.min(qty, record.held());
        if (back > 0) {
            jdbc.update("UPDATE box_commitment SET held = held - ? WHERE id = ?", back, record.id());
        }
        return back;
    }

    /**
     * A hold that came from a promise is sold: the promise and the hold both fall by what was sold; a promise used up is closed.
     *
     * @return how many came off the promise; 0 if it has been let go since (the boxes still leave: the caller handles the count)
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public int consumeHeld(UUID commitmentId, int qty, UUID actionId, String actor, Instant when) {
        Commitment record = lockedCommitmentById(commitmentId).orElse(null);
        if (record == null) {
            return 0;
        }
        int off = Math.min(qty, record.held());
        if (off <= 0) {
            return 0;
        }
        int after = record.quantity() - off;
        if (after == 0) {
            jdbc.update("DELETE FROM box_commitment WHERE id = ?", record.id());
        } else {
            jdbc.update("UPDATE box_commitment SET quantity = ?, held = held - ? WHERE id = ?", after, off, record.id());
        }
        change(record, "CONSUME", -off, after, actionId, null, when, actor);
        return off;
    }

    /** How many boxes a sale line took out of a product's set-aside (the rest of the line came from free boxes). */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public int soldFromSetAside(UUID boxLineId) {
        return jdbc.queryForObject("SELECT coalesce(sum(-qty_delta), 0) FROM box_commitment_change WHERE action_id = ? AND kind = 'CONSUME'",
                Integer.class, boxLineId);
    }

    /**
     * Boxes of a cancelled order go back into the set-aside they were sold out of (Phase 12): the promise grows by what came back. The
     * caller has put the boxes back on the shelf and sets them aside on the stock row for what this answers.
     *
     * @return how many went back into the promise; 0 if the product has no promise on this box any more (the boxes are then simply free)
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public int returnSold(UUID productId, UUID boxVariantId, int qty, UUID actionId, String actor, Instant when) {
        Commitment record = lockedCommitmentOf(productId).filter(c -> c.boxVariantId().equals(boxVariantId)).orElse(null);
        if (record == null || qty <= 0) {
            return 0;
        }
        int after = record.quantity() + qty;
        jdbc.update("UPDATE box_commitment SET quantity = ? WHERE id = ?", after, record.id());
        change(record, "RETURN", qty, after, actionId, null, when, actor);
        return qty;
    }

    // ---------------------------------------------------------------- reads and checks

    @Transactional(readOnly = true)
    public List<Commitment> commitments(UUID productId, UUID boxVariantId) {
        StringBuilder sql = new StringBuilder("SELECT id, product_id, box_variant_id, quantity, held, unpublish_count, committed_at, committed_by FROM box_commitment WHERE true");
        List<Object> args = new ArrayList<>();
        if (productId != null) {
            sql.append(" AND product_id = ?");
            args.add(productId);
        }
        if (boxVariantId != null) {
            sql.append(" AND box_variant_id = ?");
            args.add(boxVariantId);
        }
        sql.append(" ORDER BY committed_at, id");
        return jdbc.query(sql.toString(), this::commitment, args.toArray());
    }

    @Transactional(readOnly = true)
    public List<Change> history(UUID productId) {
        return jdbc.query("""
                SELECT id, commitment_id, product_id, box_variant_id, kind, qty_delta, quantity_after, action_id, event_id, occurred_at, actor
                  FROM box_commitment_change WHERE product_id = ? ORDER BY id""",
                (rs, n) -> new Change(rs.getLong("id"), rs.getObject("commitment_id", UUID.class), rs.getObject("product_id", UUID.class),
                        rs.getObject("box_variant_id", UUID.class), rs.getString("kind"), rs.getInt("qty_delta"), rs.getInt("quantity_after"),
                        rs.getObject("action_id", UUID.class), rs.getObject("event_id", UUID.class), rs.getTimestamp("occurred_at").toInstant(),
                        rs.getString("actor")), productId);
    }

    /** The box's counts, for a reply. */
    public static BoxCounts counts(Inventory box) {
        return new BoxCounts(box.onHand(), box.committed(), box.reserved(), box.free());
    }

    /** What is set aside on the box equals the sum of the products' records, or the change is rolled back. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void verifyBox(UUID boxVariantId) {
        Integer committed = jdbc.query("SELECT committed FROM inventory WHERE variant_id = ?", rs -> rs.next() ? rs.getInt(1) : null, boxVariantId);
        int sum = jdbc.queryForObject("SELECT coalesce(sum(quantity - held), 0) FROM box_commitment WHERE box_variant_id = ?", Integer.class, boxVariantId);
        if (committed != null && committed != sum) {
            throw new IllegalStateException("the boxes set aside on " + boxVariantId + " are " + committed + " but the records add up to " + sum
                    + " (promised, less what orders hold)");
        }
    }

    // ---------------------------------------------------------------- plumbing

    /** What inventory knows of a product (read, not locked), or empty if no snapshot of it has arrived. */
    @Transactional(readOnly = true)
    public Optional<ProductSnapshot> stateOf(UUID productId) {
        return jdbc.query("SELECT product_id, kind, status, default_packaging_variant_id, unpublish_count, deleted FROM product_state WHERE product_id = ?",
                (rs, n) -> new ProductSnapshot(rs.getObject("product_id", UUID.class), rs.getString("kind"), rs.getString("status"),
                        rs.getObject("default_packaging_variant_id", UUID.class), rs.getInt("unpublish_count"), rs.getBoolean("deleted")), productId)
                .stream().findFirst();
    }

    /** A product's promise, taken WITH its row lock (the box's row is already locked by the caller: box first, then the record). */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public Optional<Commitment> lockPromise(UUID productId) {
        return lockedCommitmentOf(productId);
    }

    private Optional<ProductSnapshot> lockState(UUID productId) {
        return jdbc.query("SELECT product_id, kind, status, default_packaging_variant_id, unpublish_count, deleted FROM product_state WHERE product_id = ? FOR UPDATE",
                (rs, n) -> new ProductSnapshot(rs.getObject("product_id", UUID.class), rs.getString("kind"), rs.getString("status"),
                        rs.getObject("default_packaging_variant_id", UUID.class), rs.getInt("unpublish_count"), rs.getBoolean("deleted")), productId)
                .stream().findFirst();
    }

    private Optional<Commitment> lockedCommitmentOf(UUID productId) {
        return jdbc.query("SELECT id, product_id, box_variant_id, quantity, held, unpublish_count, committed_at, committed_by FROM box_commitment WHERE product_id = ? FOR UPDATE",
                this::commitment, productId).stream().findFirst();
    }

    private Optional<Commitment> lockedCommitmentById(UUID commitmentId) {
        return jdbc.query("SELECT id, product_id, box_variant_id, quantity, held, unpublish_count, committed_at, committed_by FROM box_commitment WHERE id = ? FOR UPDATE",
                this::commitment, commitmentId).stream().findFirst();
    }

    private Optional<Commitment> commitmentOf(UUID productId) {
        return jdbc.query("SELECT id, product_id, box_variant_id, quantity, held, unpublish_count, committed_at, committed_by FROM box_commitment WHERE product_id = ?",
                this::commitment, productId).stream().findFirst();
    }

    private Commitment commitment(ResultSet rs, int row) throws SQLException {
        return new Commitment(rs.getObject("id", UUID.class), rs.getObject("product_id", UUID.class), rs.getObject("box_variant_id", UUID.class),
                rs.getInt("quantity"), rs.getInt("held"), rs.getInt("unpublish_count"), rs.getTimestamp("committed_at").toInstant(), rs.getString("committed_by"));
    }

    private void change(Commitment commitment, String kind, int qtyDelta, int quantityAfter, UUID actionId, UUID eventId, Instant occurredAt, String actor) {
        jdbc.update("""
                INSERT INTO box_commitment_change (commitment_id, product_id, box_variant_id, kind, qty_delta, quantity_after, action_id, event_id, occurred_at, actor)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                commitment.id(), commitment.productId(), commitment.boxVariantId(), kind, qtyDelta, quantityAfter, actionId, eventId,
                java.sql.Timestamp.from(occurredAt), actor);
    }
}
