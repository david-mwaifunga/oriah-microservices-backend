package com.oriah.order.service;

import com.oriah.observability.ResourceNotFoundException;
import com.oriah.order.client.InventoryClient;
import com.oriah.order.events.OrderEventLog;
import com.oriah.order.events.SaleCorrectedEvent;
import com.oriah.order.exception.SalesProblem;
import com.oriah.order.model.Channel;
import com.oriah.order.model.Order;
import com.oriah.order.model.OrderStatus;
import com.oriah.order.repository.OrderRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The database halves of a correction of a paid sale (Phase 12, plan 4.1 to 4.3), each one short local transaction. A correction is a CANCELLATION of a
 * paid order before it is dispatched, or a RETURN of pieces after it. {@link #beginCancellation} and {@code beginReturn} decide whether it may be asked
 * of inventory and write it down REQUESTED, with its lines and what is owed; {@link #finish} marks it DONE once inventory has put the stock back and
 * announces it; {@link #refuse} ends it when inventory proves it moved nothing. {@link CorrectionService} asks inventory between them, outside any
 * transaction.
 *
 * <h2>A paid row is never rewritten</h2>
 *
 * The correction and its lines are records of their own that point back at the sale; the order's lines, totals, receipt number and payment date stay
 * exactly as they were sold. The only things that change on the order are its status (a cancellation makes it CANCELLED; a return changes nothing) and,
 * for a cancelled order, the recipient and address it no longer needs.
 *
 * <h2>Locks, always in this order</h2>
 *
 * The order's row ({@link OrderLocks}), then the correction's row, then (assessment) the piece's row, then the outbox last. Everything else that changes
 * a paid order takes the order's lock first too and asks {@link CorrectionGuard}, so while a correction is REQUESTED nothing else happens to its order.
 */
@Service
@Slf4j
public class Corrections {

    public static final String CANCELLATION = "CANCELLATION";
    public static final String RETURN = "RETURN";
    public static final String REQUESTED = "REQUESTED";
    public static final String DONE = "DONE";
    public static final String REFUSED = "REFUSED";

    /** A correction as it is stored. */
    public record Correction(UUID id, UUID orderId, String kind, String state, String reasonCode, String note, String statusBefore, UUID confirmationId,
                             String receiptNumber, Instant saleArrivedAt, Instant occurredAt, String backdateReason, BigDecimal goodsAtList,
                             BigDecimal discounts, BigDecimal deliveryRefunded, BigDecimal waiverTakenBack, BigDecimal owedGross, String currency,
                             String feeBearer, String idempotencyKey, String requestHash, String recordedBy, Instant recordedAt, int attempts,
                             Instant nextAttemptAt, Instant completedAt, String refusedCode) {
        public boolean inFlight() {
            return REQUESTED.equals(state);
        }
    }

    /** The part of one sale line a correction undoes, copied from the sale; {@code title} is read from the sale line for the views. */
    public record Line(UUID saleLineId, UUID variantId, UUID productId, int quantity, BigDecimal unitPrice, BigDecimal discountPerPiece, UUID promotionId,
                       UUID boxLineId, UUID boxVariantId, String title) {
        CorrectionAmounts.Line amounts() {
            return new CorrectionAmounts.Line(saleLineId, quantity, unitPrice, discountPerPiece);
        }
    }

    /** One piece that came back, and what became of it. */
    public record Piece(UUID id, UUID correctionId, UUID saleLineId, int pieceNo, String state, String outcome, UUID markdownVariantId, String note,
                        String askedBy, Instant askedAt, Instant assessedAt, int attempts) {
    }

    /** What a begin found: a correction to ask inventory for ({@code created}), or one already recorded. */
    public record Begun(Correction correction, boolean created) {
    }

    /** One line of the sale as the database holds it: a discount that was never recorded is null here, never zero. */
    private record SoldLine(UUID id, UUID variantId, UUID productId, int quantity, BigDecimal unitPrice, BigDecimal discountPerPiece, UUID promotionId,
                            UUID boxLineId, UUID boxVariantId) {
    }

    /** The sale a correction points back at. */
    private record Sale(UUID confirmationId, Instant arrivedAt, String receiptNumber, List<SoldLine> lines) {
    }

    private static final RowMapper<Correction> CORRECTION = (rs, n) -> new Correction(rs.getObject("id", UUID.class), rs.getObject("order_id", UUID.class),
            rs.getString("kind"), rs.getString("state"), rs.getString("reason_code"), rs.getString("note"), rs.getString("status_before"),
            rs.getObject("confirmation_id", UUID.class), rs.getString("receipt_number"), rs.getTimestamp("sale_arrived_at").toInstant(),
            rs.getTimestamp("occurred_at").toInstant(), rs.getString("backdate_reason"), rs.getBigDecimal("goods_at_list"), rs.getBigDecimal("discounts"),
            rs.getBigDecimal("delivery_refunded"), rs.getBigDecimal("waiver_taken_back"), rs.getBigDecimal("owed_gross"), rs.getString("currency"),
            rs.getString("fee_bearer"), rs.getString("idempotency_key"), rs.getString("request_hash"), rs.getString("recorded_by"),
            rs.getTimestamp("recorded_at").toInstant(), rs.getInt("attempts"), instant(rs.getTimestamp("next_attempt_at")),
            instant(rs.getTimestamp("completed_at")), rs.getString("refused_code"));

    private static final RowMapper<Piece> PIECE = (rs, n) -> new Piece(rs.getObject("id", UUID.class), rs.getObject("correction_id", UUID.class),
            rs.getObject("sale_line_id", UUID.class), rs.getInt("piece_no"), rs.getString("state"), rs.getString("outcome"),
            rs.getObject("markdown_variant_id", UUID.class), rs.getString("note"), rs.getString("asked_by"), instant(rs.getTimestamp("asked_at")),
            instant(rs.getTimestamp("assessed_at")), rs.getInt("attempts"));

    private final JdbcTemplate jdbc;
    private final OrderLocks locks;
    private final OrderRepository repository;
    private final OrderEventLog events;
    private final RefundBearers bearers;
    private final BoxSwaps swaps;
    private final CorrectionGuard guard;
    private final java.time.Duration backdateWindow;
    private final java.time.Duration duplicateWindow;
    public Corrections(JdbcTemplate jdbc, OrderLocks locks, OrderRepository repository, OrderEventLog events, RefundBearers bearers, BoxSwaps swaps,
                       CorrectionGuard guard,
                       @org.springframework.beans.factory.annotation.Value("${oriah.sales.backdate-window:PT72H}") java.time.Duration backdateWindow,
                       @org.springframework.beans.factory.annotation.Value("${oriah.sales.return-duplicate-window:PT10M}") java.time.Duration duplicateWindow) {
        this.backdateWindow = backdateWindow;
        this.duplicateWindow = duplicateWindow;
        this.jdbc = jdbc;
        this.locks = locks;
        this.repository = repository;
        this.events = events;
        this.bearers = bearers;
        this.swaps = swaps;
        this.guard = guard;
    }

    // ------------------------------------------------------------------ a paid order cancelled before dispatch

    /**
     * Decides whether a paid order may be cancelled and writes the cancellation down, REQUESTED, with every line whole and what is owed (everything
     * paid). One transaction, under the order's lock.
     *
     * @param note  why, trimmed; required when the reason is OTHER
     * @param actor {@code user:<keycloak subject>} of the member of staff
     * @return the cancellation to ask inventory for, or the one that already stands for this order (the same reason asked again)
     * @throws ResourceNotFoundException 404, no such order
     * @throws SalesProblem 409 {@code already-cancelled} (it stands with another reason), {@code cancellation-not-allowed} (not paid, or already
     *                      dispatched: the body says which door to use), {@code box-swap-in-progress}, {@code correction-in-progress},
     *                      {@code payment-being-applied}, {@code order-too-old-to-correct}
     */
    @Transactional
    public Begun beginCancellation(UUID orderId, String reasonCode, String note, String actor) {
        Order order = locks.lock(orderId).orElseThrow(() -> new ResourceNotFoundException("Order", "id", orderId.toString()));
        Optional<Correction> standing = jdbc.query("SELECT * FROM order_correction WHERE order_id = ? AND kind = 'CANCELLATION' AND state <> 'REFUSED'",
                CORRECTION, orderId).stream().findFirst();
        if (standing.isPresent()) {
            if (!standing.get().reasonCode().equals(reasonCode)) {
                throw new SalesProblem(HttpStatus.CONFLICT, "already-cancelled", "this order's cancellation is already recorded, with the reason "
                        + standing.get().reasonCode() + ": a cancellation is recorded once").with("correctionId", standing.get().id().toString())
                        .with("state", standing.get().state());
            }
            return new Begun(standing.get(), false);
        }
        if ((order.status() != OrderStatus.PAID && order.status() != OrderStatus.FULFILLED) || dispatched(orderId)) {
            SalesProblem refused = new SalesProblem(HttpStatus.CONFLICT, "cancellation-not-allowed", "only a paid order that has not been dispatched is "
                    + "cancelled this way; this one is " + order.status()).with("status", order.status().name());
            if (order.status() == OrderStatus.SHIPPED || order.status() == OrderStatus.DELIVERED) {
                refused.with("use", "a-return");
            } else if (order.status() == OrderStatus.CREATED) {
                refused.with("use", "the-cancel-door");
            }
            throw refused;
        }
        swaps.requireNoneInFlight(orderId);
        guard.requireNoneInFlight(orderId);
        if (order.settlingConfirmationId() != null) {
            throw new SalesProblem(HttpStatus.CONFLICT, "payment-being-applied", "a confirmed payment is being applied to this order right now: try again in a moment");
        }
        Sale sale = saleOf(order);
        List<CorrectionAmounts.Line> lines = sale.lines().stream()
                .map(l -> new CorrectionAmounts.Line(l.id(), l.quantity(), l.unitPrice(), l.discountPerPiece())).toList();
        // Throws when the lines and delivery do not come to the order's total: what is owed on a cancellation is what was paid, or nothing is written.
        CorrectionAmounts.Amounts owed = CorrectionAmounts.cancellation(lines, order.shippingAmount(), order.deliveryWaivedAmount(), order.total());

        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        // Never dated before the money arrived: payment accepts an arrival a few seconds ahead of its own clock (FINAL AV7).
        Instant occurredAt = now.isBefore(sale.arrivedAt()) ? sale.arrivedAt() : now;
        UUID id = UUID.randomUUID();
        insert(id, order, CANCELLATION, reasonCode, note, sale, occurredAt, null, owed, bearers.forCancellation(reasonCode), null, null, actor, now);
        for (SoldLine line : sale.lines()) {
            boolean boxed = line.boxLineId() != null && line.boxVariantId() != null;
            insertLine(id, line, line.quantity(), boxed ? line.boxLineId() : null, boxed ? line.boxVariantId() : null);
        }
        log.info("Order {}: paid cancellation {} recorded by {} ({}), {} {} owed; asking inventory", orderId, id, actor, reasonCode, owed.owedGross(),
                order.currency());
        return new Begun(get(id), true);
    }

    // ------------------------------------------------------------------ pieces that came back after dispatch

    /** One line of a return: how many pieces of this sale line came back. */
    public record ReturnLine(UUID lineId, int quantity) {
    }

    /**
     * @param returnedAt       when the parcel came back; null means now
     * @param backdateReason   why it is keyed beyond the 72 hours, when it is
     * @param confirmDuplicate the person has confirmed that this is not the return they recorded a moment ago
     */
    public record ReturnAsk(String reasonCode, Instant returnedAt, String backdateReason, String note, boolean confirmDuplicate, List<ReturnLine> lines) {
    }

    /**
     * Decides whether these pieces may be recorded as returned and writes the return down, REQUESTED, with its lines, one row per piece (each with the id
     * inventory will know it by) and what is owed: the pieces' own price less their own discount, never delivery, never the box. One transaction, under
     * the order's lock. The order itself is not changed.
     *
     * @param key         the caller's Idempotency-Key: the same key with the same request answers the return it recorded, BEFORE any rule is looked at
     * @param mayBackdate whether the caller holds BACKDATE_SALE (a parcel that came back more than 72 hours ago)
     * @throws ResourceNotFoundException 404, no such order
     * @throws SalesProblem 409 {@code idempotency-key-reused}, {@code return-not-allowed}, {@code correction-in-progress},
     *                      {@code order-too-old-to-correct}, {@code more-than-was-sold}, {@code similar-return-just-recorded}; 400 {@code invalid-line},
     *                      {@code invalid-occurred-at}, {@code backdate-reason-required}
     * @throws org.springframework.security.access.AccessDeniedException 403, a date beyond the window by someone who may not back-date
     */
    @Transactional
    public Begun beginReturn(UUID orderId, ReturnAsk ask, String key, String actor, boolean mayBackdate) {
        Order order = locks.lock(orderId).orElseThrow(() -> new ResourceNotFoundException("Order", "id", orderId.toString()));
        String hash = hash(orderId, ask);
        Optional<Correction> keyed = jdbc.query("SELECT * FROM order_correction WHERE idempotency_key = ?", CORRECTION, key).stream().findFirst();
        if (keyed.isPresent()) {
            if (!keyed.get().orderId().equals(orderId) || !hash.equals(keyed.get().requestHash())) {
                throw new SalesProblem(HttpStatus.CONFLICT, "idempotency-key-reused", "this Idempotency-Key was already used for a different return: use a new key");
            }
            return new Begun(keyed.get(), false);
        }
        if (order.status() != OrderStatus.SHIPPED && order.status() != OrderStatus.DELIVERED) {
            SalesProblem refused = new SalesProblem(HttpStatus.CONFLICT, "return-not-allowed", "only an order that was dispatched or handed over takes a "
                    + "return; this one is " + order.status()).with("status", order.status().name());
            if (order.status() == OrderStatus.PAID || order.status() == OrderStatus.FULFILLED) {
                refused.with("use", "the-paid-cancellation");
            }
            throw refused;
        }
        guard.requireNoneInFlight(orderId);
        Sale sale = saleOf(order);

        java.util.Map<UUID, Integer> asked = new java.util.TreeMap<>();
        for (ReturnLine line : ask.lines()) {
            SoldLine sold = sale.lines().stream().filter(l -> l.id().equals(line.lineId())).findFirst().orElseThrow(() ->
                    new SalesProblem(HttpStatus.BAD_REQUEST, "invalid-line", "line " + line.lineId() + " is not a line of this order").with("lineId", String.valueOf(line.lineId())));
            if (line.quantity() < 1 || asked.put(line.lineId(), line.quantity()) != null) {
                throw new SalesProblem(HttpStatus.BAD_REQUEST, "invalid-line", "each line of the order is named once, with at least one piece")
                        .with("lineId", line.lineId().toString());
            }
            int taken = jdbc.queryForObject("""
                    SELECT COALESCE(sum(l.quantity), 0) FROM order_correction_line l JOIN order_correction c ON c.id = l.correction_id
                     WHERE l.sale_line_id = ? AND c.state <> 'REFUSED'""", Integer.class, line.lineId());
            if (taken + line.quantity() > sold.quantity()) {
                throw new SalesProblem(HttpStatus.CONFLICT, "more-than-was-sold", sold.quantity() + " of this line were sold and " + taken
                        + " have already come back: " + line.quantity() + " more cannot be returned").with("lineId", line.lineId().toString())
                        .with("sold", sold.quantity()).with("alreadyReturned", taken);
            }
        }
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        if (!ask.confirmDuplicate()) {
            refuseALikelyDoubleEntry(orderId, asked, now);
        }
        // Dated when the parcel came back: never before the order went out (a pickup: was handed over), and beyond 72 hours by someone who may back-date.
        Instant wentOut = jdbc.query("SELECT occurred_at FROM order_dispatch_step WHERE order_id = ? AND step = ?",
                (rs, n) -> rs.getTimestamp(1).toInstant(), orderId, order.isSelfCollect() ? "DELIVERED" : "DISPATCHED").stream().findFirst().orElse(sale.arrivedAt());
        DispatchDates.Dated when = DispatchDates.check(ask.returnedAt(), wentOut, now, backdateWindow, mayBackdate, ask.backdateReason());

        List<SoldLine> returned = sale.lines().stream().filter(l -> asked.containsKey(l.id())).toList();
        CorrectionAmounts.Amounts owed = CorrectionAmounts.ofReturn(returned.stream()
                .map(l -> new CorrectionAmounts.Line(l.id(), asked.get(l.id()), l.unitPrice(), l.discountPerPiece())).toList());
        UUID id = UUID.randomUUID();
        insert(id, order, RETURN, ask.reasonCode(), ask.note(), sale, when.occurredAt(), when.backdateReason(), owed, bearers.forReturn(ask.reasonCode()), key, hash,
                actor, now);
        for (SoldLine line : returned) {
            insertLine(id, line, asked.get(line.id()), null, null);
            for (int pieceNo = 1; pieceNo <= asked.get(line.id()); pieceNo++) {
                jdbc.update("INSERT INTO order_return_piece (id, correction_id, sale_line_id, piece_no) VALUES (?, ?, ?, ?)", pieceId(id, line.id(), pieceNo), id,
                        line.id(), pieceNo);
            }
        }
        log.info("Order {}: return {} recorded by {} ({}), {} piece(s), {} {} owed; asking inventory", orderId, id, actor, ask.reasonCode(),
                asked.values().stream().mapToInt(Integer::intValue).sum(), owed.owedGross(), order.currency());
        return new Begun(get(id), true);
    }

    /**
     * The id of one piece that came back: the same on every try, and the id inventory holds the piece under. Derived from the return, the line and the
     * piece's number, so two returns of one line never share a piece.
     */
    static UUID pieceId(UUID correctionId, UUID saleLineId, int pieceNo) {
        return UUID.nameUUIDFromBytes(("return-piece:" + correctionId + ":" + saleLineId + ":" + pieceNo).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * A second return of exactly the same pieces of one order, minutes after the first, is far more often a double entry than a second parcel: it is
     * refused until the person confirms it ({@code confirmDuplicate}).
     */
    private void refuseALikelyDoubleEntry(UUID orderId, java.util.Map<UUID, Integer> asked, Instant now) {
        for (Correction recent : jdbc.query("SELECT * FROM order_correction WHERE order_id = ? AND kind = 'RETURN' AND state <> 'REFUSED' AND recorded_at > ?",
                CORRECTION, orderId, Timestamp.from(now.minus(duplicateWindow)))) {
            java.util.Map<UUID, Integer> theirs = new java.util.TreeMap<>();
            lines(recent.id()).forEach(l -> theirs.put(l.saleLineId(), l.quantity()));
            if (theirs.equals(asked)) {
                throw new SalesProblem(HttpStatus.CONFLICT, "similar-return-just-recorded", "a return of exactly these pieces was recorded for this order at "
                        + recent.recordedAt() + ": if this is another parcel, send it again with confirmDuplicate true")
                        .with("returnId", recent.id().toString()).with("recordedAt", recent.recordedAt().toString());
            }
        }
    }

    /** The fingerprint of a return request: what makes the return (the order, the reason, when it came back, the lines), not the words typed beside it. */
    private static String hash(UUID orderId, ReturnAsk ask) {
        StringBuilder canonical = new StringBuilder("return/1;").append(orderId).append(';').append(ask.reasonCode()).append(';')
                .append(ask.returnedAt() == null ? "now" : ask.returnedAt().truncatedTo(ChronoUnit.MICROS)).append(';');
        ask.lines().stream().sorted(java.util.Comparator.comparing(l -> String.valueOf(l.lineId())))
                .forEach(l -> canonical.append(l.lineId()).append('x').append(l.quantity()).append(';'));
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every JDK", e);
        }
    }

    // ------------------------------------------------------------------ what inventory is asked
    /** The request for inventory, rebuilt from what is stored: the same bytes on every try, whoever makes it. */
    public InventoryClient.ReversalAsk ask(UUID correctionId) {
        Correction correction = get(correctionId);
        List<Line> lines = lines(correctionId);
        if (CANCELLATION.equals(correction.kind())) {
            UUID reservation = jdbc.queryForObject("SELECT reservation_id FROM orders WHERE id = ?", UUID.class, correction.orderId());
            List<InventoryClient.GoodsAsk> goods = lines.stream().map(l -> new InventoryClient.GoodsAsk(l.saleLineId(), l.variantId(), l.quantity())).toList();
            List<InventoryClient.BoxAsk> boxes = lines.stream().filter(l -> l.boxLineId() != null)
                    .map(l -> new InventoryClient.BoxAsk(l.boxLineId(), l.boxVariantId(), l.productId(), l.quantity())).toList();
            return new InventoryClient.ReversalAsk(correctionId, CANCELLATION, correction.orderId(), reservation, correction.occurredAt(), goods, boxes, List.of());
        }
        List<InventoryClient.PieceAsk> pieces = new ArrayList<>();
        for (Piece piece : pieces(correctionId)) {
            UUID variant = lines.stream().filter(l -> l.saleLineId().equals(piece.saleLineId())).findFirst().orElseThrow().variantId();
            pieces.add(new InventoryClient.PieceAsk(piece.id(), piece.saleLineId(), variant));
        }
        return new InventoryClient.ReversalAsk(correctionId, RETURN, correction.orderId(), null, correction.occurredAt(), List.of(), List.of(), pieces);
    }

    // ------------------------------------------------------------------ the endings

    /**
     * Inventory has put the stock back: the correction is DONE and announced, and a cancelled order becomes CANCELLED and forgets its recipient and
     * address -- ONE transaction, under the order's lock. Idempotent: a correction that is no longer in flight is returned as it stands.
     *
     * @throws ResourceNotFoundException no such correction
     */
    @Transactional
    public Correction finish(UUID correctionId) {
        UUID orderId = orderOf(correctionId);
        Order order = locks.lock(orderId).orElseThrow(() -> new IllegalStateException("correction " + correctionId + " names an order that does not exist"));
        Correction correction = lock(correctionId);
        if (!correction.inFlight()) {
            return correction;
        }
        if (CANCELLATION.equals(correction.kind())) {
            order.cancelPaid();
            // The status reaches the database first: a recorded (phone) order that delivers must keep its recipient and address until it is cancelled
            // (orders_phone_delivery_whole). The caller of a phone order stays: the refund is theirs.
            repository.saveAndFlush(order);
            jdbc.update("""
                    UPDATE orders SET recipient_name = NULL, recipient_phone = NULL, address_line1 = NULL, address_line2 = NULL, address_city = NULL,
                        address_state = NULL, address_postal_code = NULL, address_country = NULL WHERE id = ?""", orderId);
            order.forgetDelivery();
        }
        jdbc.update("UPDATE order_correction SET state = 'DONE', completed_at = ?, next_attempt_at = NULL WHERE id = ?",
                Timestamp.from(Instant.now().truncatedTo(ChronoUnit.MICROS)), correctionId);
        Correction done = get(correctionId);
        events.saleCorrected(CANCELLATION.equals(done.kind()) ? SaleCorrectedEvent.CANCELLED : SaleCorrectedEvent.RETURNED,
                event(order, done, lines(correctionId), CANCELLATION.equals(done.kind()) ? null : pieces(correctionId)), done.recordedBy());
        log.info("Order {}: {} {} is done: {} {} owed to the customer", orderId, done.kind(), correctionId, done.owedGross(), done.currency());
        return done;
    }

    /**
     * Inventory proved it moved nothing: this attempt is over and the order is free again. Idempotent: a correction that is no longer in flight is
     * returned as it stands.
     *
     * @param code inventory's reason, or how it was abandoned
     */
    @Transactional
    public Correction refuse(UUID correctionId, String code) {
        locks.lock(orderOf(correctionId));
        Correction correction = lock(correctionId);
        if (!correction.inFlight()) {
            return correction;
        }
        jdbc.update("UPDATE order_correction SET state = 'REFUSED', refused_code = ?, next_attempt_at = NULL WHERE id = ?",
                code.length() > 48 ? code.substring(0, 48) : code, correctionId);
        return get(correctionId);
    }

    /** Inventory's answer settled nothing: the correction stays in flight, to be asked again after a wait that doubles from 15 seconds to five minutes. */
    @Transactional
    public Correction retryLater(UUID correctionId) {
        Correction correction = lock(correctionId);
        if (correction.inFlight()) {
            jdbc.update("UPDATE order_correction SET attempts = attempts + 1, next_attempt_at = ? WHERE id = ?",
                    Timestamp.from(Instant.now().plus(CorrectionReplies.backoff(correction.attempts())).truncatedTo(ChronoUnit.MICROS)), correctionId);
        }
        return get(correctionId);
    }

    // ------------------------------------------------------------------ reads

    public Optional<Correction> find(UUID correctionId) {
        return jdbc.query("SELECT * FROM order_correction WHERE id = ?", CORRECTION, correctionId).stream().findFirst();
    }

    public Correction get(UUID correctionId) {
        return find(correctionId).orElseThrow(() -> new ResourceNotFoundException("Correction", "id", correctionId.toString()));
    }

    /** The latest attempt to cancel this paid order, refused ones included. */
    public Optional<Correction> latestCancellation(UUID orderId) {
        return jdbc.query("SELECT * FROM order_correction WHERE order_id = ? AND kind = 'CANCELLATION' ORDER BY recorded_at DESC, id LIMIT 1", CORRECTION, orderId)
                .stream().findFirst();
    }

    /** Every correction of this order, oldest first. */
    public List<Correction> ofOrder(UUID orderId) {
        return jdbc.query("SELECT * FROM order_correction WHERE order_id = ? ORDER BY recorded_at, id", CORRECTION, orderId);
    }

    /** Corrections in this state ({@code null}: every state), the longest-waiting first, at most {@code limit}. */
    public List<Correction> inState(String state, int limit) {
        return state == null
                ? jdbc.query("SELECT * FROM order_correction ORDER BY recorded_at DESC, id LIMIT ?", CORRECTION, limit)
                : jdbc.query("SELECT * FROM order_correction WHERE state = ? ORDER BY recorded_at, id LIMIT ?", CORRECTION, state, limit);
    }

    public List<Line> lines(UUID correctionId) {
        return jdbc.query("""
                SELECT l.*, i.title FROM order_correction_line l JOIN order_items i ON i.id = l.sale_line_id
                 WHERE l.correction_id = ? ORDER BY l.sale_line_id""", (rs, n) -> new Line(rs.getObject("sale_line_id", UUID.class),
                rs.getObject("variant_id", UUID.class), rs.getObject("product_id", UUID.class), rs.getInt("quantity"), rs.getBigDecimal("unit_price"),
                rs.getBigDecimal("discount_per_piece"), rs.getObject("promotion_id", UUID.class), rs.getObject("box_line_id", UUID.class),
                rs.getObject("box_variant_id", UUID.class), rs.getString("title")), correctionId);
    }

    public List<Piece> pieces(UUID correctionId) {
        return jdbc.query("SELECT * FROM order_return_piece WHERE correction_id = ? ORDER BY sale_line_id, piece_no", PIECE, correctionId);
    }

    // ------------------------------------------------------------------ plumbing shared with the return

    /** The order's row lock is held by the caller. */
    Correction lock(UUID correctionId) {
        return jdbc.query("SELECT * FROM order_correction WHERE id = ? FOR UPDATE", CORRECTION, correctionId).stream().findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Correction", "id", correctionId.toString()));
    }

    UUID orderOf(UUID correctionId) {
        return jdbc.queryForList("SELECT order_id FROM order_correction WHERE id = ?", UUID.class, correctionId).stream().findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Correction", "id", correctionId.toString()));
    }

    private boolean dispatched(UUID orderId) {
        return jdbc.queryForObject("SELECT count(*) FROM order_dispatch_step WHERE order_id = ? AND step IN ('DISPATCHED', 'DELIVERED')", Integer.class, orderId) > 0;
    }

    /**
     * The sale this order made, read as the database holds it. A sale from before a line kept its product and its per-piece discount, or before an
     * order kept its reservation and its settlement, cannot be undone exactly, and is never guessed.
     *
     * @throws SalesProblem 409 {@code order-too-old-to-correct}
     */
    private Sale saleOf(Order order) {
        List<SoldLine> lines = jdbc.query("""
                SELECT id, variant_id, product_id, quantity, unit_price, discount_per_piece, promotion_id, box_line_id, box_variant_id
                  FROM order_items WHERE order_id = ? ORDER BY id""", (rs, n) -> new SoldLine(rs.getObject("id", UUID.class),
                rs.getObject("variant_id", UUID.class), rs.getObject("product_id", UUID.class), rs.getInt("quantity"), rs.getBigDecimal("unit_price"),
                rs.getBigDecimal("discount_per_piece"), rs.getObject("promotion_id", UUID.class), rs.getObject("box_line_id", UUID.class),
                rs.getObject("box_variant_id", UUID.class)), order.id());
        List<Instant> arrived = order.paidConfirmationId() == null ? List.of() : jdbc.query(
                "SELECT arrived_at FROM order_settlement WHERE confirmation_id = ? AND order_id = ? AND state = 'DONE'",
                (rs, n) -> rs.getTimestamp(1).toInstant(), order.paidConfirmationId(), order.id());
        boolean whole = !lines.isEmpty() && lines.stream().allMatch(l -> l.productId() != null && l.discountPerPiece() != null)
                && order.reservationId() != null && order.receiptNumber() != null && !arrived.isEmpty();
        if (!whole) {
            throw new SalesProblem(HttpStatus.CONFLICT, "order-too-old-to-correct", "this sale was recorded before a sale kept everything a correction needs "
                    + "(each line's product and discount, the stock it took, the payment that settled it): it cannot be undone here");
        }
        return new Sale(order.paidConfirmationId(), arrived.get(0), order.receiptNumber(), lines);
    }

    private void insert(UUID id, Order order, String kind, String reasonCode, String note, Sale sale, Instant occurredAt, String backdateReason,
                CorrectionAmounts.Amounts owed, String feeBearer, String idempotencyKey, String requestHash, String actor, Instant now) {
        jdbc.update("""
                INSERT INTO order_correction (id, order_id, kind, state, reason_code, note, status_before, confirmation_id, receipt_number, sale_arrived_at,
                    occurred_at, backdate_reason, goods_at_list, discounts, delivery_refunded, waiver_taken_back, owed_gross, currency, fee_bearer,
                    idempotency_key, request_hash, recorded_by, recorded_at)
                VALUES (?, ?, ?, 'REQUESTED', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", id, order.id(), kind, reasonCode, note,
                order.status().name(), sale.confirmationId(), sale.receiptNumber(), Timestamp.from(sale.arrivedAt()), Timestamp.from(occurredAt),
                backdateReason, owed.goodsAtList(), owed.discounts(), owed.deliveryRefunded(), owed.waiverTakenBack(), owed.owedGross(), order.currency(),
                feeBearer, idempotencyKey, requestHash, actor, Timestamp.from(now));
    }

    private void insertLine(UUID correctionId, SoldLine line, int quantity, UUID boxLineId, UUID boxVariantId) {
        jdbc.update("""
                INSERT INTO order_correction_line (correction_id, sale_line_id, variant_id, product_id, quantity, unit_price, discount_per_piece,
                    promotion_id, box_line_id, box_variant_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", correctionId, line.id(), line.variantId(), line.productId(), quantity, line.unitPrice(),
                line.discountPerPiece(), line.promotionId(), boxLineId, boxVariantId);
    }

    /** The fact announced when a correction is done; {@code pieces} is null for a cancellation. Package-visible so its bytes can be pinned. */
    static SaleCorrectedEvent event(Order order, Correction c, List<Line> lines, List<Piece> pieces) {
        boolean phone = order.channel() == Channel.PHONE;
        return new SaleCorrectedEvent(c.id(), c.orderId(), c.kind(), c.reasonCode(), c.statusBefore(), order.status().name(), c.confirmationId(),
                c.receiptNumber(), c.saleArrivedAt(), c.occurredAt(), c.recordedAt(), c.currency(),
                lines.stream().map(l -> new SaleCorrectedEvent.Line(l.saleLineId(), l.variantId(), l.productId(), l.quantity(), l.unitPrice(),
                        l.discountPerPiece(), l.promotionId(), CorrectionAmounts.lineRefund(l.amounts()))).toList(),
                pieces == null ? null : pieces.stream().map(p -> new SaleCorrectedEvent.Piece(p.id(), p.saleLineId())).toList(),
                c.goodsAtList(), c.discounts(), c.deliveryRefunded(), c.waiverTakenBack(), c.owedGross(), c.feeBearer(), order.deliveryOption(),
                order.deliveryPayer(), order.keycloakId(), phone ? order.channel().name() : null, phone ? order.saleKind().name() : null);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
