package com.oriah.inventory.model;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;

import com.oriah.inventory.exception.InsufficientStockException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InventoryTest {

    private static final UUID VARIANT = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    @DisplayName("requires a non-null variantId")
    void requiresVariantId() {
        assertThatThrownBy(() -> new Inventory(null, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("variantId");
    }

    @Test
    @DisplayName("rejects negative onHand at construction")
    void rejectsNegativeOnHand() {
        assertThatThrownBy(() -> new Inventory(VARIANT, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("onHand");
    }

    @Test
    @DisplayName("a fresh inventory starts with zero reservations and full availability")
    void freshInventoryStartsClean() {
        var inv = new Inventory(VARIANT, 10);

        assertThat(inv.variantId()).isEqualTo(VARIANT);
        assertThat(inv.onHand()).isEqualTo(10);
        assertThat(inv.reserved()).isZero();
        assertThat(inv.available()).isEqualTo(10);
    }

    @Test
    @DisplayName("reserve increments reserved and lowers available")
    void reserveAdjustsCounts() {
        var inv = new Inventory(VARIANT, 10);

        inv.reserve(3);

        assertThat(inv.reserved()).isEqualTo(3);
        assertThat(inv.available()).isEqualTo(7);
        assertThat(inv.onHand()).isEqualTo(10);
    }

    @Test
    @DisplayName("reserve rejects qty exceeding available stock")
    void reserveRejectsOverflow() {
        var inv = new Inventory(VARIANT, 5);
        inv.reserve(3);

        assertThatThrownBy(() -> inv.reserve(3))
                .isInstanceOf(InsufficientStockException.class)
                .hasMessageContaining("only 2 available");
    }

    @Test
    @DisplayName("reserve rejects non-positive qty")
    void reserveRejectsNonPositive() {
        var inv = new Inventory(VARIANT, 10);

        assertThatThrownBy(() -> inv.reserve(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> inv.reserve(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("release decrements reserved back into available")
    void releaseAdjustsCounts() {
        var inv = new Inventory(VARIANT, 10);
        inv.reserve(4);

        inv.release(2);

        assertThat(inv.reserved()).isEqualTo(2);
        assertThat(inv.available()).isEqualTo(8);
        assertThat(inv.onHand()).isEqualTo(10);
    }

    @Test
    @DisplayName("release rejects qty exceeding reserved")
    void releaseRejectsOverflow() {
        var inv = new Inventory(VARIANT, 10);
        inv.reserve(2);

        assertThatThrownBy(() -> inv.release(5))
                .isInstanceOf(InsufficientStockException.class)
                .hasMessageContaining("only 2 reserved");
    }

    @Test
    @DisplayName("confirm decrements both reserved and onHand")
    void confirmAdjustsBothCounts() {
        var inv = new Inventory(VARIANT, 10);
        inv.reserve(3);

        inv.confirm(3);

        assertThat(inv.reserved()).isZero();
        assertThat(inv.onHand()).isEqualTo(7);
        assertThat(inv.available()).isEqualTo(7);
    }

    @Test
    @DisplayName("confirm rejects qty exceeding reserved")
    void confirmRejectsOverflow() {
        var inv = new Inventory(VARIANT, 10);
        inv.reserve(2);

        assertThatThrownBy(() -> inv.confirm(3))
                .isInstanceOf(InsufficientStockException.class)
                .hasMessageContaining("only 2 reserved");
    }

    @Test
    @DisplayName("restock increments onHand and raises available")
    void restockAdjustsCounts() {
        var inv = new Inventory(VARIANT, 5);
        inv.reserve(2);

        inv.restock(10);

        assertThat(inv.onHand()).isEqualTo(15);
        assertThat(inv.reserved()).isEqualTo(2);
        assertThat(inv.available()).isEqualTo(13);
    }
}
