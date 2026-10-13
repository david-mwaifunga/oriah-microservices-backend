package com.oriah.cart.model;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The aggregate's own limits, with no Spring and no database.
 *
 * <h2>Why the aggregate and not Bean Validation</h2>
 *
 * {@code Cart.items()} returns an unmodifiable list, so the aggregate is the
 * only place a line can be added -- which makes it the only place a
 * lines-per-cart limit can be enforced. A {@code @Size} on a DTO cannot see how
 * many lines are already in the bag, and a {@code @Max} on a request quantity
 * cannot see the quantity it is about to be added to.
 *
 * <h2>The live 500 this closes</h2>
 *
 * {@code CartItem.bump} was {@code this.quantity += by} on an {@code Integer}
 * with no validation on the field at all -- the only {@code @Min(1)} lives on
 * the DTOs, and nothing anywhere carried a maximum. Two adds of
 * {@code Integer.MAX_VALUE} therefore wrapped the in-memory quantity negative;
 * what saved the row was {@code V1__create_carts.sql}'s
 * {@code CHECK (quantity > 0)}, which rejected it at flush. So the symptom was
 * a 500 from a constraint violation rather than a negative quantity in a
 * response -- worth stating precisely, because a test asserting the latter
 * would fail for the wrong reason.
 *
 * <h2>The limit is on the RESULT, and the check must not overflow either</h2>
 *
 * {@link #theCapCheckDoesNotOverflowWhileCheckingForOverflow} is the subtle
 * one. Guarding with {@code quantity + by > CAP} in {@code int} arithmetic
 * overflows for a large {@code by} and produces a NEGATIVE sum, which sails
 * under the cap -- a guard that is defeated by precisely the input it exists to
 * stop. The sum has to be widened before it is compared.
 *
 * <h2>The lines cap is not an arbitrary number</h2>
 *
 * 100 is catalog's limit on one pricing request ({@code @Size(max = 100)} on
 * {@code VariantPricingRequest.variantIds}), and catalog's javadoc already
 * asserts that "cart enforces the same limit on lines per cart, so a bag that
 * cannot be priced in one request cannot exist". That was false until now: a
 * 101-line bag was constructible, and its pricing call would be rejected,
 * stranding the shopper's whole cart behind a 400 with no way back except
 * removing lines.
 */
class CartCapsTest {

    private static final BigDecimal PRICE = new BigDecimal("4500.00");
    private static final String ZMW = "ZMW";

    private static Cart cart() {
        return new Cart("kc-alice");
    }

    private static CartItem add(Cart cart, UUID variantId, int quantity) {
        return cart.addOrIncrement(variantId, quantity, PRICE, "Aurelia 18k", ZMW);
    }

    @Test
    @DisplayName("the 101st distinct line is refused")
    void theHundredAndFirstLineIsRefused() {
        Cart cart = cart();
        for (int i = 0; i < Cart.MAX_LINES; i++) {
            add(cart, UUID.randomUUID(), 1);
        }
        assertThat(cart.items()).hasSize(Cart.MAX_LINES);

        assertThatThrownBy(() -> add(cart, UUID.randomUUID(), 1))
                .as("a bag that cannot be priced in one catalog request must not be "
                        + "constructible")
                .isInstanceOf(IllegalStateException.class);

        assertThat(cart.items())
                .as("the refused line must not be half-added")
                .hasSize(Cart.MAX_LINES);
    }

    @Test
    @DisplayName("adding more of a variant already in the bag is NOT blocked by the lines cap")
    void bumpingAFullBagIsStillAllowed() {
        Cart cart = cart();
        UUID first = UUID.randomUUID();
        add(cart, first, 1);
        for (int i = 1; i < Cart.MAX_LINES; i++) {
            add(cart, UUID.randomUUID(), 1);
        }

        // The cap counts LINES, not units. A full bag whose shopper wants a
        // second of something they already have adds no line, so refusing it
        // would be a bug in the guard -- and the obvious implementation
        // (check size() first, unconditionally) has exactly that bug.
        add(cart, first, 1);

        assertThat(cart.items()).hasSize(Cart.MAX_LINES);
        assertThat(cart.items().getFirst().quantity()).isEqualTo(2);
    }

    @Test
    @DisplayName("a single add above the per-line quantity cap is refused")
    void aQuantityAboveTheCapIsRefused() {
        Cart cart = cart();

        assertThatThrownBy(() -> add(cart, UUID.randomUUID(), Cart.MAX_QUANTITY_PER_LINE + 1))
                .isInstanceOf(IllegalStateException.class);

        assertThat(cart.items()).isEmpty();
    }

    @Test
    @DisplayName("bumping past the cap is refused on the RESULTING quantity")
    void bumpingPastTheCapIsRefusedOnTheSum() {
        Cart cart = cart();
        UUID variantId = UUID.randomUUID();
        add(cart, variantId, Cart.MAX_QUANTITY_PER_LINE);

        // Each add is individually legal; their sum is not. A guard that only
        // validates the incoming quantity passes both and stores the sum.
        assertThatThrownBy(() -> add(cart, variantId, 1))
                .isInstanceOf(IllegalStateException.class);

        assertThat(cart.items().getFirst().quantity())
                .as("the refused bump must leave the line untouched")
                .isEqualTo(Cart.MAX_QUANTITY_PER_LINE);
    }

    @Test
    @DisplayName("the cap check does not overflow while checking for overflow")
    void theCapCheckDoesNotOverflowWhileCheckingForOverflow() {
        Cart cart = cart();
        UUID variantId = UUID.randomUUID();
        add(cart, variantId, Cart.MAX_QUANTITY_PER_LINE);

        // THE assertion. In int arithmetic MAX_QUANTITY_PER_LINE +
        // Integer.MAX_VALUE is negative, so a naive `quantity + by > CAP`
        // guard is FALSE here and the add is allowed -- the quantity then
        // wraps negative and the row is rejected by the DB CHECK as a 500.
        assertThatThrownBy(() -> add(cart, variantId, Integer.MAX_VALUE))
                .isInstanceOf(IllegalStateException.class);

        assertThat(cart.items().getFirst().quantity())
                .as("must not have wrapped negative")
                .isEqualTo(Cart.MAX_QUANTITY_PER_LINE);
    }

    @Test
    @DisplayName("updateQuantity is capped too, or the cap is one PATCH away from useless")
    void updateQuantityAboveTheCapIsRefused() {
        Cart cart = cart();
        UUID variantId = UUID.randomUUID();
        add(cart, variantId, 1);

        assertThatThrownBy(() -> cart.updateQuantity(variantId, Cart.MAX_QUANTITY_PER_LINE + 1))
                .isInstanceOf(IllegalStateException.class);

        assertThat(cart.items().getFirst().quantity()).isEqualTo(1);
    }

    @Test
    @DisplayName("a bump refreshes the price, title and currency instead of keeping the snapshot")
    void aBumpRefreshesTheLineFromTheNewEntry() {
        Cart cart = cart();
        UUID variantId = UUID.randomUUID();
        cart.addOrIncrement(variantId, 1, new BigDecimal("100.00"), "Aurelia 18k", ZMW);

        // DELIBERATE REVERSAL of the documented old behaviour ("the cart holds
        // the price snapshot from when the customer first added the item").
        // Once the price comes from catalog rather than from the shopper,
        // keeping the first snapshot means a repriced variant is sold at
        // yesterday's price every time it is added again -- the defect
        // surviving on the second add, which is the easiest thing in the world
        // for a shopper to do by accident.
        cart.addOrIncrement(variantId, 1, new BigDecimal("120.00"), "Aurelia 18k - Size M", "USD");

        CartItem line = cart.items().getFirst();
        assertThat(line.quantity()).isEqualTo(2);
        assertThat(line.unitPrice()).isEqualByComparingTo("120.00");
        assertThat(line.title()).isEqualTo("Aurelia 18k - Size M");
        assertThat(line.currency()).isEqualTo("USD");
        assertThat(cart.subtotal())
                .as("2 x the REFRESHED price, not 100 + 120 and not 2 x 100")
                .isEqualByComparingTo("240.00");
    }

    @Test
    @DisplayName("a zero or negative price is refused: zero is catalog's 'not priced yet' marker")
    void anUnpricedLineIsRefused() {
        Cart cart = cart();

        assertThatThrownBy(() -> cart.addOrIncrement(
                UUID.randomUUID(), 1, BigDecimal.ZERO, "Draft ring", ZMW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cart.addOrIncrement(
                UUID.randomUUID(), 1, new BigDecimal("-1.00"), "Aurelia 18k", ZMW))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(cart.items()).isEmpty();
    }
}
