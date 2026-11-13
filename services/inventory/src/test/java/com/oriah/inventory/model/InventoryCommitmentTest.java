package com.oriah.inventory.model;

import java.util.Random;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.oriah.inventory.exception.InsufficientStockException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Boxes set aside for a product: a packaging row carries three numbers, not one. {@code onHand} is what is physically held,
 * {@code committed} is what is promised to products, {@code reserved} is what unpaid orders hold, and what is {@code free} (what can
 * still be promised or reserved) is what is left: {@code onHand - committed - reserved}, never below zero. The trap is promising the
 * same box twice: 50 pouches to the gold studs and 50 to the silver ones out of 60 held would promise 100 that do not exist.
 */
class InventoryCommitmentTest {

    private static final UUID BOX = UUID.fromString("b2222222-2222-4222-8222-222222222222");

    private static Inventory boxes(int onHand) {
        var inv = Inventory.open(BOX, "PACKAGING");
        inv.receive(onHand);
        return inv;
    }

    @Test
    @DisplayName("what is free is what is held, less what is set aside and what unpaid orders hold")
    void freeIsWhatIsLeft() {
        var inv = boxes(60);
        inv.reserve(5);
        inv.commit(20);

        assertThat(inv.onHand()).isEqualTo(60);
        assertThat(inv.committed()).isEqualTo(20);
        assertThat(inv.reserved()).isEqualTo(5);
        assertThat(inv.free()).isEqualTo(35);
        assertThat(inv.available()).as("what can still be reserved is what is free").isEqualTo(35);
    }

    @Test
    @DisplayName("setting aside needs free boxes: 50 and then 50 out of 60 is refused, and the refusal says how many were free")
    void youCannotPromiseTheSameBoxTwice() {
        var inv = boxes(60);
        inv.commit(50);

        assertThatThrownBy(() -> inv.commit(50))
                .isInstanceOf(InsufficientStockException.class)
                .hasMessageContaining("10");
        assertThat(inv.committed()).as("the refused promise changed nothing").isEqualTo(50);
        inv.commit(10);
        assertThat(inv.free()).isZero();
    }

    @Test
    @DisplayName("letting go moves boxes from set-aside back to free, and nothing leaves the building")
    void releasing() {
        var inv = boxes(60);
        inv.commit(50);

        inv.uncommit(30);

        assertThat(inv.committed()).isEqualTo(20);
        assertThat(inv.free()).isEqualTo(40);
        assertThat(inv.onHand()).isEqualTo(60);
        assertThatThrownBy(() -> inv.uncommit(21)).isInstanceOf(InsufficientStockException.class);
    }

    @Test
    @DisplayName("only packaging is set aside: goods are refused, and a quantity must be above zero")
    void onlyPackaging() {
        var ring = Inventory.open(UUID.randomUUID(), "SELLABLE");
        ring.receive(10);

        assertThatThrownBy(() -> ring.commit(1)).isInstanceOf(IllegalStateException.class).hasMessageContaining("packaging");
        assertThatThrownBy(() -> boxes(10).commit(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> boxes(10).uncommit(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a reservation sees only what is free: boxes promised to a product are not for the next order to take")
    void reserveSeesFree() {
        var inv = boxes(10);
        inv.commit(6);

        assertThatThrownBy(() -> inv.reserve(5)).isInstanceOf(InsufficientStockException.class).hasMessageContaining("4");
        inv.reserve(4);
        assertThat(inv.free()).isZero();
    }

    @Test
    @DisplayName("a sale of a box that was set aside lowers BOTH the set-aside and the count: if only the count fell the promise would outlive the box")
    void aSaleOfACommittedBoxLowersBoth() {
        var inv = boxes(10);
        inv.commit(6);

        inv.consumeCommitted(2);

        assertThat(inv.committed()).isEqualTo(4);
        assertThat(inv.onHand()).isEqualTo(8);
        assertThat(inv.free()).as("selling set-aside boxes leaves what is free as it was").isEqualTo(4);
        assertThatThrownBy(() -> inv.consumeCommitted(5)).isInstanceOf(InsufficientStockException.class);
        assertThatThrownBy(() -> inv.consumeCommitted(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("writing off boxes takes free ones first, and touches what is promised only for what free cannot absorb, saying how many")
    void aWriteOffTakesFreeFirst() {
        var inv = boxes(10);
        inv.commit(6);          // free 4

        assertThat(inv.writeOff(3)).as("absorbed by free: nothing promised was touched").isZero();
        assertThat(inv.committed()).isEqualTo(6);
        assertThat(inv.onHand()).isEqualTo(7);

        assertThat(inv.writeOff(3)).as("free had 1 left: 2 of the promised boxes went").isEqualTo(2);
        assertThat(inv.committed()).isEqualTo(4);
        assertThat(inv.onHand()).isEqualTo(4);
        assertThat(inv.free()).isZero();
    }

    @Test
    @DisplayName("boxes an unpaid order holds are never written off: more than is held and unreserved is refused, promised or not")
    void reservedBoxesAreNotWrittenOff() {
        var inv = boxes(10);
        inv.reserve(4);
        inv.commit(3);

        assertThatThrownBy(() -> inv.writeOff(7)).isInstanceOf(InsufficientStockException.class);
        assertThat(inv.writeOff(6)).isEqualTo(3);
        assertThat(inv.onHand()).isEqualTo(4);
        assertThat(inv.reserved()).isEqualTo(4);
    }

    @Test
    @DisplayName("goods are written off exactly as before: nothing is ever set aside for them")
    void goodsAreUnchanged() {
        var ring = Inventory.open(UUID.randomUUID(), "SELLABLE");
        ring.receive(10);
        ring.reserve(3);

        assertThat(ring.writeOff(7)).isZero();
        assertThat(ring.onHand()).isEqualTo(3);
        assertThatThrownBy(() -> ring.writeOff(1)).isInstanceOf(InsufficientStockException.class);
    }

    @Test
    @DisplayName("through thousands of random changes, committed and reserved together never exceed what is held, and neither goes below zero")
    void invariantsHoldThroughRandomSequences() {
        Random random = new Random(20261113L);
        for (int run = 0; run < 400; run++) {
            var inv = boxes(1 + random.nextInt(60));
            for (int step = 0; step < 40; step++) {
                int qty = 1 + random.nextInt(12);
                try {
                    switch (random.nextInt(8)) {
                        case 0 -> inv.commit(qty);
                        case 1 -> inv.uncommit(qty);
                        case 2 -> inv.reserve(qty);
                        case 3 -> inv.release(qty);
                        case 4 -> inv.confirm(qty);
                        case 5 -> inv.consumeCommitted(qty);
                        case 6 -> inv.writeOff(qty);
                        default -> inv.takeBack(qty);
                    }
                } catch (InsufficientStockException refused) {
                    // refused: nothing changed, which is what the checks below prove
                }
                assertThat(inv.committed()).isGreaterThanOrEqualTo(0);
                assertThat(inv.reserved()).isGreaterThanOrEqualTo(0);
                assertThat(inv.committed() + inv.reserved()).as("run %d step %d", run, step).isLessThanOrEqualTo(inv.onHand());
                assertThat(inv.onHand()).isEqualTo(inv.free() + inv.committed() + inv.reserved());
            }
        }
    }
}
