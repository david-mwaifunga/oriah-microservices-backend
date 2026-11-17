package com.oriah.order.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.oriah.order.exception.SalesProblem;
import com.oriah.order.service.Corrections.Correction;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * What an order administrator reads about a return (Phase 12, plan 4.3): the pieces that came back, what the customer is owed for them and where each
 * piece stands. An allow-list: no note, no back-dating reason, no actor, and nothing about cost (what a piece cost is not order's to know).
 */
@Component
public class ReturnViews {

    /** The part of one sale line that came back. {@code lineRefund = (unitPrice - discountPerPiece) x quantity}. */
    @Schema(name = "ReturnLine")
    public record LineView(UUID lineId, String title, int quantity, BigDecimal unitPrice, BigDecimal discountPerPiece, BigDecimal lineRefund) {
    }

    /**
     * One piece that came back. {@code state}: AWAITING (nobody has looked at it yet; it is held, not for sale), IN_PROGRESS (decided, and the stock
     * system has not recorded it yet) or DONE. {@code outcome}: FINE, MARKED_DOWN (with the listing it now sells under) or UNSELLABLE.
     */
    @Schema(name = "ReturnPiece")
    public record PieceView(UUID pieceId, UUID lineId, int pieceNo, String state, @JsonInclude(JsonInclude.Include.NON_NULL) String outcome,
                            @JsonInclude(JsonInclude.Include.NON_NULL) UUID markdownVariantId, @JsonInclude(JsonInclude.Include.NON_NULL) Instant assessedAt) {
        static PieceView of(Corrections.Piece p) {
            return new PieceView(p.id(), p.saleLineId(), p.pieceNo(), p.state(), p.outcome(), p.markdownVariantId(), p.assessedAt());
        }
    }

    /** The new order a return was exchanged for: a link only, no money is netted between the two. */
    @Schema(name = "ReturnExchange")
    public record ExchangeView(UUID exchangeId, UUID newOrderId) {
    }

    /**
     * A return. {@code owedGross} is what the customer is owed before any charge for sending it (the pieces only: never delivery, never the box);
     * {@code feeBearer} says who bears that charge. {@code state}: REQUESTED (the stock system has not settled it), DONE or REFUSED.
     */
    @Schema(name = "Return")
    public record ReturnView(UUID returnId, UUID orderId, String receiptNumber, String state, String reasonCode, Instant returnedAt, List<LineView> lines,
                             List<PieceView> pieces, BigDecimal owedGross, String feeBearer, String currency,
                             @JsonInclude(JsonInclude.Include.NON_NULL) ExchangeView exchange,
                             @JsonInclude(JsonInclude.Include.NON_NULL) String refusedCode, boolean replay) {
    }

    /** A page of returns. */
    @Schema(name = "ReturnPage")
    public record Page(List<ReturnView> content, int page, int size, long totalElements) {
    }

    static final int MAX_PAGE_SIZE = 50;

    private final Corrections corrections;
    private final JdbcTemplate jdbc;

    public ReturnViews(Corrections corrections, JdbcTemplate jdbc) {
        this.corrections = corrections;
        this.jdbc = jdbc;
    }

    public ReturnView of(Correction c, boolean replay) {
        List<LineView> lines = corrections.lines(c.id()).stream().map(l -> new LineView(l.saleLineId(), l.title(), l.quantity(), l.unitPrice(),
                l.discountPerPiece(), CorrectionAmounts.lineRefund(l.amounts()))).toList();
        ExchangeView exchange = jdbc.query("SELECT id, new_order_id FROM order_exchange WHERE return_id = ?",
                (rs, n) -> new ExchangeView(rs.getObject("id", UUID.class), rs.getObject("new_order_id", UUID.class)), c.id()).stream().findFirst().orElse(null);
        return new ReturnView(c.id(), c.orderId(), c.receiptNumber(), c.state(), c.reasonCode(), c.occurredAt(), lines,
                corrections.pieces(c.id()).stream().map(PieceView::of).toList(), c.owedGross(), c.feeBearer(), c.currency(), exchange, c.refusedCode(), replay);
    }

    /**
     * Returns by where they stand: {@code AWAITING_ASSESSMENT} (received, with a piece nobody has finished looking at: the work list, the longest
     * waiting first), {@code IN_FLIGHT} (the stock system has not settled them) or {@code ALL} (newest first, refused attempts included).
     *
     * @throws SalesProblem 400 {@code invalid-state}
     */
    public Page list(String state, int page, int size) {
        int pageSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int pageNumber = Math.max(page, 0);
        String wanted = state == null ? "AWAITING_ASSESSMENT" : state.strip().toUpperCase(Locale.ROOT);
        String where = switch (wanted) {
            case "AWAITING_ASSESSMENT" -> " WHERE c.kind = 'RETURN' AND c.state = 'DONE' AND EXISTS (SELECT 1 FROM order_return_piece p "
                    + "WHERE p.correction_id = c.id AND p.state <> 'DONE')";
            case "IN_FLIGHT" -> " WHERE c.kind = 'RETURN' AND c.state = 'REQUESTED'";
            case "ALL" -> " WHERE c.kind = 'RETURN'";
            default -> throw new SalesProblem(HttpStatus.BAD_REQUEST, "invalid-state", "state is one of AWAITING_ASSESSMENT, IN_FLIGHT, ALL");
        };
        long total = jdbc.queryForObject("SELECT count(*) FROM order_correction c" + where, Long.class);
        List<UUID> ids = jdbc.queryForList("SELECT c.id FROM order_correction c" + where + " ORDER BY c.recorded_at " + ("ALL".equals(wanted) ? "DESC" : "ASC")
                + ", c.id LIMIT ? OFFSET ?", UUID.class, pageSize, (long) pageNumber * pageSize);
        return new Page(ids.stream().map(id -> of(corrections.get(id), false)).toList(), pageNumber, pageSize, total);
    }
}
