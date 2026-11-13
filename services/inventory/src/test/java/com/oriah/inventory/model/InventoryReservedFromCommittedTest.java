package com.oriah.inventory.model;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.oriah.inventory.exception.InsufficientStockException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * An order that holds a box takes it from what was set aside for its product first (Phase 7). The boxes do not leave the building and
 * they are not free for anyone else: they move from {@code committed} to {@code reserved}, so {@code onHand} and {@code free} do not
 * change, and {@code reserved + committed <= onHand} (the database's own CHECK) keeps holding. Released, they go back where they came
 * from. This is arithmetic on one row; the reservation service decides when, and keeps the product's record in step.
 */
class InventoryReservedFromCommittedTest {

    private static final UUID BOX = UUID.fromString("b2222222-2222-4222-8222-222222222222");

    private static Inventory boxes(int onHand, int committed) {
        var inv = Inventory.open(BOX, "PACKAGING");
        inv.receive(onHand);
        if (committed > 0) {
            inv.commit(committed);
        }
        return inv;
    }

    @Test
    @DisplayName("boxes an order takes from what was set aside move from set-aside to reserved: the count and what is free do not move")
    void movesFromSetAsideToReserved() {
        var inv = boxes(60, 20);
        int freeBefore = inv.free();

        inv.reserveFromCommitted(5);

        assertThat(inv.committed()).isEqualTo(15);
        assertThat(inv.reserved()).isEqualTo(5);
        assertThat(inv.onHand()).isEqualTo(60);
        assertThat(inv.free()).as("nobody else can take them, and nothing new was taken from free").isEqualTo(freeBefore);
    }

    @Test
    @DisplayName("an order cannot take more from the set-aside than is set aside")
    void cannotTakeMoreThanIsSetAside() {
        var inv = boxes(60, 20);

        assertThatThrownBy(() -> inv.reserveFromCommitted(21)).isInstanceOf(InsufficientStockException.class).hasMessageContaining("20");
        assertThat(inv.committed()).as("the refused hold changed nothing").isEqualTo(20);
        assertThat(inv.reserved()).isZero();
    }

    @Test
    @DisplayName("released, boxes go back to the set-aside they came from; free does not move")
    void releasedBackToTheSetAside() {
        var inv = boxes(60, 20);
        inv.reserveFromCommitted(5);

        inv.releaseToCommitted(5);

        assertThat(inv.committed()).isEqualTo(20);
        assertThat(inv.reserved()).isZero();
        assertThat(inv.free()).isEqualTo(40);
    }

    @Test
    @DisplayName("an order cannot give back more to the set-aside than it holds")
    void cannotGiveBackMoreThanIsHeld() {
        var inv = boxes(60, 20);
        inv.reserveFromCommitted(5);

        assertThatThrownBy(() -> inv.releaseToCommitted(6)).isInstanceOf(InsufficientStockException.class).hasMessageContaining("5");
        assertThat(inv.reserved()).isEqualTo(5);
    }

    @Test
    @DisplayName("goods are never set aside, so nothing is taken from a set-aside of goods")
    void goodsHaveNoSetAside() {
        var ring = Inventory.open(UUID.randomUUID(), "SELLABLE");
        ring.receive(7);

        assertThatThrownBy(() -> ring.reserveFromCommitted(1)).isInstanceOf(InsufficientStockException.class);
        assertThat(ring.reserved()).isZero();
    }

    @Test
    @DisplayName("free boxes can be sold without a reservation (the box swapped in after payment): the count falls, nothing else moves")
    void sellingFreeBoxes() {
        var inv = boxes(60, 20);
        inv.reserveFromCommitted(5);   // 15 set aside, 5 reserved, 40 free

        inv.sellFromFree(10);

        assertThat(inv.onHand()).isEqualTo(50);
        assertThat(inv.committed()).isEqualTo(15);
        assertThat(inv.reserved()).isEqualTo(5);
        assertThat(inv.free()).isEqualTo(30);
    }

    @Test
    @DisplayName("only free boxes can be sold that way: not the ones set aside or reserved")
    void cannotSellWhatIsNotFree() {
        var inv = boxes(60, 20);
        inv.reserveFromCommitted(5);

        assertThatThrownBy(() -> inv.sellFromFree(41)).isInstanceOf(InsufficientStockException.class).hasMessageContaining("40");
        assertThat(inv.onHand()).isEqualTo(60);
    }

    @Test
    @DisplayName("a mix: some boxes from the set-aside, some from free, all released to where they came from")
    void aMixIsReleasedToWhereItCameFrom() {
        var inv = boxes(10, 2);

        inv.reserveFromCommitted(2);
        inv.reserve(1);
        assertThat(inv.reserved()).isEqualTo(3);
        assertThat(inv.committed()).isZero();
        assertThat(inv.free()).isEqualTo(7);

        inv.releaseToCommitted(2);
        inv.release(1);

        assertThat(inv.committed()).isEqualTo(2);
        assertThat(inv.reserved()).isZero();
        assertThat(inv.free()).isEqualTo(8);
        assertThat(inv.onHand()).isEqualTo(10);
    }
}
