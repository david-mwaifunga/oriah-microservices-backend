package com.oriah.inventory.service;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.oriah.events.EventDraft;
import com.oriah.events.OutboxWriter;
import com.oriah.inventory.events.ReservationEvents;
import com.oriah.inventory.exception.CommitmentProblem;
import com.oriah.inventory.ledger.MovementTimes;
import com.oriah.inventory.model.Inventory;
import com.oriah.inventory.repository.InventoryRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * An order's hold on its pieces and boxes (Phase 7): a header for ONE order with a line per piece and per box, so a hold has an owner and a
 * clock and can be let go exactly once. Until now a reservation was a bare integer on the stock row, which could not say whose it was.
 *
 * <h2>What a hold is</h2>
 *
 * Reserving takes every line or none: a shortage on the last line leaves nothing held, and every short line is named so the caller can say
 * what is short. A piece's line takes from the variant's free stock. A BOX line (one box for each piece) takes from the product's set-aside
 * first and from free boxes after; the line remembers where its units came from, so releasing puts them back where they came from, once.
 * An order holds at most one reservation at a time, and the caller's reservation id makes a repeat of the same request hold nothing more.
 *
 * <h2>How a hold ends, exactly once</h2>
 *
 * <ul>
 *   <li><b>consumed</b>: the sale. Goods and boxes drain their lots in one transaction, each under the order's own sale-line id (so a second
 *       reservation of the same order can never sell a line twice), dated to the day the money arrived;</li>
 *   <li><b>released</b>: a cancel. Releasing anything that is not held is a no-op, never an error (an event may arrive twice, or late);</li>
 *   <li><b>expired</b>: the sweep. A hold is judged from its own {@code expires_at} under its row lock with one clock, so a sale arriving a
 *       moment after the time is refused even if the sweep has not run yet.</li>
 * </ul>
 *
 * <h2>Locks, always in this order</h2>
 *
 * the header row, then the stock rows in variant-id order, then the product's promise records; and whatever is written to the outbox last.
 * Two holds touching the same ring and box in different orders therefore never wait on each other.
 *
 * <p>After every change what each stock row says is reserved is checked against what the held lines add up to, and the boxes' set-aside
 * against the promises; a mismatch rolls the change back.
 */
@Service
@Slf4j
public class ReservationService {

    public enum Status { HELD, CONSUMED, RELEASED, EXPIRED }

    /**
     * One thing the order is buying. {@code saleLineId} is the order's own id for the piece (it becomes the stock action's id when the
     * order is paid); {@code boxLineId} is its id for the box that goes with it. The box is the product's default box, unless
     * {@code boxVariantId} names another (a person choosing a box on a recorded order).
     */
    public record Line(UUID saleLineId, UUID variantId, UUID productId, int quantity, UUID boxLineId, UUID boxVariantId) {
    }

    /** A line of a reservation as held: where its units came from. {@code kind} is GOODS or BOX. */
    public record LineView(UUID saleLineId, UUID variantId, String kind, UUID productId, int quantity, int fromCommitted, int fromFree) {
    }

    /** A reservation as it stands. */
    public record View(UUID reservationId, UUID orderId, Status status, Instant heldAt, Instant expiresAt, Instant endedAt, List<LineView> lines) {
    }

    private record Header(UUID id, UUID orderId, Status status, Instant heldAt, Instant expiresAt, Instant endedAt) {
    }

    /** A line as stored. */
    private record StoredLine(UUID id, UUID saleLineId, UUID variantId, String kind, UUID productId, int quantity, int fromCommitted, int fromFree,
                              UUID commitmentId) {
    }

    /** A line as asked for, with its box worked out. */
    private record Planned(UUID saleLineId, UUID variantId, String kind, UUID productId, int quantity) {
    }

    private final JdbcTemplate jdbc;
    private final InventoryRepository inventories;
    private final BoxCommitmentService commitments;
    private final StockService stock;
    private final Clock clock;
    private final OutboxWriter outbox;
    private final TransactionTemplate tx;
    private final Duration defaultHold;
    private final Duration maxHold;

    public ReservationService(JdbcTemplate jdbc, InventoryRepository inventories, BoxCommitmentService commitments, StockService stock, Clock clock,
                              OutboxWriter outbox, PlatformTransactionManager transactionManager,
                              @Value("${oriah.sales.hold:PT10M}") Duration defaultHold,
                              @Value("${oriah.sales.payment-submitted-hold:PT24H}") Duration maxHold) {
        this.jdbc = jdbc;
        this.inventories = inventories;
        this.commitments = commitments;
        this.stock = stock;
        this.clock = clock;
        this.outbox = outbox;
        this.tx = new TransactionTemplate(transactionManager);
        this.defaultHold = defaultHold;
        this.maxHold = maxHold;
    }

    // ---------------------------------------------------------------- reserving

    /**
     * Holds the order's pieces and their boxes, all or nothing.
     *
     * @param hold how long to hold; null means the default (ten minutes); never longer than the longest a hold may be stretched to
     * @return the reservation; asking again with the same reservation id and order answers the same one and holds nothing more
     * @throws CommitmentProblem 409: {@code insufficient-stock} (with {@code shortages}), {@code order-already-holds}, {@code reservation-id-used},
     *                           {@code no-default-box}, {@code product-state-not-received}, {@code sale-line-already-sold}
     */
    @Transactional
    public View reserve(UUID reservationId, UUID orderId, List<Line> lines, Duration hold, String actor) {
        require(reservationId != null && orderId != null, "a reservation needs its own id and its order's id");
        require(lines != null && !lines.isEmpty(), "a reservation needs at least one line");
        Set<UUID> saleLineIds = new HashSet<>();
        for (Line line : lines) {
            require(line != null && line.saleLineId() != null && line.variantId() != null && line.productId() != null && line.boxLineId() != null,
                    "every line needs a sale-line id, a variant, a product and a box-line id");
            require(line.quantity() > 0, "every quantity must be above zero");
            require(saleLineIds.add(line.saleLineId()) && saleLineIds.add(line.boxLineId()), "sale-line ids must be different on every line, boxes included");
        }
        Optional<Header> existing = header(reservationId, false);
        if (existing.isPresent()) {
            if (!existing.get().orderId().equals(orderId)) {
                throw new CommitmentProblem(HttpStatus.CONFLICT, "reservation-id-used", "this reservation id was used for another order");
            }
            return view(existing.get());
        }
        Instant now = now();
        Duration length = hold == null ? defaultHold : hold;
        require(!length.isNegative() && !length.isZero() && length.compareTo(maxHold) <= 0, "a hold is above zero and at most " + maxHold);

        List<Planned> planned = expand(lines);
        for (Planned p : planned) {
            Integer sold = jdbc.queryForObject("SELECT count(*) FROM stock_action WHERE id = ?", Integer.class, p.saleLineId());
            if (sold != null && sold > 0) {
                throw new CommitmentProblem(HttpStatus.CONFLICT, "sale-line-already-sold", "sale line " + p.saleLineId() + " has already been sold")
                        .with("saleLineId", p.saleLineId());
            }
        }
        try {
            jdbc.update("INSERT INTO reservation (id, order_id, status, held_at, expires_at, created_by) VALUES (?, ?, 'HELD', ?, ?, ?)",
                    reservationId, orderId, Timestamp.from(now), Timestamp.from(now.plus(length)), actor);
        } catch (DuplicateKeyException e) {
            if (String.valueOf(e.getMessage()).contains("reservation_one_held_per_order")) {
                throw new CommitmentProblem(HttpStatus.CONFLICT, "order-already-holds", "this order already holds a reservation: release it or let it lapse first")
                        .with("orderId", orderId);
            }
            throw new CommitmentProblem(HttpStatus.CONFLICT, "reservation-in-progress", "the same reservation is being made right now: ask again");
        }

        Map<UUID, Inventory> rows = lock(planned.stream().map(Planned::variantId).toList());
        Map<UUID, Integer> budget = new TreeMap<>();
        List<Map<String, Object>> shortages = shortages(planned, rows, budget);
        if (!shortages.isEmpty()) {
            throw new CommitmentProblem(HttpStatus.CONFLICT, "insufficient-stock", "not everything on the order can be held: " + shortages.size() + " line(s) short")
                    .with("shortages", shortages);
        }

        for (Planned p : planned) {
            Inventory row = rows.get(p.variantId());
            int fromCommitted = 0;
            UUID commitmentId = null;
            if (p.kind().equals("BOX")) {
                BoxCommitmentService.Draw draw = commitments.drawForReservation(p.productId(), p.variantId(), p.quantity());
                fromCommitted = draw.fromCommitted();
                commitmentId = draw.commitmentId();
                if (fromCommitted > 0) {
                    row.reserveFromCommitted(fromCommitted);
                }
            }
            int fromFree = p.quantity() - fromCommitted;
            if (fromFree > 0) {
                row.reserve(fromFree);
            }
            jdbc.update("""
                    INSERT INTO reservation_line (id, reservation_id, sale_line_id, variant_id, kind, product_id, quantity, from_committed, from_free, commitment_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                    UUID.randomUUID(), reservationId, p.saleLineId(), p.variantId(), p.kind(), p.kind().equals("BOX") ? p.productId() : null, p.quantity(),
                    fromCommitted, fromFree, commitmentId);
        }
        rows.values().forEach(inventories::saveAndFlush);
        verify(rows.keySet());
        log.info("Held reservation {} for order {}: {} line(s), until {}", reservationId, orderId, planned.size(), now.plus(length));
        return view(header(reservationId, false).orElseThrow());
    }

    /** Every line asked for becomes a piece line and a box line (one box per piece), in the order locks are taken: variant id first. */
    private List<Planned> expand(List<Line> lines) {
        List<Planned> planned = new ArrayList<>();
        for (Line line : lines) {
            planned.add(new Planned(line.saleLineId(), line.variantId(), "GOODS", line.productId(), line.quantity()));
            BoxCommitmentService.ProductSnapshot state = commitments.stateOf(line.productId()).orElseThrow(() -> new CommitmentProblem(HttpStatus.CONFLICT,
                    "product-state-not-received", "inventory has not yet heard of product " + line.productId() + " from the catalogue: any change to it there sends it")
                    .with("productId", line.productId()));
            if (state.deleted() || !"SELLABLE".equals(state.kind())) {
                throw new CommitmentProblem(HttpStatus.CONFLICT, "not-a-sellable-product", "product " + line.productId() + " is not for sale")
                        .with("productId", line.productId());
            }
            UUID box = line.boxVariantId() != null ? line.boxVariantId() : state.defaultPackagingVariantId();
            if (box == null) {
                throw new CommitmentProblem(HttpStatus.CONFLICT, "no-default-box", "product " + line.productId() + " has no default box: choose one in the catalogue first")
                        .with("productId", line.productId());
            }
            planned.add(new Planned(line.boxLineId(), box, "BOX", line.productId(), line.quantity()));
        }
        planned.sort(Comparator.comparing(Planned::variantId).thenComparing(p -> p.kind()).thenComparing(Planned::productId).thenComparing(Planned::saleLineId));
        return planned;
    }

    /** Takes every stock row the lines touch, in variant-id order, WITH its lock. A variant with no stock row is simply absent. */
    private Map<UUID, Inventory> lock(Collection<UUID> variantIds) {
        Map<UUID, Inventory> rows = new LinkedHashMap<>();
        for (Inventory row : inventories.findAllForUpdate(new TreeSet<>(variantIds))) {
            rows.put(row.variantId(), row);
        }
        return rows;
    }

    /**
     * Every line that cannot be held, worked out BEFORE anything is changed, so the refusal names them all. A box line counts what its
     * product has promised and not already held first, then what is free; the promise is read under its lock, product by product.
     */
    private List<Map<String, Object>> shortages(List<Planned> planned, Map<UUID, Inventory> rows, Map<UUID, Integer> budget) {
        Map<UUID, Integer> requested = new TreeMap<>();
        Map<UUID, Integer> supplied = new TreeMap<>();
        Map<UUID, String> kinds = new TreeMap<>();
        Map<UUID, Integer> freeNeeded = new TreeMap<>();
        Map<UUID, Integer> unheld = new TreeMap<>();
        for (Planned p : planned) {
            requested.merge(p.variantId(), p.quantity(), Integer::sum);
            kinds.put(p.variantId(), p.kind());
            int fromPromise = 0;
            if (p.kind().equals("BOX")) {
                if (!unheld.containsKey(p.productId())) {
                    Optional<BoxCommitmentService.Commitment> promise = commitments.lockPromise(p.productId());
                    unheld.put(p.productId(), promise.filter(c -> c.boxVariantId().equals(p.variantId())).map(c -> c.quantity() - c.held()).orElse(0));
                }
                fromPromise = Math.min(p.quantity(), unheld.get(p.productId()));
                unheld.merge(p.productId(), -fromPromise, Integer::sum);
                supplied.merge(p.variantId(), fromPromise, Integer::sum);
            }
            freeNeeded.merge(p.variantId(), p.quantity() - fromPromise, Integer::sum);
        }
        List<Map<String, Object>> short_ = new ArrayList<>();
        for (Map.Entry<UUID, Integer> need : freeNeeded.entrySet()) {
            Inventory row = rows.get(need.getKey());
            int free = row == null ? 0 : row.free();
            if (row != null && kinds.get(need.getKey()).equals("BOX") && !"PACKAGING".equals(row.kind())) {
                throw new CommitmentProblem(HttpStatus.CONFLICT, "not-packaging", "variant " + need.getKey() + " is not packaging and cannot be a box");
            }
            if (need.getValue() > free) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("variantId", need.getKey());
                m.put("kind", kinds.get(need.getKey()));
                m.put("requested", requested.get(need.getKey()));
                m.put("available", free + supplied.getOrDefault(need.getKey(), 0));
                short_.add(m);
            }
        }
        return short_;
    }

    // ---------------------------------------------------------------- consuming: the sale

    /**
     * The order has paid: every line is sold in this one transaction (pieces and boxes drain their oldest lots, each under the order's own
     * sale-line id), dated to {@code occurredAt}, the day the money arrived.
     *
     * <p>Consuming again answers the same result and sells nothing more.
     *
     * @throws CommitmentProblem 404 {@code reservation-not-found}; 409 {@code reservation-not-held} (released or expired),
     *                           {@code reservation-expired} (its time has passed, even if the sweep has not yet ended it)
     * @throws IllegalArgumentException the date is in the future
     */
    @Transactional
    public View consume(UUID reservationId, Instant occurredAt, String actor) {
        Header header = header(reservationId, true).orElseThrow(() -> notFound(reservationId));
        if (header.status() == Status.CONSUMED) {
            return view(header);
        }
        requireHeld(header);
        Instant now = now();
        Instant when = MovementTimes.resolve(occurredAt, now, null);
        List<StoredLine> lines = lines(reservationId);
        Map<UUID, Inventory> rows = lock(lines.stream().map(StoredLine::variantId).toList());
        for (StoredLine line : lines) {
            if (!rows.containsKey(line.variantId())) {
                throw new IllegalStateException("the stock row of variant " + line.variantId() + " is gone while reservation " + reservationId + " holds it");
            }
            stock.consume(line.variantId(), line.quantity(), line.saleLineId(), when, actor)
                    .orElseThrow(() -> new IllegalStateException("variant " + line.variantId() + " has no stock row"));
            if (line.commitmentId() != null && line.fromCommitted() > 0) {
                commitments.consumeHeld(line.commitmentId(), line.fromCommitted(), line.saleLineId(), actor, when);
            }
        }
        jdbc.update("UPDATE reservation SET status = 'CONSUMED', ended_at = ? WHERE id = ?", Timestamp.from(now), reservationId);
        verify(rows.keySet());
        for (StoredLine line : lines) {
            if (line.kind().equals("BOX")) {
                commitments.verifyBox(line.variantId());
            }
        }
        // Announced last (the outbox takes a lock that queues appends), in this transaction: a sale that rolls back announces nothing.
        List<ReservationEvents.Consumed.Line> sold = lines.stream()
                .map(l -> new ReservationEvents.Consumed.Line(l.saleLineId(), l.variantId(), l.kind(), l.productId(), l.quantity(), costOf(l.saleLineId())))
                .toList();
        outbox.append(new EventDraft(ReservationEvents.AGGREGATE, reservationId.toString(), ReservationEvents.CONSUMED, ReservationEvents.VERSION,
                ReservationEvents.TOPIC, when, actor, new ReservationEvents.Consumed(reservationId, header.orderId(), "ZMW", sold)));
        log.info("Reservation {} consumed (dated {}): {} line(s) sold", reservationId, when, lines.size());
        return view(header(reservationId, false).orElseThrow());
    }

    // ---------------------------------------------------------------- releasing

    /**
     * The hold is let go: pieces and boxes go back where they came from, and the reservation ends. Releasing a reservation that is not held
     * (sold, released, expired) changes nothing and answers how it stands.
     *
     * @throws CommitmentProblem 404 {@code reservation-not-found}
     */
    @Transactional
    public View release(UUID reservationId, String actor) {
        Header header = header(reservationId, true).orElseThrow(() -> notFound(reservationId));
        if (header.status() != Status.HELD) {
            return view(header);
        }
        end(header, Status.RELEASED, now());
        log.info("Reservation {} released", reservationId);
        return view(header(reservationId, false).orElseThrow());
    }

    /**
     * Stretches a hold to {@code until}, never beyond {@code held_at + 24 h}, and never shorter than it is.
     *
     * @throws CommitmentProblem 404; 409 {@code reservation-not-held}, {@code reservation-expired}
     */
    @Transactional
    public View extend(UUID reservationId, Instant until, String actor) {
        require(until != null, "extend needs the time to hold until");
        Header header = header(reservationId, true).orElseThrow(() -> notFound(reservationId));
        requireHeld(header);
        Instant target = until.isAfter(header.heldAt().plus(maxHold)) ? header.heldAt().plus(maxHold) : until;
        if (target.isAfter(header.expiresAt())) {
            jdbc.update("UPDATE reservation SET expires_at = ? WHERE id = ?", Timestamp.from(target.truncatedTo(ChronoUnit.MICROS)), reservationId);
        }
        return view(header(reservationId, false).orElseThrow());
    }

    @Transactional(readOnly = true)
    public View get(UUID reservationId) {
        return view(header(reservationId, false).orElseThrow(() -> notFound(reservationId)));
    }

    // ---------------------------------------------------------------- expiring

    /**
     * Ends every HELD reservation whose time has passed (at most {@code limit}), each in a transaction of its own, so one that is busy or
     * broken does not hold the others up.
     *
     * @return how many were ended
     */
    public int expireDue(int limit) {
        List<UUID> due = jdbc.queryForList("SELECT id FROM reservation WHERE status = 'HELD' AND expires_at <= ? ORDER BY expires_at, id LIMIT ?",
                UUID.class, Timestamp.from(now()), limit);
        int ended = 0;
        for (UUID id : due) {
            try {
                Boolean done = tx.execute(status -> expireOne(id));
                if (Boolean.TRUE.equals(done)) {
                    ended++;
                }
            } catch (RuntimeException e) {
                log.error("Reservation {} could not be expired; the next sweep tries again", id, e);
            }
        }
        return ended;
    }

    /** Expires one reservation if it is still held and its time has passed, under its lock; a busy one is skipped. */
    private boolean expireOne(UUID id) {
        Header header = jdbc.query("""
                SELECT id, order_id, status, held_at, expires_at, ended_at FROM reservation
                 WHERE id = ? FOR UPDATE SKIP LOCKED""", this::header, id).stream().findFirst().orElse(null);
        Instant now = now();
        if (header == null || header.status() != Status.HELD || header.expiresAt().isAfter(now)) {
            return false;
        }
        end(header, Status.EXPIRED, now);
        outbox.append(new EventDraft(ReservationEvents.AGGREGATE, id.toString(), ReservationEvents.EXPIRED, ReservationEvents.VERSION,
                ReservationEvents.TOPIC, now, "system:inventory",
                new ReservationEvents.Expired(id, header.orderId(), header.heldAt(), header.expiresAt(), now)));
        log.info("Reservation {} expired (was due {})", id, header.expiresAt());
        return true;
    }

    // ---------------------------------------------------------------- the common ending

    /** Gives every held line back where it came from and ends the header. The header's lock is already held. */
    private void end(Header header, Status status, Instant now) {
        List<StoredLine> lines = lines(header.id());
        Map<UUID, Inventory> rows = lock(lines.stream().map(StoredLine::variantId).toList());
        for (StoredLine line : lines) {
            Inventory row = rows.get(line.variantId());
            if (row == null) {
                throw new IllegalStateException("the stock row of variant " + line.variantId() + " is gone while reservation " + header.id() + " holds it");
            }
            int back = 0;
            if (line.commitmentId() != null && line.fromCommitted() > 0) {
                back = commitments.restoreHeld(line.commitmentId(), line.fromCommitted());
            }
            if (back > 0) {
                row.releaseToCommitted(back);
            }
            if (line.quantity() - back > 0) {
                row.release(line.quantity() - back);
            }
        }
        jdbc.update("UPDATE reservation SET status = ?, ended_at = ? WHERE id = ?", status.name(), Timestamp.from(now), header.id());
        rows.values().forEach(inventories::saveAndFlush);
        verify(rows.keySet());
        for (StoredLine line : lines) {
            if (line.kind().equals("BOX")) {
                commitments.verifyBox(line.variantId());
            }
        }
    }

    // ---------------------------------------------------------------- checks and reads

    /** What each stock row says is reserved equals what its held lines add up to, or the change is rolled back. */
    private void verify(Collection<UUID> variantIds) {
        for (UUID variantId : variantIds) {
            Integer reserved = jdbc.query("SELECT reserved FROM inventory WHERE variant_id = ?", rs -> rs.next() ? rs.getInt(1) : null, variantId);
            Integer held = jdbc.queryForObject("""
                    SELECT coalesce(sum(l.quantity), 0) FROM reservation_line l JOIN reservation r ON r.id = l.reservation_id
                     WHERE r.status = 'HELD' AND l.variant_id = ?""", Integer.class, variantId);
            if (reserved != null && !reserved.equals(held)) {
                throw new IllegalStateException("variant " + variantId + " says " + reserved + " reserved but its held lines add up to " + held);
            }
        }
    }

    /** What the units a sale line drained cost: the live ledger rows of its action, at the cost each lot gave them. */
    private BigDecimal costOf(UUID saleLineId) {
        BigDecimal cost = jdbc.queryForObject("SELECT coalesce(sum(-qty_delta * unit_cost), 0) FROM live_movement WHERE action_id = ?", BigDecimal.class, saleLineId);
        return cost == null ? BigDecimal.ZERO.setScale(2) : cost.setScale(2);
    }

    private void requireHeld(Header header) {
        if (header.status() != Status.HELD) {
            throw new CommitmentProblem(HttpStatus.CONFLICT, "reservation-not-held", "reservation " + header.id() + " is " + header.status().name().toLowerCase()
                    + ", not held").with("status", header.status().name());
        }
        if (!now().isBefore(header.expiresAt())) {
            throw new CommitmentProblem(HttpStatus.CONFLICT, "reservation-expired", "reservation " + header.id() + " lapsed at " + header.expiresAt())
                    .with("expiresAt", header.expiresAt());
        }
    }

    private static CommitmentProblem notFound(UUID reservationId) {
        return new CommitmentProblem(HttpStatus.NOT_FOUND, "reservation-not-found", "there is no reservation " + reservationId);
    }

    private static void require(boolean ok, String message) {
        if (!ok) {
            throw new IllegalArgumentException(message);
        }
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private Optional<Header> header(UUID id, boolean lock) {
        return jdbc.query("SELECT id, order_id, status, held_at, expires_at, ended_at FROM reservation WHERE id = ?" + (lock ? " FOR UPDATE" : ""),
                this::header, id).stream().findFirst();
    }

    private Header header(ResultSet rs, int row) throws SQLException {
        Timestamp ended = rs.getTimestamp("ended_at");
        return new Header(rs.getObject("id", UUID.class), rs.getObject("order_id", UUID.class), Status.valueOf(rs.getString("status")),
                rs.getTimestamp("held_at").toInstant(), rs.getTimestamp("expires_at").toInstant(), ended == null ? null : ended.toInstant());
    }

    private List<StoredLine> lines(UUID reservationId) {
        return jdbc.query("""
                SELECT id, sale_line_id, variant_id, kind, product_id, quantity, from_committed, from_free, commitment_id
                  FROM reservation_line WHERE reservation_id = ? ORDER BY variant_id, kind, sale_line_id""",
                (rs, n) -> new StoredLine(rs.getObject("id", UUID.class), rs.getObject("sale_line_id", UUID.class), rs.getObject("variant_id", UUID.class),
                        rs.getString("kind"), rs.getObject("product_id", UUID.class), rs.getInt("quantity"), rs.getInt("from_committed"), rs.getInt("from_free"),
                        rs.getObject("commitment_id", UUID.class)), reservationId);
    }

    private View view(Header header) {
        List<LineView> lines = lines(header.id()).stream()
                .map(l -> new LineView(l.saleLineId(), l.variantId(), l.kind(), l.productId(), l.quantity(), l.fromCommitted(), l.fromFree()))
                .toList();
        return new View(header.id(), header.orderId(), header.status(), header.heldAt(), header.expiresAt(), header.endedAt(), lines);
    }
}
