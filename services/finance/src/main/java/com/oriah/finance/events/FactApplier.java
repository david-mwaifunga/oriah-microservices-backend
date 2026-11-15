package com.oriah.finance.events;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.oriah.events.EventJson;
import com.oriah.finance.events.inbound.ConsignmentLine;
import com.oriah.finance.events.inbound.OrderCreated;
import com.oriah.finance.events.inbound.ProductSnapshot;

import tools.jackson.core.JacksonException;

/**
 * Keeps the facts that are never money: the consignment lines procurement announced (the standing list of estimated opening stock reads them), the names of products (display
 * only: the report by product never takes a figure from a name) and the orders finance has heard of (a typed expense that names one is checked against them). Nothing about
 * a person is read: not a supplier's name, not an order's owner.
 */
@Component
public class FactApplier {

    private final JdbcTemplate jdbc;

    public FactApplier(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** {@code consignment.received} (revision 1) and {@code consignment.amended} (its own revision): a full snapshot, kept as it was announced. */
    @Transactional
    public void consignmentLine(EventContext ctx, byte[] payload) {
        ConsignmentLine l = read(payload, ConsignmentLine.class, "consignment line");
        if (l.consignmentLineId() == null || l.consignmentId() == null || l.variantId() == null || l.receivedAt() == null || l.landedTotal() == null) {
            throw new IllegalArgumentException("a consignment line names its ids, its receipt and its landed total");
        }
        if (!"ZMW".equals(l.currency())) {
            throw new IllegalArgumentException("a consignment line is in ZMW only, was " + l.currency());
        }
        boolean amended = "consignment.amended".equals(ctx.type());
        int revision = amended ? (l.revision() == null ? 0 : l.revision()) : 1;
        if (revision < 1 || (amended && revision < 2)) {
            throw new IllegalArgumentException("an amendment is revision 2 or later, was " + l.revision());
        }
        SaleApplier.ngwee(l.landedTotal(), "a landed total");
        jdbc.update("""
                INSERT INTO consignment_line_fact (consignment_line_id, revision, consignment_id, consignment_reference, variant_id, sku, kind, quantity, landed_total, is_estimated,
                                                   supplier_known, received_at, source_recorded_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (consignment_line_id, revision) DO NOTHING""",
                l.consignmentLineId(), revision, l.consignmentId(), l.consignmentReference(), l.variantId(), l.sku(), l.kind(), l.quantity(), l.landedTotal(), l.isEstimated(),
                l.supplierId() != null, java.sql.Timestamp.from(l.receivedAt()), ctx.recordedAt() == null ? null : java.sql.Timestamp.from(ctx.recordedAt()));
    }

    /** {@code product.created}, {@code product.updated} and {@code product.deleted}: the name follows the newest snapshot; a deletion marks, never removes. */
    @Transactional
    public void product(EventContext ctx, byte[] payload) {
        ProductSnapshot p = read(payload, ProductSnapshot.class, "product");
        if (p.id() == null) {
            throw new IllegalArgumentException("a product event names its product");
        }
        if ("product.deleted".equals(ctx.type())) {
            jdbc.update("""
                    INSERT INTO product_name (product_id, title, kind, deleted, last_seq) VALUES (?, '(deleted product)', 'SELLABLE', TRUE, ?)
                    ON CONFLICT (product_id) DO UPDATE SET deleted = TRUE, last_seq = EXCLUDED.last_seq WHERE product_name.last_seq < EXCLUDED.last_seq""", p.id(), ctx.seq());
            return;
        }
        if (p.title() == null || p.title().isBlank()) {
            throw new IllegalArgumentException("a product snapshot names its title");
        }
        String kind = p.kind() == null ? "SELLABLE" : p.kind();   // every snapshot written before kinds existed was a product made to be sold
        jdbc.update("""
                INSERT INTO product_name (product_id, title, kind, deleted, last_seq) VALUES (?, ?, ?, FALSE, ?)
                ON CONFLICT (product_id) DO UPDATE SET title = EXCLUDED.title, kind = EXCLUDED.kind, last_seq = EXCLUDED.last_seq WHERE product_name.last_seq < EXCLUDED.last_seq""",
                p.id(), p.title(), kind, ctx.seq());
    }

    /** {@code order.created}: the order exists. */
    @Transactional
    public void orderCreated(EventContext ctx, byte[] payload) {
        OrderCreated o = read(payload, OrderCreated.class, "order");
        if (o.orderId() == null) {
            throw new IllegalArgumentException("order.created names its order");
        }
        jdbc.update("INSERT INTO order_ref (order_id, channel) VALUES (?, ?) ON CONFLICT DO NOTHING", o.orderId(), o.channel());
    }

    private static <T> T read(byte[] payload, Class<T> type, String what) {
        try {
            return EventJson.mapper().readValue(payload, type);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("a " + what + " event cannot be read: " + e.getOriginalMessage(), e);
        }
    }
}
