package com.oriah.procurement.allocation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.oriah.procurement.allocation.ConsignmentCosting.CostView;
import com.oriah.procurement.allocation.ConsignmentCosting.LineView;
import com.oriah.procurement.allocation.ConsignmentCosting.Outcome;
import com.oriah.procurement.allocation.ConsignmentCosting.SliceView;
import com.oriah.procurement.model.Consignment;
import com.oriah.procurement.model.Consignment.CostLineInput;
import com.oriah.procurement.model.Consignment.OpeningLine;
import com.oriah.procurement.model.CostBasis;
import com.oriah.procurement.model.CostTreatment;
import com.oriah.procurement.model.CostType;
import com.oriah.procurement.model.PurchaseOrder;
import com.oriah.procurement.model.PurchaseOrder.NewLine;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A consignment, costed: the consignment's own lines and cost lines turned into the allocator's
 * inputs, and what the allocator says turned back into kwacha a person can read.
 *
 * <p>This is the preview and, later, the receipt. A consignment that cannot be costed is not
 * an error to throw: it is a list of PROBLEMS in plain words, each naming the cost line or the
 * line, so a person can fix them one by one. The allocator's own arithmetic is tested to the
 * ngwee in {@code LandedCostAllocatorTest}; what is pinned here is the wiring: that each line
 * and cost reaches it as the consignment holds it, and that nothing is guessed.
 */
class ConsignmentCostingTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 12);

    private static NewLine line(String sku, int quantity, String total) {
        return new NewLine(UUID.randomUUID(), quantity, new BigDecimal(total), sku, "Product", "Product - " + sku,
                "SELLABLE");
    }

    private static PurchaseOrder order(NewLine... lines) {
        return new PurchaseOrder(UUID.randomUUID(), TODAY, null, List.of(lines), TODAY);
    }

    /** The worked example of MVP_PLAN Part 4: 7 rings at K10,500.00 and 200 boxes at K10,000.00. */
    private static Consignment ringsAndBoxes() {
        Consignment c = Consignment.draft(null);
        c.attach(order(line("RING", 7, "10500.00")));
        c.attach(order(line("BOX", 200, "10000.00")));
        return c;
    }

    private static CostLineInput cost(CostType type, String amount) {
        return new CostLineInput(type, new BigDecimal(amount), null, null, null, null, null);
    }

    private static CostLineInput cost(CostType type, String amount, CostBasis basis) {
        return new CostLineInput(type, new BigDecimal(amount), basis, null, null, null, null);
    }

    private static List<SliceView> slices(LineView line) {
        return line.slices();
    }

    private static SliceView slice(int quantity, String unit) {
        return new SliceView(quantity, new BigDecimal(unit));
    }

    // =====================================================================

    @Nested
    @DisplayName("the worked example: 7 rings and 200 boxes under one courier charge of K6,000")
    class WorkedExample {

        @Test
        @DisplayName("shared by units, the ring lands at K1,528.99 and K1,528.98 and the box at K78.99 and K78.98, to the ngwee")
        void byUnits() {
            Consignment c = ringsAndBoxes();
            c.replaceCostLines(List.of(cost(CostType.COURIER_INBOUND, "6000.00")));

            Outcome outcome = ConsignmentCosting.of(c);

            assertThat(outcome.problems()).isEmpty();
            assertThat(outcome.ready()).isTrue();
            assertThat(slices(outcome.lines().get(0))).containsExactly(slice(4, "1528.99"), slice(3, "1528.98"));
            assertThat(slices(outcome.lines().get(1))).containsExactly(slice(110, "78.99"), slice(90, "78.98"));
            assertThat(outcome.lines().get(0).sharedCost()).isEqualByComparingTo("202.90");
            assertThat(outcome.lines().get(0).landedTotal()).isEqualByComparingTo("10702.90");
            assertThat(outcome.lines().get(1).landedTotal()).isEqualByComparingTo("15797.10");
        }

        @Test
        @DisplayName("shared by value, the ring lands at K1,939.03 and K1,939.02 and the box at K64.64 and K64.63")
        void byValue() {
            Consignment c = ringsAndBoxes();
            c.replaceCostLines(List.of(cost(CostType.COURIER_INBOUND, "6000.00", CostBasis.VALUE)));

            Outcome outcome = ConsignmentCosting.of(c);

            assertThat(outcome.ready()).isTrue();
            assertThat(slices(outcome.lines().get(0))).containsExactly(slice(3, "1939.03"), slice(4, "1939.02"));
            assertThat(slices(outcome.lines().get(1))).containsExactly(slice(83, "64.64"), slice(117, "64.63"));
        }

        @Test
        @DisplayName("the totals add up: goods plus capitalized costs is what lands, and the units recover all of it")
        void totalsAddUp() {
            Consignment c = ringsAndBoxes();
            c.replaceCostLines(List.of(cost(CostType.COURIER_INBOUND, "6000.00")));

            Outcome outcome = ConsignmentCosting.of(c);

            assertThat(outcome.goodsTotal()).isEqualByComparingTo("20500.00");
            assertThat(outcome.capitalizedCostTotal()).isEqualByComparingTo("6000.00");
            assertThat(outcome.landedTotal()).isEqualByComparingTo("26500.00");
            BigDecimal fromUnits = outcome.lines().stream()
                    .flatMap(l -> l.slices().stream())
                    .map(s -> s.unitCost().multiply(BigDecimal.valueOf(s.quantity())))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(fromUnits).as("the unit costs, times the units, recover goods plus costs").isEqualByComparingTo("26500.00");
        }

        @Test
        @DisplayName("each cost says what it put on each line, so a screen can show the sum line by line")
        void eachCostShowsItsShares() {
            Consignment c = ringsAndBoxes();
            c.replaceCostLines(List.of(cost(CostType.COURIER_INBOUND, "6000.00")));

            Outcome outcome = ConsignmentCosting.of(c);

            CostView courier = outcome.costs().get(0);
            assertThat(courier.costType()).isEqualTo(CostType.COURIER_INBOUND);
            assertThat(courier.basis()).isEqualTo(CostBasis.UNITS);
            assertThat(courier.amount()).isEqualByComparingTo("6000.00");
            UUID ring = c.lines().get(0).id();
            UUID box = c.lines().get(1).id();
            assertThat(courier.perLine().get(ring)).isEqualByComparingTo("202.90");
            assertThat(courier.perLine().get(box)).isEqualByComparingTo("5797.10");
        }
    }

    // =====================================================================

    @Nested
    @DisplayName("goods only, packaging only: a consignment need not be the mixed 7-ring / 200-box shape")
    class GoodsOnlyAndPackagingOnly {

        private NewLine box(String sku, int quantity, String total) {
            return new NewLine(UUID.randomUUID(), quantity, new BigDecimal(total), sku, "Gift box", "Gift box " + sku,
                    "PACKAGING");
        }

        @Test
        @DisplayName("a packaging-only shipment shares its freight across the packaging units alone")
        void packagingOnly() {
            Consignment c = Consignment.draft(null);
            c.attach(order(box("SMALL", 100, "500.00"), box("LARGE", 50, "400.00")));
            c.replaceCostLines(List.of(cost(CostType.COURIER_INBOUND, "300.00")));

            Outcome outcome = ConsignmentCosting.of(c);

            // 300.00 over 150 units: 200.00 to the 100 small, 100.00 to the 50 large.
            assertThat(outcome.ready()).isTrue();
            assertThat(outcome.lines().get(0).sharedCost()).isEqualByComparingTo("200.00");
            assertThat(outcome.lines().get(1).sharedCost()).isEqualByComparingTo("100.00");
            assertThat(slices(outcome.lines().get(0))).containsExactly(slice(100, "7.00"));
            assertThat(slices(outcome.lines().get(1))).containsExactly(slice(50, "10.00"));
            assertThat(outcome.landedTotal()).isEqualByComparingTo("1200.00");
        }

        @Test
        @DisplayName("a goods-only shipment is shared between its pieces alone, by units or by value")
        void goodsOnly() {
            Consignment c = Consignment.draft(null);
            c.attach(order(line("RING", 7, "10500.00")));
            c.attach(order(line("CHAIN", 3, "1500.00")));
            c.replaceCostLines(List.of(cost(CostType.COURIER_INBOUND, "1000.00")));

            Outcome byUnits = ConsignmentCosting.of(c);

            // 1,000.00 over 10 pieces: 700.00 to the rings, 300.00 to the chains.
            assertThat(byUnits.lines().get(0).sharedCost()).isEqualByComparingTo("700.00");
            assertThat(byUnits.lines().get(1).sharedCost()).isEqualByComparingTo("300.00");
            assertThat(byUnits.landedTotal()).isEqualByComparingTo("13000.00");

            c.replaceCostLines(List.of(cost(CostType.COURIER_INBOUND, "1000.00", CostBasis.VALUE)));
            Outcome byValue = ConsignmentCosting.of(c);
            // 1,000.00 over 12,000.00 of goods: 875.00 to the rings, 125.00 to the chains.
            assertThat(byValue.lines().get(0).sharedCost()).isEqualByComparingTo("875.00");
            assertThat(byValue.lines().get(1).sharedCost()).isEqualByComparingTo("125.00");
        }

        @Test
        @DisplayName("packaging and goods in one shipment are shared between ALL the units, packaging included")
        void mixed() {
            Consignment c = Consignment.draft(null);
            c.attach(order(line("RING", 7, "10500.00")));
            c.attach(order(box("BOX", 200, "10000.00")));
            c.replaceCostLines(List.of(cost(CostType.COURIER_INBOUND, "6000.00")));

            Outcome outcome = ConsignmentCosting.of(c);

            assertThat(outcome.lines().get(1).sharedCost()).as("the 200 boxes carry most of a charge by units")
                    .isEqualByComparingTo("5797.10");
        }
    }

    @Nested
    @DisplayName("what is left out of the unit costs, and what is typed")
    class TreatmentsAndTypedShares {

        @Test
        @DisplayName("a PERIOD cost is a bill for the month: shown, totalled apart, and nothing is put on the goods")
        void periodCostIsNotShared() {
            Consignment c = ringsAndBoxes();
            c.replaceCostLines(List.of(
                    cost(CostType.COURIER_INBOUND, "6000.00"),
                    new CostLineInput(CostType.PORT_INCIDENTALS, new BigDecimal("40.00"), CostBasis.UNITS,
                            CostTreatment.PERIOD, null, "Storage", null)));

            Outcome outcome = ConsignmentCosting.of(c);

            assertThat(outcome.ready()).isTrue();
            assertThat(outcome.periodCostTotal()).isEqualByComparingTo("40.00");
            assertThat(outcome.capitalizedCostTotal()).isEqualByComparingTo("6000.00");
            assertThat(outcome.landedTotal()).isEqualByComparingTo("26500.00");
            assertThat(outcome.costs().get(1).perLine()).as("a period cost puts nothing on any line").isEmpty();
        }

        @Test
        @DisplayName("a typed split is used as typed, and the unit-level pass still runs inside each line")
        void typedSplitOverridesTheBasis() {
            Consignment c = ringsAndBoxes();
            UUID ring = c.lines().get(0).id();
            UUID box = c.lines().get(1).id();
            c.replaceCostLines(List.of(new CostLineInput(CostType.COURIER_INBOUND, new BigDecimal("6000.00"), null, null,
                    null, null, Map.of(ring, new BigDecimal("3000.00"), box, new BigDecimal("3000.00")))));

            Outcome outcome = ConsignmentCosting.of(c);

            assertThat(outcome.ready()).isTrue();
            assertThat(outcome.lines().get(0).sharedCost()).isEqualByComparingTo("3000.00");
            assertThat(outcome.lines().get(1).sharedCost()).isEqualByComparingTo("3000.00");
            // 10500 + 3000 = 13500 over 7 units = 1928.5714...: 1,350,000 ngwee is 192,857 each with 1 left over,
            // so one unit at 1928.58 and six at 1928.57.
            assertThat(slices(outcome.lines().get(0))).containsExactly(slice(1, "1928.58"), slice(6, "1928.57"));
            assertThat(outcome.costs().get(0).typed()).isTrue();
        }

        @Test
        @DisplayName("duty keyed per line lands on the line it was assessed for, not spread")
        void dutyIsDirect() {
            Consignment c = ringsAndBoxes();
            UUID ring = c.lines().get(0).id();
            UUID box = c.lines().get(1).id();
            c.replaceCostLines(List.of(new CostLineInput(CostType.DUTY, new BigDecimal("210.00"), null, null, null, null,
                    Map.of(ring, new BigDecimal("210.00"), box, new BigDecimal("0.00")))));

            Outcome outcome = ConsignmentCosting.of(c);

            assertThat(outcome.ready()).isTrue();
            assertThat(outcome.lines().get(0).sharedCost()).isEqualByComparingTo("210.00");
            assertThat(outcome.lines().get(1).sharedCost()).isEqualByComparingTo("0.00");
            assertThat(outcome.lines().get(0).landedTotal()).isEqualByComparingTo("10710.00");
        }

        @Test
        @DisplayName("duty, surtax and import VAT of zero need nothing typed: they read zero today and the fields stay")
        void zeroDutiesNeedNothing() {
            Consignment c = ringsAndBoxes();
            c.replaceCostLines(List.of(cost(CostType.COURIER_INBOUND, "6000.00"), cost(CostType.DUTY, "0.00"),
                    cost(CostType.SURTAX, "0.00"), cost(CostType.IMPORT_VAT, "0.00")));

            Outcome outcome = ConsignmentCosting.of(c);

            assertThat(outcome.ready()).isTrue();
            assertThat(outcome.landedTotal()).isEqualByComparingTo("26500.00");
            assertThat(outcome.costs().get(1).perLine().values()).allSatisfy(v -> assertThat(v).isEqualByComparingTo("0.00"));
        }

        @Test
        @DisplayName("a consignment with no costs is just its goods")
        void noCosts() {
            Consignment c = ringsAndBoxes();

            Outcome outcome = ConsignmentCosting.of(c);

            assertThat(outcome.ready()).isTrue();
            assertThat(outcome.landedTotal()).isEqualByComparingTo("20500.00");
            assertThat(outcome.lines().get(0).sharedCost()).isEqualByComparingTo("0.00");
        }

        @Test
        @DisplayName("the opening consignment is costed like any other: its lines have no supplier and its costs are estimates")
        void openingIsCosted() {
            Consignment c = Consignment.opening(null, List.of(
                    new OpeningLine(UUID.randomUUID(), 7, new BigDecimal("10000.00"), null, null, null,
                            "RING", "P", "P - RING", "SELLABLE"),
                    new OpeningLine(UUID.randomUUID(), 200, new BigDecimal("9000.00"), null, null, null,
                            "BOX", "P", "P - BOX", "PACKAGING")));

            Outcome outcome = ConsignmentCosting.of(c);

            assertThat(outcome.ready()).isTrue();
            assertThat(outcome.landedTotal()).isEqualByComparingTo("19000.00");
            assertThat(slices(outcome.lines().get(0))).containsExactly(slice(1, "1428.58"), slice(6, "1428.57"));
        }
    }

    // =====================================================================

    @Nested
    @DisplayName("when it cannot be costed yet: problems in plain words, nothing guessed")
    class Problems {

        @Test
        @DisplayName("a consignment with no lines has nothing to cost")
        void noLines() {
            Outcome outcome = ConsignmentCosting.of(Consignment.draft(null));

            assertThat(outcome.ready()).isFalse();
            assertThat(outcome.problems()).singleElement().asString().contains("no lines");
            assertThat(outcome.lines()).isEmpty();
        }

        @Test
        @DisplayName("a cost shared by weight names the line that has no weight")
        void weightMissing() {
            Consignment c = ringsAndBoxes();
            c.changeLineDetails(c.lines().get(0).id(), 700L, null, null);
            c.replaceCostLines(List.of(cost(CostType.COURIER_INBOUND, "6000.00", CostBasis.WEIGHT)));

            Outcome outcome = ConsignmentCosting.of(c);

            assertThat(outcome.ready()).isFalse();
            assertThat(outcome.problems()).singleElement().asString()
                    .contains("Cost line 1").contains("weight").contains("line 2");
        }

        @Test
        @DisplayName("once every line has a weight the same cost is shared by it")
        void weightPresent() {
            Consignment c = ringsAndBoxes();
            c.changeLineDetails(c.lines().get(0).id(), 700L, null, null);
            c.changeLineDetails(c.lines().get(1).id(), 5300L, null, null);
            c.replaceCostLines(List.of(cost(CostType.COURIER_INBOUND, "6000.00", CostBasis.WEIGHT)));

            Outcome outcome = ConsignmentCosting.of(c);

            assertThat(outcome.ready()).isTrue();
            assertThat(outcome.lines().get(0).sharedCost()).isEqualByComparingTo("700.00");
            assertThat(outcome.lines().get(1).sharedCost()).isEqualByComparingTo("5300.00");
        }

        @Test
        @DisplayName("duty above zero with nothing typed per line says it must be typed, naming the cost line")
        void directNeedsTyping() {
            Consignment c = ringsAndBoxes();
            c.replaceCostLines(List.of(cost(CostType.DUTY, "210.00")));

            Outcome outcome = ConsignmentCosting.of(c);

            assertThat(outcome.ready()).isFalse();
            assertThat(outcome.problems()).singleElement().asString()
                    .contains("Cost line 1").contains("DUTY").contains("typed");
        }

        @Test
        @DisplayName("typed shares that no longer cover the lines (an order was attached since) are a problem, not silently ignored")
        void staleTypedShares() {
            Consignment c = ringsAndBoxes();
            UUID ring = c.lines().get(0).id();
            UUID box = c.lines().get(1).id();
            c.replaceCostLines(List.of(new CostLineInput(CostType.DUTY, new BigDecimal("210.00"), null, null, null, null,
                    Map.of(ring, new BigDecimal("210.00"), box, new BigDecimal("0.00")))));
            c.attach(order(line("EXTRA", 1, "5.00")));

            Outcome outcome = ConsignmentCosting.of(c);

            assertThat(outcome.ready()).isFalse();
            assertThat(outcome.problems()).singleElement().asString()
                    .contains("Cost line 1").contains("every line");
        }

        @Test
        @DisplayName("totals that the books cannot hold are a problem, not a database error at the moment of receiving")
        void totalsTooLargeToRecord() {
            // Each amount fits its column (ten whole digits); their SUM does not fit the recorded total.
            Consignment c = Consignment.draft(null);
            c.attach(order(line("A", 1, "9000000000.00")));
            c.attach(order(line("B", 1, "9000000000.00")));

            Outcome outcome = ConsignmentCosting.of(c);

            assertThat(outcome.ready()).isFalse();
            assertThat(outcome.problems()).singleElement().asString()
                    .contains("too large").contains("9,999,999,999.99");
            assertThat(outcome.lines()).isEmpty();
        }

        @Test
        @DisplayName("the same for a month's bills, and for goods plus costs when each is within its limit")
        void costTotalsTooLargeToRecord() {
            Consignment c = Consignment.draft(null);
            c.attach(order(line("A", 1, "9000000000.00")));
            c.replaceCostLines(List.of(cost(CostType.FREIGHT, "2000000000.00")));

            Outcome goodsPlusCosts = ConsignmentCosting.of(c);

            assertThat(goodsPlusCosts.ready()).isFalse();
            assertThat(goodsPlusCosts.problems()).singleElement().asString().contains("too large");

            Consignment months = Consignment.draft(null);
            months.attach(order(line("B", 1, "10.00")));
            months.replaceCostLines(List.of(
                    new CostLineInput(CostType.PORT_INCIDENTALS, new BigDecimal("6000000000.00"), CostBasis.UNITS,
                            CostTreatment.PERIOD, null, null, null),
                    new CostLineInput(CostType.PORT_INCIDENTALS, new BigDecimal("6000000000.00"), CostBasis.UNITS,
                            CostTreatment.PERIOD, null, null, null)));

            assertThat(ConsignmentCosting.of(months).problems()).singleElement().asString().contains("too large");
        }

        @Test
        @DisplayName("every problem is reported together, so they can all be fixed at once")
        void allTogether() {
            Consignment c = ringsAndBoxes();
            c.replaceCostLines(List.of(
                    cost(CostType.COURIER_INBOUND, "6000.00", CostBasis.WEIGHT),
                    cost(CostType.DUTY, "210.00")));

            Outcome outcome = ConsignmentCosting.of(c);

            assertThat(outcome.problems()).hasSize(2);
            assertThat(outcome.lines()).isEmpty();
            assertThat(outcome.costs()).as("the costs are still shown, so the screen is not blank").hasSize(2);
        }
    }
}
