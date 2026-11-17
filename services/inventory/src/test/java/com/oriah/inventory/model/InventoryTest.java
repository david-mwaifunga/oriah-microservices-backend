package com.oriah.inventory.model;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;

import com.oriah.inventory.exception.InsufficientStockException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The aggregate's own rules. A stock row is opened for a received variant and holds nothing until units are received
 * into it: there is no way to create one with a count (stock with no cost makes profit wrong).
 */
class InventoryTest {

    private static final UUID VARIANT = UUID.fromString("11111111-1111-1111-1111-111111111111");

    /** A row holding {@code onHand} units, the way the receipt service builds one. */
    private static Inventory holding(int onHand) {
        var inv = Inventory.open(VARIANT, "SELLABLE");
        inv.receive(onHand);
        return inv;
    }

    // ---- returned pieces waiting to be looked at (Phase 12) -----------------------------------------------------------------------------

    @Test
    @DisplayName("a returned piece is on the shelf and counted, but not free: nobody can reserve it until somebody has looked at it")
    void quarantinedStockIsNotFree() {
        var inv = holding(3);

        inv.takeBackToQuarantine();

        assertThat(inv.onHand()).isEqualTo(4);
        assertThat(inv.quarantined()).isEqualTo(1);
        assertThat(inv.free()).as("the three that were free before, and not the returned one").isEqualTo(3);
        assertThat(inv.available()).isEqualTo(3);

        inv.releaseQuarantine();
        assertThat(inv.quarantined()).isZero();
        assertThat(inv.free()).as("looked at and fine: on sale again").isEqualTo(4);
        assertThat(inv.onHand()).isEqualTo(4);
    }

    @Test
    @DisplayName("a returned piece that leaves (written off, or moved to a marked-down listing) leaves the count and the quarantine together")
    void aQuarantinedPieceLeaves() {
        var inv = holding(3);
        inv.takeBackToQuarantine();

        inv.exitQuarantined();

        assertThat(inv.onHand()).isEqualTo(3);
        assertThat(inv.quarantined()).isZero();
        assertThat(inv.free()).isEqualTo(3);
        assertThatThrownBy(inv::exitQuarantined).as("nothing is waiting").isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(inv::releaseQuarantine).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("the ordinary write-off and give-away never take a returned piece that is waiting: a plain refusal, never a broken count")
    void theOrdinaryWriteOffNeverTakesAQuarantinedPiece() {
        var inv = holding(1);
        inv.takeBackToQuarantine();

        assertThatThrownBy(() -> inv.writeOff(2)).as("one free, one waiting").isInstanceOf(InsufficientStockException.class);
        inv.writeOff(1);
        assertThat(inv.onHand()).isEqualTo(1);
        assertThat(inv.quarantined()).as("the waiting piece is untouched").isEqualTo(1);
        assertThatThrownBy(() -> inv.writeOff(1)).isInstanceOf(InsufficientStockException.class);
    }

    @Test
    @DisplayName("only goods are returned: a box that went out does not come back")
    void onlyGoodsAreQuarantined() {
        var box = Inventory.open(VARIANT, "PACKAGING");
        box.receive(5);

        assertThatThrownBy(box::takeBackToQuarantine).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("requires a non-null variantId")
    void requiresVariantId() {
        assertThatThrownBy(() -> Inventory.open(null, "SELLABLE"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("variantId");
    }

    @Test
    @DisplayName("a row is goods or packaging and nothing else")
    void kindIsGoodsOrPackaging() {
        assertThat(Inventory.open(VARIANT, "PACKAGING").kind()).isEqualTo("PACKAGING");
        assertThatThrownBy(() -> Inventory.open(VARIANT, "SERVICE"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("kind");
        assertThatThrownBy(() -> Inventory.open(VARIANT, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a fresh row holds nothing: no count, no reservations, nothing available")
    void freshInventoryHoldsNothing() {
        var inv = Inventory.open(VARIANT, "SELLABLE");

        assertThat(inv.variantId()).isEqualTo(VARIANT);
        assertThat(inv.onHand()).isZero();
        assertThat(inv.reserved()).isZero();
        assertThat(inv.available()).isZero();
        assertThat(inv.kind()).isEqualTo("SELLABLE");
    }

    @Test
    @DisplayName("receiving units raises the count and what is available, and rejects a non-positive quantity")
    void receiveAdjustsCounts() {
        var inv = holding(5);
        inv.reserve(2);

        inv.receive(10);

        assertThat(inv.onHand()).isEqualTo(15);
        assertThat(inv.reserved()).isEqualTo(2);
        assertThat(inv.available()).isEqualTo(13);
        assertThatThrownBy(() -> inv.receive(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> inv.receive(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("reserve increments reserved and lowers available")
    void reserveAdjustsCounts() {
        var inv = holding(10);

        inv.reserve(3);

        assertThat(inv.reserved()).isEqualTo(3);
        assertThat(inv.available()).isEqualTo(7);
        assertThat(inv.onHand()).isEqualTo(10);
    }

    @Test
    @DisplayName("reserve rejects qty exceeding available stock")
    void reserveRejectsOverflow() {
        var inv = holding(5);
        inv.reserve(3);

        assertThatThrownBy(() -> inv.reserve(3))
                .isInstanceOf(InsufficientStockException.class)
                .hasMessageContaining("only 2 available");
    }

    @Test
    @DisplayName("reserve rejects non-positive qty")
    void reserveRejectsNonPositive() {
        var inv = holding(10);

        assertThatThrownBy(() -> inv.reserve(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> inv.reserve(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("release decrements reserved back into available")
    void releaseAdjustsCounts() {
        var inv = holding(10);
        inv.reserve(4);

        inv.release(2);

        assertThat(inv.reserved()).isEqualTo(2);
        assertThat(inv.available()).isEqualTo(8);
        assertThat(inv.onHand()).isEqualTo(10);
    }

    @Test
    @DisplayName("release rejects qty exceeding reserved")
    void releaseRejectsOverflow() {
        var inv = holding(10);
        inv.reserve(2);

        assertThatThrownBy(() -> inv.release(5))
                .isInstanceOf(InsufficientStockException.class)
                .hasMessageContaining("only 2 reserved");
    }

    @Test
    @DisplayName("confirm decrements both reserved and onHand")
    void confirmAdjustsBothCounts() {
        var inv = holding(10);
        inv.reserve(3);

        inv.confirm(3);

        assertThat(inv.reserved()).isZero();
        assertThat(inv.onHand()).isEqualTo(7);
        assertThat(inv.available()).isEqualTo(7);
    }

    @Test
    @DisplayName("confirm rejects qty exceeding reserved")
    void confirmRejectsOverflow() {
        var inv = holding(10);
        inv.reserve(2);

        assertThatThrownBy(() -> inv.confirm(3))
                .isInstanceOf(InsufficientStockException.class)
                .hasMessageContaining("only 2 reserved");
    }

    @Test
    @DisplayName("a write-off takes only stock nobody has reserved, and lowers the count")
    void writeOffTakesAvailableStock() {
        var inv = holding(10);
        inv.reserve(4);

        inv.writeOff(6);

        assertThat(inv.onHand()).isEqualTo(4);
        assertThat(inv.reserved()).isEqualTo(4);
        assertThat(inv.available()).isZero();
        assertThatThrownBy(() -> inv.writeOff(1))
                .isInstanceOf(InsufficientStockException.class)
                .hasMessageContaining("only 0 available");
    }

    @Test
    @DisplayName("a take-back returns units to the count")
    void takeBackRaisesTheCount() {
        var inv = holding(10);
        inv.writeOff(3);

        inv.takeBack(2);

        assertThat(inv.onHand()).isEqualTo(9);
        assertThatThrownBy(() -> inv.takeBack(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
