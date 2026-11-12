package com.oriah.procurement.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.oriah.procurement.model.PurchaseOrder.NewLine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A purchase order: what Oriah placed with one supplier, one line per item, each with
 * how many and the all-in total that left the bank.
 *
 * <p>The rules live in the aggregate, so they hold whoever calls it: the API, a test,
 * a later job. A refused input is an IllegalArgumentException (the shared error
 * contract renders it 400) and a refused STATE is an IllegalStateException (409);
 * the aggregate is left exactly as it was either way.
 */
class PurchaseOrderTest {

    private static final UUID SUPPLIER = UUID.randomUUID();
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 12);

    private static NewLine line(String sku, int quantity, String total) {
        return new NewLine(UUID.randomUUID(), quantity, new BigDecimal(total), sku, "A ring", "A ring - M", "SELLABLE");
    }

    private static PurchaseOrder order(NewLine... lines) {
        return new PurchaseOrder(SUPPLIER, TODAY, "note", List.of(lines), TODAY);
    }

    @Nested
    @DisplayName("placing an order")
    class Placing {

        @Test
        @DisplayName("an order starts OPEN, unattached, with its lines numbered in the order given")
        void startsOpen() {
            PurchaseOrder po = order(line("A", 7, "10500.00"), line("B", 200, "10000.00"));

            assertThat(po.status()).isEqualTo(PurchaseOrderStatus.OPEN);
            assertThat(po.supplierId()).isEqualTo(SUPPLIER);
            assertThat(po.isAttached()).isFalse();
            assertThat(po.lines()).extracting(PurchaseOrderLine::position).containsExactly(1, 2);
            assertThat(po.lines()).extracting(PurchaseOrderLine::sku).containsExactly("A", "B");
            assertThat(po.totalPaid()).isEqualByComparingTo("20500.00");
        }

        @Test
        @DisplayName("an order with no lines is refused: there is nothing to buy")
        void needsALine() {
            assertThatThrownBy(() -> new PurchaseOrder(SUPPLIER, TODAY, null, List.of(), TODAY))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("at least one line");
        }

        @Test
        @DisplayName("an order holds at most 100 lines")
        void capped() {
            List<NewLine> hundred = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                hundred.add(line("S" + i, 1, "1.00"));
            }
            new PurchaseOrder(SUPPLIER, TODAY, null, hundred, TODAY);

            hundred.add(line("S100", 1, "1.00"));
            assertThatThrownBy(() -> new PurchaseOrder(SUPPLIER, TODAY, null, hundred, TODAY))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("at most 100");
        }

        @Test
        @DisplayName("every line needs at least one unit and a total paid above zero, to the ngwee")
        void lineAmounts() {
            assertThatThrownBy(() -> order(line("A", 0, "10.00")))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("quantity");
            assertThatThrownBy(() -> order(line("A", 1, "0.00")))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("total paid");
            assertThatThrownBy(() -> order(line("A", 1, "-1.00")))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("total paid");
            assertThatThrownBy(() -> order(line("A", 1, "10.005")))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("total paid");
        }

        @Test
        @DisplayName("the same variant twice on one order is refused: one line per item")
        void oneLinePerVariant() {
            UUID variant = UUID.randomUUID();
            NewLine a = new NewLine(variant, 1, new BigDecimal("5.00"), "A", "t", "t", "SELLABLE");
            NewLine b = new NewLine(variant, 2, new BigDecimal("6.00"), "A", "t", "t", "SELLABLE");

            assertThatThrownBy(() -> order(a, b))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("more than once");
        }

        @Test
        @DisplayName("an order cannot be dated in the future, and a note cannot hold a NUL")
        void headerRules() {
            assertThatThrownBy(() -> new PurchaseOrder(SUPPLIER, TODAY.plusDays(1), null, List.of(line("A", 1, "1.00")), TODAY))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("future");
            assertThatThrownBy(() -> new PurchaseOrder(SUPPLIER, TODAY, "a\u0000b", List.of(line("A", 1, "1.00")), TODAY))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("NUL");
            assertThatThrownBy(() -> new PurchaseOrder(SUPPLIER, TODAY, "x".repeat(501), List.of(line("A", 1, "1.00")), TODAY))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("500");
            assertThat(new PurchaseOrder(SUPPLIER, null, null, List.of(line("A", 1, "1.00")), TODAY).placedOn()).isEqualTo(TODAY);
        }

        @Test
        @DisplayName("a supplier is required, and the kind of a line must be SELLABLE or PACKAGING")
        void supplierAndKind() {
            assertThatThrownBy(() -> new PurchaseOrder(null, TODAY, null, List.of(line("A", 1, "1.00")), TODAY))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("supplier");
            assertThatThrownBy(() -> order(new NewLine(UUID.randomUUID(), 1, BigDecimal.ONE, "A", "t", "t", "SERVICE")))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("kind");
        }
    }

    @Nested
    @DisplayName("changing an open order")
    class Changing {

        @Test
        @DisplayName("the lines can be replaced, and are numbered afresh")
        void replaceLines() {
            PurchaseOrder po = order(line("A", 1, "1.00"));

            po.replaceLines(List.of(line("X", 2, "5.00"), line("Y", 3, "6.00")));

            assertThat(po.lines()).extracting(PurchaseOrderLine::sku).containsExactly("X", "Y");
            assertThat(po.lines()).extracting(PurchaseOrderLine::position).containsExactly(1, 2);
            assertThat(po.totalPaid()).isEqualByComparingTo("11.00");
        }

        @Test
        @DisplayName("a refused replacement leaves the order as it was")
        void refusedReplacementChangesNothing() {
            PurchaseOrder po = order(line("A", 1, "1.00"));

            assertThatThrownBy(() -> po.replaceLines(List.of(line("X", 0, "5.00"))))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> po.replaceLines(List.of()))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThat(po.lines()).extracting(PurchaseOrderLine::sku).containsExactly("A");
        }

        @Test
        @DisplayName("the date and the note can be changed")
        void header() {
            PurchaseOrder po = order(line("A", 1, "1.00"));

            po.changeHeader(TODAY.minusDays(3), "paid by bank transfer", TODAY);

            assertThat(po.placedOn()).isEqualTo(TODAY.minusDays(3));
            assertThat(po.note()).contains("paid by bank transfer");
        }

        @Test
        @DisplayName("an open order can be cancelled, once")
        void cancel() {
            PurchaseOrder po = order(line("A", 1, "1.00"));

            po.cancel();

            assertThat(po.status()).isEqualTo(PurchaseOrderStatus.CANCELLED);
            assertThatThrownBy(po::cancel).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> po.replaceLines(List.of(line("X", 1, "1.00")))).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> po.changeHeader(TODAY, "x", TODAY)).isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("belonging to a consignment")
    class Attaching {

        @Test
        @DisplayName("an order attached to a consignment is frozen: no edits, no cancel")
        void attachedOrderIsFrozen() {
            PurchaseOrder po = order(line("A", 1, "1.00"));
            UUID consignment = UUID.randomUUID();

            po.attachTo(consignment);

            assertThat(po.isAttached()).isTrue();
            assertThat(po.consignmentId()).isEqualTo(consignment);
            assertThatThrownBy(() -> po.replaceLines(List.of(line("X", 1, "1.00"))))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("consignment");
            assertThatThrownBy(() -> po.changeHeader(TODAY, "x", TODAY)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(po::cancel).isInstanceOf(IllegalStateException.class).hasMessageContaining("consignment");
        }

        @Test
        @DisplayName("an order belongs to one consignment at a time")
        void oneConsignmentAtATime() {
            PurchaseOrder po = order(line("A", 1, "1.00"));
            po.attachTo(UUID.randomUUID());

            assertThatThrownBy(() -> po.attachTo(UUID.randomUUID()))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("already");
        }

        @Test
        @DisplayName("detaching sets it free to be edited, cancelled or attached elsewhere")
        void detach() {
            PurchaseOrder po = order(line("A", 1, "1.00"));
            po.attachTo(UUID.randomUUID());

            po.detach();

            assertThat(po.isAttached()).isFalse();
            po.replaceLines(List.of(line("X", 1, "1.00")));
            assertThatThrownBy(po::detach).isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("receiving needs an attached open order, and closes it for good")
        void receive() {
            PurchaseOrder po = order(line("A", 1, "1.00"));
            assertThatThrownBy(po::markReceived).isInstanceOf(IllegalStateException.class);

            po.attachTo(UUID.randomUUID());
            po.markReceived();

            assertThat(po.status()).isEqualTo(PurchaseOrderStatus.RECEIVED);
            assertThatThrownBy(po::markReceived).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(po::detach).isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("a cancelled order cannot be attached")
        void cancelledCannotBeAttached() {
            PurchaseOrder po = order(line("A", 1, "1.00"));
            po.cancel();

            assertThatThrownBy(() -> po.attachTo(UUID.randomUUID())).isInstanceOf(IllegalStateException.class);
        }
    }
}
