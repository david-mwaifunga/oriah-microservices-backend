package com.oriah.inventory.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

import com.oriah.inventory.events.ReceiptSnapshot;
import com.oriah.inventory.events.ReceiptSnapshot.Slice;

/**
 * What the stock tests need, in one place: the worked example's two lines as procurement publishes them, a way to
 * put stock in through the same call the consumer uses, and a wipe between tests. The ledger refuses UPDATE and
 * DELETE, so a wipe is a TRUNCATE (allowed, as procurement's is).
 */
public final class StockTestData {

    public static final String ACTOR = "user:kc-maya";

    private StockTestData() {
    }

    public static void wipe(JdbcTemplate jdbc) {
        jdbc.execute("TRUNCATE stock_movement, stock_action, cost_lot, inventory");
    }

    private static BigDecimal k(String amount) {
        return new BigDecimal(amount);
    }

    /** 7 rings, K10,500.00 + K202.90 shared = K10,702.90: 4 x K1,528.99 and 3 x K1,528.98. */
    public static ReceiptSnapshot ring(UUID variant, String receivedAt) {
        return new ReceiptSnapshot(UUID.randomUUID(), "C-000045", UUID.randomUUID(), variant, "RING-1", "Aurelia Ring",
                "Aurelia Ring - RING-1", "SELLABLE", UUID.randomUUID(), "Dubai Gold", receivedAt, false, "ZMW", 7,
                k("10500.00"), k("202.90"), k("10702.90"),
                List.of(new Slice(4, k("1528.99")), new Slice(3, k("1528.98"))), 1, null, null, null);
    }

    /** 200 boxes, K10,000.00 + K5,797.10 shared = K15,797.10: 110 x K78.99 and 90 x K78.98. */
    public static ReceiptSnapshot box(UUID variant, String receivedAt) {
        return new ReceiptSnapshot(UUID.randomUUID(), "C-000045", UUID.randomUUID(), variant, "BOX-M", "Gift Box",
                "Gift Box - M", "PACKAGING", UUID.randomUUID(), "Dubai Gold", receivedAt, false, "ZMW", 200,
                k("10000.00"), k("5797.10"), k("15797.10"),
                List.of(new Slice(110, k("78.99")), new Slice(90, k("78.98"))), 2, null, null, null);
    }

    /** The same line, as a different receipt of the same variant (new consignment, new line, its own time). */
    public static ReceiptSnapshot another(ReceiptSnapshot of, String receivedAt, int linePosition) {
        return new ReceiptSnapshot(UUID.randomUUID(), "C-000046", UUID.randomUUID(), of.variantId(), of.sku(), of.productTitle(),
                of.variantTitle(), of.kind(), of.supplierId(), of.supplierName(), receivedAt, of.isEstimated(), of.currency(),
                of.quantity(), of.goodsTotal(), of.sharedCost(), of.landedTotal(), of.slices(), linePosition, null, null, null);
    }

    /** {@code qty} units at one cost (K10.00 each, nothing shared): the simplest stock a test needs. */
    public static ReceiptSnapshot simple(UUID variant, int qty) {
        BigDecimal total = k("10.00").multiply(BigDecimal.valueOf(qty));
        return new ReceiptSnapshot(UUID.randomUUID(), "C-000001", UUID.randomUUID(), variant, "SKU-" + variant.toString().substring(0, 8),
                "Product", "Product - one", "SELLABLE", UUID.randomUUID(), "Supplier", "2026-11-10T08:00:00Z", false, "ZMW", qty,
                total, k("0.00"), total, List.of(new Slice(qty, k("10.00"))), 1, null, null, null);
    }

    /** Puts {@code qty} units of a variant on the shelf, through the same call the consumer uses. */
    public static void stock(StockService service, UUID variant, int qty) {
        service.applyReceipt(simple(variant, qty), context(1));
    }

    /** What the consumer hands the service besides the snapshot. */
    public static StockService.EventContext context(long outboxId) {
        return new StockService.EventContext(UUID.randomUUID(), outboxId, ACTOR, Instant.parse("2026-11-12T10:00:00Z"));
    }
}
