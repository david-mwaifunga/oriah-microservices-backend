package com.oriah.inventory.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.oriah.inventory.events.ReceiptSnapshot.Slice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What inventory is willing to turn into lots. The event is a full snapshot of one consignment line as it landed;
 * before any stock is made from it, every promise it makes is checked, because stock made from a wrong event is a
 * wrong cost for the life of the lot. An event that fails is refused, never repaired.
 */
class ReceiptSnapshotTest {

    private static BigDecimal k(String amount) {
        return new BigDecimal(amount);
    }

    /** The ring line of the worked example: 7 rings, K10,500.00 + K202.90 shared = K10,702.90, 4 x 1528.99 + 3 x 1528.98. */
    private static ReceiptSnapshot ring() {
        return new ReceiptSnapshot(UUID.randomUUID(), "C-000045", UUID.randomUUID(), UUID.randomUUID(), "RING-1",
                "Aurelia Ring", "Aurelia Ring - RING-1", "SELLABLE", UUID.randomUUID(), "Dubai Gold",
                "2026-11-12T10:00:00.123456Z", false, "ZMW", 7, k("10500.00"), k("202.90"), k("10702.90"),
                List.of(new Slice(4, k("1528.99")), new Slice(3, k("1528.98"))), 1, null, null, null);
    }

    private static ReceiptSnapshot with(ReceiptSnapshot r, int quantity, BigDecimal goods, BigDecimal shared, BigDecimal landed,
                                        List<Slice> slices) {
        return new ReceiptSnapshot(r.consignmentId(), r.consignmentReference(), r.consignmentLineId(), r.variantId(), r.sku(),
                r.productTitle(), r.variantTitle(), r.kind(), r.supplierId(), r.supplierName(), r.receivedAt(), r.isEstimated(),
                r.currency(), quantity, goods, shared, landed, slices, r.linePosition(), r.revision(), r.reason(), r.amendedAt());
    }

    @Test
    @DisplayName("the worked example is accepted")
    void theWorkedExample() {
        ReceiptSnapshot ring = ring();

        assertThatCode(ring::validate).doesNotThrowAnyException();
        assertThat(ring.receivedAtInstant()).isEqualTo(Instant.parse("2026-11-12T10:00:00.123456Z"));
    }

    @Test
    @DisplayName("opening stock has no supplier and is an estimate, and is accepted")
    void openingStock() {
        ReceiptSnapshot r = ring();
        ReceiptSnapshot opening = new ReceiptSnapshot(r.consignmentId(), r.consignmentReference(), r.consignmentLineId(), r.variantId(),
                r.sku(), null, r.variantTitle(), "SELLABLE", null, null, r.receivedAt(), true, "ZMW", 7, k("10500.00"),
                k("202.90"), k("10702.90"), r.slices(), 1, null, null, null);

        assertThatCode(opening::validate).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a unit may cost K0.00 (an absurd input, but the arithmetic is exact)")
    void zeroCostUnits() {
        ReceiptSnapshot r = with(ring(), 5, k("0.03"), k("0.00"), k("0.03"), List.of(new Slice(3, k("0.01")), new Slice(2, k("0.00"))));

        assertThatCode(r::validate).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("slices that do not add up to the quantity are refused")
    void slicesMustAddUpToTheQuantity() {
        ReceiptSnapshot r = with(ring(), 7, k("10500.00"), k("202.90"), k("10702.90"),
                List.of(new Slice(4, k("1528.99")), new Slice(2, k("1528.98"))));

        assertThatThrownBy(r::validate).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("slices");
    }

    @Test
    @DisplayName("slices that do not add up to the landed total are refused, to the ngwee")
    void slicesMustAddUpToTheLandedTotal() {
        // Goods + shared agree with the landed total (10,702.91), and the slices are fine in themselves, but they come to 10,702.90.
        ReceiptSnapshot r = with(ring(), 7, k("10500.00"), k("202.91"), k("10702.91"),
                List.of(new Slice(4, k("1528.99")), new Slice(3, k("1528.98"))));

        assertThatThrownBy(r::validate).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("landed");
    }

    @Test
    @DisplayName("goods plus shared cost must be the landed total")
    void goodsPlusSharedIsLanded() {
        ReceiptSnapshot r = with(ring(), 7, k("10500.00"), k("202.89"), k("10702.90"),
                List.of(new Slice(4, k("1528.99")), new Slice(3, k("1528.98"))));

        assertThatThrownBy(r::validate).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("goods");
    }

    @Test
    @DisplayName("a line lands at one or two unit costs, never three")
    void atMostTwoSlices() {
        ReceiptSnapshot r = with(ring(), 6, k("100.00"), k("0.00"), k("100.00"),
                List.of(new Slice(2, k("16.67")), new Slice(2, k("16.66")), new Slice(2, k("16.67"))));

        assertThatThrownBy(r::validate).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("slices");
    }

    @Test
    @DisplayName("the dearer unit cost comes first, and the two are exactly a ngwee apart")
    void dearerFirstAndANgweeApart() {
        ReceiptSnapshot cheaperFirst = with(ring(), 7, k("10500.00"), k("202.90"), k("10702.90"),
                List.of(new Slice(3, k("1528.98")), new Slice(4, k("1528.99"))));
        ReceiptSnapshot twoNgweeApart = with(ring(), 2, k("3.00"), k("0.00"), k("3.00"),
                List.of(new Slice(1, k("1.51")), new Slice(1, k("1.49"))));

        assertThatThrownBy(cheaperFirst::validate).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("dearer");
        assertThatThrownBy(twoNgweeApart::validate).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ngwee apart");
    }

    @Test
    @DisplayName("money has at most two decimals, and no cost is negative")
    void moneyAtTheEdge() {
        ReceiptSnapshot threeDecimals = with(ring(), 7, k("10500.000"), k("202.900"), k("10702.900"),
                List.of(new Slice(4, k("1528.990")), new Slice(3, k("1528.980"))));
        ReceiptSnapshot negative = with(ring(), 1, k("-1.00"), k("0.00"), k("-1.00"), List.of(new Slice(1, k("-1.00"))));

        assertThatThrownBy(threeDecimals::validate).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("decimal");
        assertThatThrownBy(negative::validate).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("only goods and packaging, only kwacha, a real quantity and a real position")
    void theSmallPrint() {
        ReceiptSnapshot r = ring();
        ReceiptSnapshot kind = new ReceiptSnapshot(r.consignmentId(), r.consignmentReference(), r.consignmentLineId(), r.variantId(),
                r.sku(), r.productTitle(), r.variantTitle(), "SERVICE", r.supplierId(), r.supplierName(), r.receivedAt(),
                r.isEstimated(), "ZMW", 7, r.goodsTotal(), r.sharedCost(), r.landedTotal(), r.slices(), 1, null, null, null);
        ReceiptSnapshot currency = new ReceiptSnapshot(r.consignmentId(), r.consignmentReference(), r.consignmentLineId(), r.variantId(),
                r.sku(), r.productTitle(), r.variantTitle(), "SELLABLE", r.supplierId(), r.supplierName(), r.receivedAt(),
                r.isEstimated(), "USD", 7, r.goodsTotal(), r.sharedCost(), r.landedTotal(), r.slices(), 1, null, null, null);
        ReceiptSnapshot position = new ReceiptSnapshot(r.consignmentId(), r.consignmentReference(), r.consignmentLineId(), r.variantId(),
                r.sku(), r.productTitle(), r.variantTitle(), "SELLABLE", r.supplierId(), r.supplierName(), r.receivedAt(),
                r.isEstimated(), "ZMW", 7, r.goodsTotal(), r.sharedCost(), r.landedTotal(), r.slices(), 0, null, null, null);
        ReceiptSnapshot none = with(r, 0, k("0.00"), k("0.00"), k("0.00"), List.of());

        assertThatThrownBy(kind::validate).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("kind");
        assertThatThrownBy(currency::validate).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("currency");
        assertThatThrownBy(position::validate).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("linePosition");
        assertThatThrownBy(none::validate).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("quantity");
    }

    @Test
    @DisplayName("a receivedAt that is not a timestamp is refused")
    void receivedAtMustBeATimestamp() {
        ReceiptSnapshot r = ring();
        ReceiptSnapshot bad = new ReceiptSnapshot(r.consignmentId(), r.consignmentReference(), r.consignmentLineId(), r.variantId(),
                r.sku(), r.productTitle(), r.variantTitle(), "SELLABLE", r.supplierId(), r.supplierName(), "yesterday", false,
                "ZMW", 7, r.goodsTotal(), r.sharedCost(), r.landedTotal(), r.slices(), 1, null, null, null);

        assertThatThrownBy(bad::validate).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("receivedAt");
    }

    @Test
    @DisplayName("an event that carries no linePosition (written before it existed) is accepted")
    void anOlderEventHasNoLinePosition() {
        ReceiptSnapshot r = ring();
        ReceiptSnapshot old = new ReceiptSnapshot(r.consignmentId(), r.consignmentReference(), r.consignmentLineId(), r.variantId(),
                r.sku(), r.productTitle(), r.variantTitle(), "SELLABLE", r.supplierId(), r.supplierName(), r.receivedAt(), false,
                "ZMW", 7, r.goodsTotal(), r.sharedCost(), r.landedTotal(), r.slices(), null, null, null, null);

        assertThatCode(old::validate).doesNotThrowAnyException();
    }

    // ----- amendments -----

    private static ReceiptSnapshot amendment(Integer revision, String reason, String amendedAt) {
        ReceiptSnapshot r = ring();
        return new ReceiptSnapshot(r.consignmentId(), r.consignmentReference(), r.consignmentLineId(), r.variantId(), r.sku(), r.productTitle(),
                r.variantTitle(), r.kind(), r.supplierId(), r.supplierName(), r.receivedAt(), r.isEstimated(), r.currency(), r.quantity(),
                r.goodsTotal(), r.sharedCost(), r.landedTotal(), r.slices(), r.linePosition(), revision, reason, amendedAt);
    }

    @Test
    @DisplayName("an amendment says which revision it is, why, and when: and is otherwise checked exactly like a receipt")
    void anAmendment() {
        assertThatCode(() -> amendment(2, "the courier's bill was K8,000", "2026-11-13T09:00:00Z").validateAmendment()).doesNotThrowAnyException();
        assertThat(amendment(2, "x", "2026-11-13T09:00:00.5Z").amendedAtInstant()).isEqualTo(Instant.parse("2026-11-13T09:00:00.5Z"));
    }

    @Test
    @DisplayName("an amendment with no reason, a revision below 2, or no time is refused")
    void anAmendmentNeedsItsParts() {
        assertThatThrownBy(() -> amendment(1, "why", "2026-11-13T09:00:00Z").validateAmendment()).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("revision");
        assertThatThrownBy(() -> amendment(null, "why", "2026-11-13T09:00:00Z").validateAmendment()).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("revision");
        assertThatThrownBy(() -> amendment(2, " ", "2026-11-13T09:00:00Z").validateAmendment()).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("why");
        assertThatThrownBy(() -> amendment(2, "x".repeat(201), "2026-11-13T09:00:00Z").validateAmendment()).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("why");
        assertThatThrownBy(() -> amendment(2, "why", null).validateAmendment()).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("amendedAt");
        assertThatThrownBy(() -> amendment(2, "why", "last week").validateAmendment()).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("amendedAt");
    }
}
