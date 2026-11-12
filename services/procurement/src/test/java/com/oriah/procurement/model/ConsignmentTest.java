package com.oriah.procurement.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.oriah.procurement.model.Consignment.CostLineInput;
import com.oriah.procurement.model.Consignment.Header;
import com.oriah.procurement.model.Consignment.OpeningLine;
import com.oriah.procurement.model.PurchaseOrder.NewLine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A consignment: one shipment, built from WHOLE purchase orders (or, for day-one stock,
 * typed in), with the costs shared across it.
 *
 * <p>The rules live in the aggregate, so they hold whoever calls it. A refused input is an
 * IllegalArgumentException (400) and a refused state an IllegalStateException (409); the
 * aggregate, and the purchase order that was being attached, are untouched either way.
 */
class ConsignmentTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 12);

    private static NewLine line(String sku, int quantity, String total) {
        return new NewLine(UUID.randomUUID(), quantity, new BigDecimal(total), sku, "A ring", "A ring - " + sku,
                "SELLABLE");
    }

    private static PurchaseOrder order(UUID supplier, NewLine... lines) {
        return new PurchaseOrder(supplier, TODAY, null, List.of(lines), TODAY);
    }

    private static PurchaseOrder order(NewLine... lines) {
        return order(UUID.randomUUID(), lines);
    }

    private static OpeningLine opening(String sku, int quantity, String goods) {
        return new OpeningLine(UUID.randomUUID(), quantity, new BigDecimal(goods), null, null, null,
                sku, "A ring", "A ring - " + sku, "SELLABLE");
    }

    private static CostLineInput cost(CostType type, String amount) {
        return new CostLineInput(type, new BigDecimal(amount), null, null, null, null, null);
    }

    private static Header header(String note, boolean estimated, ChargesBasis basis) {
        return new Header(note, estimated, basis, null, null, null, null);
    }

    // =====================================================================

    @Nested
    @DisplayName("a new draft")
    class NewDraft {

        @Test
        @DisplayName("is a DRAFT of whole purchase orders, not estimated, with nothing in it yet")
        void startsEmpty() {
            Consignment c = Consignment.draft("  Fycargo, 3 boxes ");

            assertThat(c.status()).isEqualTo(ConsignmentStatus.DRAFT);
            assertThat(c.isOpening()).isFalse();
            assertThat(c.isEstimated()).isFalse();
            assertThat(c.note()).isEqualTo("Fycargo, 3 boxes");
            assertThat(c.lines()).isEmpty();
            assertThat(c.costLines()).isEmpty();
            assertThat(c.chargesBasis()).as("no default: recorded from the forwarder's invoice").isNull();
            assertThat(c.receivedAt()).isNull();
        }

        @Test
        @DisplayName("a note over 500 characters or with a NUL is refused")
        void noteIsChecked() {
            assertThatThrownBy(() -> Consignment.draft("x".repeat(501))).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> Consignment.draft("a\0b")).isInstanceOf(IllegalArgumentException.class);
        }
    }

    // =====================================================================

    @Nested
    @DisplayName("attaching purchase orders")
    class Attaching {

        @Test
        @DisplayName("copies each order line onto the consignment, with its supplier, its names and its all-in total as the goods cost")
        void copiesTheLines() {
            UUID dubai = UUID.randomUUID();
            UUID hongKong = UUID.randomUUID();
            PurchaseOrder rings = order(dubai, line("RING-M", 7, "10500.00"));
            PurchaseOrder boxes = order(hongKong, line("BOX-1", 200, "10000.00"));
            Consignment c = Consignment.draft(null);

            c.attach(rings);
            c.attach(boxes);

            assertThat(c.lines()).extracting(ConsignmentLine::position).containsExactly(1, 2);
            ConsignmentLine first = c.lines().get(0);
            assertThat(first.supplierId()).isEqualTo(dubai);
            assertThat(first.purchaseOrderLineId()).isEqualTo(rings.lines().get(0).id());
            assertThat(first.variantId()).isEqualTo(rings.lines().get(0).variantId());
            assertThat(first.sku()).isEqualTo("RING-M");
            assertThat(first.variantTitle()).isEqualTo("A ring - RING-M");
            assertThat(first.kind()).isEqualTo("SELLABLE");
            assertThat(first.quantity()).isEqualTo(7);
            assertThat(first.goodsTotal()).isEqualByComparingTo("10500.00");
            assertThat(first.isOpening()).isFalse();
            assertThat(c.lines().get(1).supplierId()).isEqualTo(hongKong);
            assertThat(c.lines().get(1).quantity()).isEqualTo(200);
        }

        @Test
        @DisplayName("freezes the order: it now says which consignment it is in, and can no longer be edited or cancelled")
        void freezesTheOrder() {
            PurchaseOrder po = order(line("A", 1, "10.00"));
            Consignment c = Consignment.draft(null);

            c.attach(po);

            assertThat(po.consignmentId()).isEqualTo(c.id());
            assertThat(po.isAttached()).isTrue();
            assertThatThrownBy(po::cancel).isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("the same variant on two orders stays two lines: cost belongs to a receipt, and the prices differ")
        void sameVariantTwoOrdersTwoLines() {
            NewLine ring = line("RING-M", 2, "100.00");
            PurchaseOrder a = order(ring);
            PurchaseOrder b = order(new NewLine(ring.variantId(), 3, new BigDecimal("180.00"), "RING-M", "A ring",
                    "A ring - RING-M", "SELLABLE"));
            Consignment c = Consignment.draft(null);

            c.attach(a);
            c.attach(b);

            assertThat(c.lines()).hasSize(2);
            assertThat(c.lines()).extracting(ConsignmentLine::goodsTotal)
                    .usingElementComparator(BigDecimal::compareTo)
                    .containsExactly(new BigDecimal("100.00"), new BigDecimal("180.00"));
        }

        @Test
        @DisplayName("an order that is cancelled, already in a consignment, or already here is refused, and nothing changes")
        void refusesOrdersThatCannotJoin() {
            PurchaseOrder cancelled = order(line("A", 1, "10.00"));
            cancelled.cancel();
            PurchaseOrder elsewhere = order(line("B", 1, "10.00"));
            Consignment other = Consignment.draft(null);
            other.attach(elsewhere);
            PurchaseOrder here = order(line("C", 1, "10.00"));
            Consignment c = Consignment.draft(null);
            c.attach(here);

            assertThatThrownBy(() -> c.attach(cancelled)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> c.attach(elsewhere)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> c.attach(here)).isInstanceOf(IllegalStateException.class);

            assertThat(c.lines()).hasSize(1);
            assertThat(elsewhere.consignmentId()).isEqualTo(other.id());
            assertThat(cancelled.isAttached()).isFalse();
        }

        @Test
        @DisplayName("the opening consignment takes no purchase orders: its stock has no order behind it")
        void openingTakesNoOrders() {
            Consignment opening = Consignment.opening(null, List.of(opening("A", 1, "10.00")));

            assertThatThrownBy(() -> opening.attach(order(line("B", 1, "10.00"))))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("opening");
        }

        @Test
        @DisplayName("a consignment holds at most 20 orders and 200 lines")
        void caps() {
            Consignment c = Consignment.draft(null);
            for (int i = 0; i < 20; i++) {
                c.attach(order(line("S" + i, 1, "10.00")));
            }
            assertThatThrownBy(() -> c.attach(order(line("X", 1, "10.00"))))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("20");

            Consignment big = Consignment.draft(null);
            for (int i = 0; i < 2; i++) {
                List<NewLine> hundred = new ArrayList<>();
                for (int j = 0; j < 100; j++) {
                    hundred.add(line("B" + i + "-" + j, 1, "1.00"));
                }
                big.attach(order(hundred.toArray(NewLine[]::new)));
            }
            assertThat(big.lines()).hasSize(200);
            PurchaseOrder one = order(line("ONE-TOO-MANY", 1, "1.00"));
            assertThatThrownBy(() -> big.attach(one))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("200");
            assertThat(one.isAttached()).as("a refused order is left alone").isFalse();
        }

        @Test
        @DisplayName("a received consignment takes no more orders")
        void receivedTakesNoOrders() {
            Consignment c = Consignment.draft(null);
            c.attach(order(line("A", 1, "10.00")));
            c.markReceived(java.time.Instant.parse("2026-11-12T10:00:00Z"));

            assertThatThrownBy(() -> c.attach(order(line("B", 1, "10.00")))).isInstanceOf(IllegalStateException.class);
        }
    }

    // =====================================================================

    @Nested
    @DisplayName("detaching purchase orders")
    class Detaching {

        @Test
        @DisplayName("removes that order's lines, renumbers the rest from 1, and sets the order free")
        void removesItsLinesAndFreesIt() {
            PurchaseOrder a = order(line("A1", 1, "10.00"), line("A2", 1, "10.00"));
            PurchaseOrder b = order(line("B1", 1, "10.00"));
            Consignment c = Consignment.draft(null);
            c.attach(a);
            c.attach(b);

            c.detach(a);

            assertThat(c.lines()).extracting(ConsignmentLine::sku).containsExactly("B1");
            assertThat(c.lines()).extracting(ConsignmentLine::position).containsExactly(1);
            assertThat(a.isAttached()).isFalse();
            a.cancel();
        }

        @Test
        @DisplayName("takes the typed shares of the removed lines with it, so none points at a line that is gone")
        void dropsTheSharesOfRemovedLines() {
            PurchaseOrder a = order(line("A", 1, "10.00"));
            PurchaseOrder b = order(line("B", 1, "10.00"));
            Consignment c = Consignment.draft(null);
            c.attach(a);
            c.attach(b);
            UUID lineA = c.lines().get(0).id();
            UUID lineB = c.lines().get(1).id();
            Map<UUID, BigDecimal> shares = new LinkedHashMap<>();
            shares.put(lineA, new BigDecimal("3.00"));
            shares.put(lineB, new BigDecimal("2.00"));
            c.replaceCostLines(List.of(new CostLineInput(CostType.DUTY, new BigDecimal("5.00"), null, null, null, null, shares)));

            c.detach(a);

            assertThat(c.costLines().get(0).shares()).containsOnlyKeys(lineB);
        }

        @Test
        @DisplayName("an order that is not in this consignment cannot be detached from it")
        void refusesAStranger() {
            Consignment c = Consignment.draft(null);
            c.attach(order(line("A", 1, "10.00")));
            PurchaseOrder stranger = order(line("B", 1, "10.00"));

            assertThatThrownBy(() -> c.detach(stranger)).isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("a received consignment keeps its orders")
        void receivedKeepsItsOrders() {
            PurchaseOrder a = order(line("A", 1, "10.00"));
            Consignment c = Consignment.draft(null);
            c.attach(a);
            c.markReceived(java.time.Instant.parse("2026-11-12T10:00:00Z"));

            assertThatThrownBy(() -> c.detach(a)).isInstanceOf(IllegalStateException.class);
        }
    }

    // =====================================================================

    @Nested
    @DisplayName("the opening consignment")
    class Opening {

        @Test
        @DisplayName("is typed in: no supplier, no order, always an estimate, one line per variant")
        void isTypedIn() {
            Consignment c = Consignment.opening("Day-one stock, from the old book",
                    List.of(opening("RING-M", 7, "10000.00"), opening("BOX-1", 200, "9000.00")));

            assertThat(c.isOpening()).isTrue();
            assertThat(c.isEstimated()).isTrue();
            assertThat(c.status()).isEqualTo(ConsignmentStatus.DRAFT);
            assertThat(c.lines()).extracting(ConsignmentLine::position).containsExactly(1, 2);
            assertThat(c.lines()).allSatisfy(l -> {
                assertThat(l.supplierId()).isNull();
                assertThat(l.purchaseOrderLineId()).isNull();
                assertThat(l.isOpening()).isTrue();
            });
            assertThat(c.lines().get(0).goodsTotal()).isEqualByComparingTo("10000.00");
        }

        @Test
        @DisplayName("a K0 cost is refused: the client approximates every piece from the old book")
        void zeroCostIsRefused() {
            assertThatThrownBy(() -> Consignment.opening(null, List.of(opening("A", 1, "0.00"))))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("above zero");
        }

        @Test
        @DisplayName("needs a line, at most 200, a quantity of at least one, a distinct variant each, and whole ngwee")
        void lineRules() {
            assertThatThrownBy(() -> Consignment.opening(null, List.of()))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> Consignment.opening(null, List.of(opening("A", 0, "1.00"))))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("quantity");
            assertThatThrownBy(() -> Consignment.opening(null, List.of(opening("A", 1, "1.005"))))
                    .isInstanceOf(IllegalArgumentException.class);
            OpeningLine a = opening("A", 1, "1.00");
            assertThatThrownBy(() -> Consignment.opening(null, List.of(a, a)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("more than once");
            List<OpeningLine> many = new ArrayList<>();
            for (int i = 0; i < 201; i++) {
                many.add(opening("S" + i, 1, "1.00"));
            }
            assertThatThrownBy(() -> Consignment.opening(null, many))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("200");
        }

        @Test
        @DisplayName("its lines are replaced as a set; a normal consignment's are not typed, so they cannot be")
        void replacingLines() {
            Consignment c = Consignment.opening(null, List.of(opening("A", 1, "10.00")));
            c.replaceOpeningLines(List.of(opening("B", 2, "20.00"), opening("C", 3, "30.00")));

            assertThat(c.lines()).extracting(ConsignmentLine::sku).containsExactly("B", "C");
            assertThat(c.lines()).extracting(ConsignmentLine::position).containsExactly(1, 2);
            assertThatThrownBy(() -> Consignment.draft(null).replaceOpeningLines(List.of(opening("A", 1, "1.00"))))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("it cannot be set to 'not an estimate'")
        void staysAnEstimate() {
            Consignment c = Consignment.opening(null, List.of(opening("A", 1, "10.00")));

            assertThatThrownBy(() -> c.changeHeader(header(null, false, null)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("estimate");
        }
    }

    // =====================================================================

    @Nested
    @DisplayName("the header")
    class HeaderRules {

        @Test
        @DisplayName("records the customs facts exactly as keyed, tidied: capital letters on the form and agent, trimmed text")
        void recordsCustomsFacts() {
            Consignment c = Consignment.draft(null);

            boolean changed = c.changeHeader(new Header(" Hand-carried ", true, ChargesBasis.FREIGHT_ONLY,
                    " Fycargo Clearing ", ClearanceForm.CE6, " ASM-123 ", Boolean.TRUE));

            assertThat(changed).isTrue();
            assertThat(c.note()).isEqualTo("Hand-carried");
            assertThat(c.isEstimated()).isTrue();
            assertThat(c.chargesBasis()).isEqualTo(ChargesBasis.FREIGHT_ONLY);
            assertThat(c.declarant()).isEqualTo("Fycargo Clearing");
            assertThat(c.clearanceForm()).isEqualTo(ClearanceForm.CE6);
            assertThat(c.zraAssessmentRef()).isEqualTo("ASM-123");
            assertThat(c.licensedAgentUsed()).isTrue();
        }

        @Test
        @DisplayName("says whether anything changed, so a form saved untouched does not move the version")
        void saysWhetherAnythingChanged() {
            Consignment c = Consignment.draft("n");

            assertThat(c.changeHeader(header("n", false, null))).isFalse();
            assertThat(c.changeHeader(header("m", false, null))).isTrue();
            assertThat(c.changeHeader(header("m", false, null))).isFalse();
        }

        @Test
        @DisplayName("a blank clears a field; an over-long one is refused")
        void blankClears() {
            Consignment c = Consignment.draft("n");
            c.changeHeader(new Header("n", false, ChargesBasis.DUTY_PAID, "Agent", ClearanceForm.CE20, "R-1", false));

            c.changeHeader(new Header("  ", false, null, "   ", null, "", null));

            assertThat(c.note()).isNull();
            assertThat(c.chargesBasis()).isNull();
            assertThat(c.declarant()).isNull();
            assertThat(c.clearanceForm()).isNull();
            assertThat(c.zraAssessmentRef()).isNull();
            assertThat(c.licensedAgentUsed()).isNull();
            assertThatThrownBy(() -> c.changeHeader(new Header(null, false, null, "x".repeat(121), null, null, null)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> c.changeHeader(new Header(null, false, null, null, null, "x".repeat(81), null)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("DUTY_PAID says the forwarder's price already includes duty, so a duty, surtax or import VAT line above zero contradicts it")
        void dutyPaidContradictsDutyLines() {
            Consignment c = Consignment.draft(null);
            c.attach(order(line("A", 1, "10.00")));
            c.replaceCostLines(List.of(cost(CostType.DUTY, "50.00")));

            assertThatThrownBy(() -> c.changeHeader(header(null, false, ChargesBasis.DUTY_PAID)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("duty");
            assertThat(c.chargesBasis()).as("a refused change changes nothing").isNull();
            c.changeHeader(header(null, false, ChargesBasis.FREIGHT_ONLY));
        }

        @Test
        @DisplayName("a received consignment's header is frozen")
        void receivedIsFrozen() {
            Consignment c = Consignment.draft(null);
            c.attach(order(line("A", 1, "10.00")));
            c.markReceived(java.time.Instant.parse("2026-11-12T10:00:00Z"));

            assertThatThrownBy(() -> c.changeHeader(header("x", false, null))).isInstanceOf(IllegalStateException.class);
        }
    }

    // =====================================================================

    @Nested
    @DisplayName("what a line records about the goods")
    class LineDetails {

        private Consignment withALine() {
            Consignment c = Consignment.draft(null);
            c.attach(order(line("A", 1, "10.00")));
            return c;
        }

        @Test
        @DisplayName("the chargeable weight in whole grams, the country the goods came from and the HS code, tidied")
        void recordsDetails() {
            Consignment c = withALine();
            UUID id = c.lines().get(0).id();

            c.changeLineDetails(id, 1250L, " cn ", " 7113.19.00 ");

            ConsignmentLine l = c.lines().get(0);
            assertThat(l.chargeableWeightGrams()).isEqualTo(1250L);
            assertThat(l.originCountry()).isEqualTo("CN");
            assertThat(l.hsCode()).isEqualTo("7113.19.00");
        }

        @Test
        @DisplayName("nothing sent clears them")
        void blankClears() {
            Consignment c = withALine();
            UUID id = c.lines().get(0).id();
            c.changeLineDetails(id, 10L, "CN", "7113");

            c.changeLineDetails(id, null, " ", "");

            ConsignmentLine l = c.lines().get(0);
            assertThat(l.chargeableWeightGrams()).isNull();
            assertThat(l.originCountry()).isNull();
            assertThat(l.hsCode()).isNull();
        }

        @Test
        @DisplayName("a negative weight, a country that is not two letters, an HS code that is not digits, or a line that is not here is refused")
        void refusals() {
            Consignment c = withALine();
            UUID id = c.lines().get(0).id();

            assertThatThrownBy(() -> c.changeLineDetails(id, -1L, null, null)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> c.changeLineDetails(id, null, "CHN", null)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> c.changeLineDetails(id, null, "1A", null)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> c.changeLineDetails(id, null, null, "gold")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> c.changeLineDetails(UUID.randomUUID(), null, null, null))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no such line");
        }
    }

    // =====================================================================

    @Nested
    @DisplayName("cost lines")
    class CostLines {

        private Consignment withTwoLines() {
            Consignment c = Consignment.draft(null);
            c.attach(order(line("RING", 7, "10500.00")));
            c.attach(order(line("BOX", 200, "10000.00")));
            return c;
        }

        @Test
        @DisplayName("the courier's one charge is capitalized and shared by units unless said otherwise")
        void defaults() {
            Consignment c = withTwoLines();

            c.replaceCostLines(List.of(cost(CostType.COURIER_INBOUND, "6000.00"),
                    cost(CostType.AGENT_FEE, "100.00"),
                    cost(CostType.INBOUND_TRANSPORT, "50.00")));

            assertThat(c.costLines()).extracting(CostLine::position).containsExactly(1, 2, 3);
            assertThat(c.costLines()).extracting(CostLine::basis)
                    .containsExactly(CostBasis.UNITS, CostBasis.VALUE, CostBasis.UNITS);
            assertThat(c.costLines()).extracting(CostLine::treatment)
                    .containsOnly(CostTreatment.CAPITALIZE);
            assertThat(c.costLines().get(0).amount()).isEqualByComparingTo("6000.00");
        }

        @Test
        @DisplayName("an explicit basis wins over the default, and the types with no default must be given one")
        void explicitBasisAndNoDefault() {
            Consignment c = withTwoLines();

            c.replaceCostLines(List.of(new CostLineInput(CostType.COURIER_INBOUND, new BigDecimal("6000.00"),
                    CostBasis.VALUE, null, null, null, null)));
            assertThat(c.costLines().get(0).basis()).isEqualTo(CostBasis.VALUE);

            assertThatThrownBy(() -> c.replaceCostLines(List.of(cost(CostType.INSURANCE, "10.00"))))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("basis");
            assertThatThrownBy(() -> c.replaceCostLines(List.of(cost(CostType.PORT_INCIDENTALS, "10.00"))))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("basis");
            assertThat(c.costLines()).as("a refused replacement changes nothing").hasSize(1);
        }

        @Test
        @DisplayName("duty, surtax and import VAT are typed per line, never pooled and spread: any other basis is refused")
        void dutiesAreDirect() {
            Consignment c = withTwoLines();

            for (CostType type : List.of(CostType.DUTY, CostType.SURTAX, CostType.IMPORT_VAT)) {
                assertThat(type.defaultBasis()).isEqualTo(CostBasis.DIRECT);
                assertThatThrownBy(() -> c.replaceCostLines(List.of(new CostLineInput(type, new BigDecimal("10.00"),
                        CostBasis.VALUE, null, null, null, null))))
                        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("DIRECT");
            }
            c.replaceCostLines(List.of(cost(CostType.DUTY, "0.00"), cost(CostType.SURTAX, "0.00"),
                    cost(CostType.IMPORT_VAT, "0.00")));
            assertThat(c.costLines()).extracting(CostLine::basis).containsOnly(CostBasis.DIRECT);
        }

        @Test
        @DisplayName("an amount is whole ngwee and not negative; zero is allowed, because duty reads zero today")
        void amounts() {
            Consignment c = withTwoLines();

            assertThatThrownBy(() -> c.replaceCostLines(List.of(cost(CostType.FREIGHT, "-1.00"))))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> c.replaceCostLines(List.of(cost(CostType.FREIGHT, "1.005"))))
                    .isInstanceOf(IllegalArgumentException.class);
            c.replaceCostLines(List.of(cost(CostType.FREIGHT, "0.00")));
            assertThat(c.costLines()).hasSize(1);
        }

        @Test
        @DisplayName("at most 30 cost lines")
        void cap() {
            Consignment c = withTwoLines();
            List<CostLineInput> thirtyOne = new ArrayList<>();
            for (int i = 0; i < 31; i++) {
                thirtyOne.add(cost(CostType.FREIGHT, "1.00"));
            }

            assertThatThrownBy(() -> c.replaceCostLines(thirtyOne))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("30");
            c.replaceCostLines(thirtyOne.subList(0, 30));
        }

        @Test
        @DisplayName("replacing with none clears them all")
        void clears() {
            Consignment c = withTwoLines();
            c.replaceCostLines(List.of(cost(CostType.FREIGHT, "1.00")));

            c.replaceCostLines(List.of());

            assertThat(c.costLines()).isEmpty();
        }

        @Test
        @DisplayName("a PERIOD cost is a bill for the month, shown here but not shared: it cannot carry typed shares")
        void periodCostsAreNotShared() {
            Consignment c = withTwoLines();
            UUID ring = c.lines().get(0).id();

            c.replaceCostLines(List.of(new CostLineInput(CostType.PORT_INCIDENTALS, new BigDecimal("40.00"),
                    CostBasis.UNITS, CostTreatment.PERIOD, LocalDate.of(2026, 11, 10), "Storage, not part of the goods", null)));
            assertThat(c.costLines().get(0).treatment()).isEqualTo(CostTreatment.PERIOD);
            assertThat(c.costLines().get(0).incurredOn()).isEqualTo(LocalDate.of(2026, 11, 10));
            assertThat(c.costLines().get(0).note()).isEqualTo("Storage, not part of the goods");

            assertThatThrownBy(() -> c.replaceCostLines(List.of(new CostLineInput(CostType.PORT_INCIDENTALS,
                    new BigDecimal("40.00"), CostBasis.UNITS, CostTreatment.PERIOD, null, null,
                    Map.of(ring, new BigDecimal("40.00"))))))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("shared");
        }

        @Test
        @DisplayName("typed shares must name only this consignment's lines, none negative")
        void sharesNameThisConsignmentsLines() {
            Consignment c = withTwoLines();
            UUID ring = c.lines().get(0).id();

            assertThatThrownBy(() -> c.replaceCostLines(List.of(new CostLineInput(CostType.DUTY, new BigDecimal("5.00"),
                    null, null, null, null, Map.of(UUID.randomUUID(), new BigDecimal("5.00"))))))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no such line");
            assertThatThrownBy(() -> c.replaceCostLines(List.of(new CostLineInput(CostType.DUTY, new BigDecimal("5.00"),
                    null, null, null, null, Map.of(ring, new BigDecimal("-1.00"))))))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("typed shares must cover every line and add up to the cost exactly, and say by how much they are out")
        void sharesAddUpExactly() {
            Consignment c = withTwoLines();
            UUID ring = c.lines().get(0).id();
            UUID box = c.lines().get(1).id();

            assertThatThrownBy(() -> c.replaceCostLines(List.of(new CostLineInput(CostType.DUTY, new BigDecimal("5.00"),
                    null, null, null, null, Map.of(ring, new BigDecimal("5.00"))))))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("every line");
            Map<UUID, BigDecimal> short1 = new LinkedHashMap<>();
            short1.put(ring, new BigDecimal("3.00"));
            short1.put(box, new BigDecimal("1.99"));
            assertThatThrownBy(() -> c.replaceCostLines(List.of(new CostLineInput(CostType.DUTY, new BigDecimal("5.00"),
                    null, null, null, null, short1))))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("0.01");
            Map<UUID, BigDecimal> exact = new LinkedHashMap<>();
            exact.put(ring, new BigDecimal("3.00"));
            exact.put(box, new BigDecimal("2.00"));
            c.replaceCostLines(List.of(new CostLineInput(CostType.DUTY, new BigDecimal("5.00"), null, null, null, null, exact)));
            assertThat(c.costLines().get(0).shares()).containsOnlyKeys(ring, box);
        }

        @Test
        @DisplayName("a duty line of zero needs no shares: nothing to type when nothing was charged")
        void zeroDutyNeedsNoShares() {
            Consignment c = withTwoLines();

            c.replaceCostLines(List.of(cost(CostType.DUTY, "0.00")));

            assertThat(c.costLines().get(0).shares()).isEmpty();
        }

        @Test
        @DisplayName("a duty, surtax or import VAT line above zero is refused while the charges basis says DUTY_PAID")
        void dutyPaidRefusesDutyLines() {
            Consignment c = withTwoLines();
            c.changeHeader(header(null, false, ChargesBasis.DUTY_PAID));
            UUID ring = c.lines().get(0).id();
            UUID box = c.lines().get(1).id();

            assertThatThrownBy(() -> c.replaceCostLines(List.of(new CostLineInput(CostType.IMPORT_VAT,
                    new BigDecimal("5.00"), null, null, null, null,
                    Map.of(ring, new BigDecimal("3.00"), box, new BigDecimal("2.00"))))))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("DUTY_PAID");
            c.replaceCostLines(List.of(cost(CostType.DUTY, "0.00"), cost(CostType.COURIER_INBOUND, "6000.00")));
        }

        @Test
        @DisplayName("a received consignment's cost lines are frozen")
        void receivedIsFrozen() {
            Consignment c = withTwoLines();
            c.markReceived(java.time.Instant.parse("2026-11-12T10:00:00Z"));

            assertThatThrownBy(() -> c.replaceCostLines(List.of(cost(CostType.FREIGHT, "1.00"))))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    // =====================================================================

    @Nested
    @DisplayName("who typed a split: every override records the actor")
    class TypedSplitsAreAttributed {

        private static final java.time.Instant FIRST = java.time.Instant.parse("2026-11-12T08:00:00Z");
        private static final java.time.Instant LATER = java.time.Instant.parse("2026-11-12T09:30:00Z");

        private Consignment withTwoLines() {
            Consignment c = Consignment.draft(null);
            c.attach(order(line("RING", 7, "10500.00")));
            c.attach(order(line("BOX", 200, "10000.00")));
            return c;
        }

        private CostLineInput duty(Consignment c, String ring, String box) {
            Map<UUID, BigDecimal> shares = new LinkedHashMap<>();
            shares.put(c.lines().get(0).id(), new BigDecimal(ring));
            shares.put(c.lines().get(1).id(), new BigDecimal(box));
            return new CostLineInput(CostType.DUTY, new BigDecimal(ring).add(new BigDecimal(box)), null, null, null, null, shares);
        }

        @Test
        @DisplayName("a typed split is stamped with who typed it and when; a cost shared by a rule is not stamped at all")
        void stamping() {
            Consignment c = withTwoLines();
            c.replaceCostLines(List.of(cost(CostType.COURIER_INBOUND, "6000.00"), duty(c, "3.00", "2.00")));

            c.stampNewlyTyped("kc-maya", FIRST);

            assertThat(c.costLines().get(0).typedBy()).as("the algorithm shared it: nobody typed it").isNull();
            assertThat(c.costLines().get(0).typedAt()).isNull();
            assertThat(c.costLines().get(1).typedBy()).isEqualTo("kc-maya");
            assertThat(c.costLines().get(1).typedAt()).isEqualTo(FIRST);
        }

        @Test
        @DisplayName("saving the cost list again with that split unchanged keeps who typed it, however many other lines change")
        void aResaveKeepsTheFirstTypist() {
            Consignment c = withTwoLines();
            c.replaceCostLines(List.of(duty(c, "3.00", "2.00")));
            c.stampNewlyTyped("kc-maya", FIRST);

            // Someone else saves the list again, adding a line and re-sending the same split.
            c.replaceCostLines(List.of(cost(CostType.COURIER_INBOUND, "6000.00"), duty(c, "3.00", "2.00")));
            c.stampNewlyTyped("kc-zoe", LATER);

            assertThat(c.costLines().get(1).typedBy()).as("the split was Maya's, and still is").isEqualTo("kc-maya");
            assertThat(c.costLines().get(1).typedAt()).isEqualTo(FIRST);
            assertThat(c.costLines().get(0).typedBy()).isNull();
        }

        @Test
        @DisplayName("changing the split makes the person who changed it the typist")
        void aChangedSplitIsTheChangersAlone() {
            Consignment c = withTwoLines();
            c.replaceCostLines(List.of(duty(c, "3.00", "2.00")));
            c.stampNewlyTyped("kc-maya", FIRST);

            c.replaceCostLines(List.of(duty(c, "4.00", "1.00")));
            c.stampNewlyTyped("kc-zoe", LATER);

            assertThat(c.costLines().get(0).typedBy()).isEqualTo("kc-zoe");
            assertThat(c.costLines().get(0).typedAt()).isEqualTo(LATER);
        }

        @Test
        @DisplayName("a typed split that is replaced by a rule, or removed, has no typist left")
        void aRemovedSplitHasNoTypist() {
            Consignment c = withTwoLines();
            c.replaceCostLines(List.of(duty(c, "3.00", "2.00")));
            c.stampNewlyTyped("kc-maya", FIRST);

            c.replaceCostLines(List.of(cost(CostType.COURIER_INBOUND, "6000.00")));
            c.stampNewlyTyped("kc-zoe", LATER);

            assertThat(c.costLines().get(0).typedBy()).isNull();
        }

        @Test
        @DisplayName("stamping again changes nothing that is already stamped")
        void stampingIsOnlyForTheUnstamped() {
            Consignment c = withTwoLines();
            c.replaceCostLines(List.of(duty(c, "3.00", "2.00")));
            c.stampNewlyTyped("kc-maya", FIRST);

            c.stampNewlyTyped("kc-zoe", LATER);

            assertThat(c.costLines().get(0).typedBy()).isEqualTo("kc-maya");
        }

        @Test
        @DisplayName("a consignment with a typed split nobody is recorded as typing cannot be received")
        void anUnattributedSplitBlocksReceiving() {
            Consignment c = withTwoLines();
            c.replaceCostLines(List.of(duty(c, "3.00", "2.00")));

            assertThatThrownBy(() -> c.markReceived(java.time.Instant.parse("2026-11-12T10:00:00Z")))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("who typed");
            c.stampNewlyTyped("kc-maya", FIRST);
            c.markReceived(java.time.Instant.parse("2026-11-12T10:00:00Z"));
        }

        @Test
        @DisplayName("a typist must be named")
        void aTypistMustBeNamed() {
            Consignment c = withTwoLines();
            c.replaceCostLines(List.of(duty(c, "3.00", "2.00")));

            assertThatThrownBy(() -> c.stampNewlyTyped(null, FIRST)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> c.stampNewlyTyped("kc-maya", null)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("the closed list of cost types")
    class CostTypes {

        @Test
        @DisplayName("a type is read by its name, in any letter case")
        void parses() {
            assertThat(CostType.parse("courier_inbound")).isEqualTo(CostType.COURIER_INBOUND);
            assertThat(CostType.parse(" Import_VAT ")).isEqualTo(CostType.IMPORT_VAT);
        }

        @Test
        @DisplayName("a bank or transfer charge is refused with the reason: the supplier price already is the total that left the bank")
        void refusesBankCharges() {
            for (String name : List.of("BANK_CHARGE", "bank fee", "TRANSFER_FEE", "wire transfer", "Bank")) {
                assertThatThrownBy(() -> CostType.parse(name))
                        .as(name)
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("already is the total that left the bank");
            }
        }

        @Test
        @DisplayName("anything else not on the list is refused and the list is shown")
        void refusesUnknown() {
            assertThatThrownBy(() -> CostType.parse("SNACKS"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("COURIER_INBOUND").hasMessageContaining("IMPORT_VAT");
            assertThatThrownBy(() -> CostType.parse(null)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("each type has the basis it normally shares by: the courier's charge by units, an agent's flat fee by value")
        void defaultBases() {
            assertThat(CostType.COURIER_INBOUND.defaultBasis()).isEqualTo(CostBasis.UNITS);
            assertThat(CostType.FREIGHT.defaultBasis()).isEqualTo(CostBasis.UNITS);
            assertThat(CostType.INBOUND_TRANSPORT.defaultBasis()).isEqualTo(CostBasis.UNITS);
            assertThat(CostType.AGENT_FEE.defaultBasis()).isEqualTo(CostBasis.VALUE);
            assertThat(CostType.INSURANCE.defaultBasis()).isNull();
            assertThat(CostType.PORT_INCIDENTALS.defaultBasis()).isNull();
        }
    }
}
