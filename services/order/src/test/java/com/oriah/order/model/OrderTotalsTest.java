package com.oriah.order.model;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.oriah.quote.Quote;
import com.oriah.quote.QuoteLine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The order's own total is the money it records (MVP_PLAN Phase 0, item 4.3):
 * {@code total = subtotal + shipping - discount}, on every new order, marked
 * so the database enforces it (V4).
 *
 * <p>It used to end {@code total = subtotal} ("no shipping/discount yet"), so
 * the order disagreed with what checkout charged -- the one record that is
 * supposed to say what was sold and for how much.
 */
class OrderTotalsTest {

    private static final BigDecimal ZERO = new BigDecimal("0.00");

    private static Order selfCollect() {
        return new Order("kc-alice", UUID.randomUUID(), "ZMW", "SELF_COLLECT", ZERO);
    }

    @Test
    @DisplayName("a new order is marked authoritative, with its delivery option and no discount")
    void aNewOrderIsMarkedAuthoritative() {
        Order order = selfCollect();

        assertThat(order.totalsAuthoritative()).isTrue();
        assertThat(order.deliveryOption()).isEqualTo("SELF_COLLECT");
        assertThat(order.shippingAmount()).isEqualByComparingTo("0.00");
        // Zero until Phase 7 brings scoped promotions (the owner's choice).
        assertThat(order.discountAmount()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("the total is the subtotal plus shipping, less discount")
    void theTotalIsSubtotalPlusShippingMinusDiscount() {
        Order order = new Order("kc-alice", UUID.randomUUID(), "ZMW", "OWN_DELIVERY", new BigDecimal("125.00"));
        order.addItem(UUID.randomUUID(), "Aurelia", 2, new BigDecimal("100.00"));
        order.addItem(UUID.randomUUID(), "Celeste", 1, new BigDecimal("50.00"));

        assertThat(order.subtotal()).isEqualByComparingTo("250.00");
        assertThat(order.total()).isEqualByComparingTo("375.00");
    }

    @Test
    @DisplayName("before any line is added, the total is already the delivery charge")
    void aNewOrderTotalsItsShippingBeforeAnyLine() {
        Order order = new Order("kc-alice", UUID.randomUUID(), "ZMW", "OWN_DELIVERY", new BigDecimal("125.00"));

        // Not 0: a marked order whose total ignored its shipping would break
        // the V4 check the moment it was saved.
        assertThat(order.total()).isEqualByComparingTo("125.00");
    }

    @ParameterizedTest(name = "{0} x {1} + {2} x {3}, delivery {4}")
    @CsvSource({
            "1, 100.00, 1, 50.00, 0.00",
            "2, 4500.00, 1, 0.01, 125.00",
            "99, 9999.99, 3, 0.10, 750.50",
    })
    @DisplayName("the order's total is exactly the shared quote's total -- the one formula the digest binds")
    void theAggregateAgreesWithTheSharedQuote(int qtyA, String priceA, int qtyB, String priceB, String delivery) {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        Order order = new Order("kc-alice", null, "ZMW", "OWN_DELIVERY", new BigDecimal(delivery));
        order.addItem(a, "A", qtyA, new BigDecimal(priceA));
        order.addItem(b, "B", qtyB, new BigDecimal(priceB));

        Quote quote = new Quote(List.of(
                new QuoteLine(a, qtyA, new BigDecimal(priceA), ZERO),
                new QuoteLine(b, qtyB, new BigDecimal(priceB), ZERO)), "ZMW", "OWN_DELIVERY", new BigDecimal(delivery));

        assertThat(order.total()).isEqualByComparingTo(quote.total());
        assertThat(order.subtotal()).isEqualByComparingTo(quote.subtotal());
    }

    @ParameterizedTest(name = "{0} x {1} less {2} a piece, then {3} x {4}, delivery {5}")
    @CsvSource({
            "2, 4500.00, 500.00, 1, 100.00, 125.00",
            "3, 100.00, 100.00, 1, 0.01, 0.00",
            "99, 9999.99, 0.01, 3, 0.10, 750.50",
    })
    @DisplayName("a promotion's discount is per piece, stored per line, and the order's discount and total are exactly the shared quote's")
    void aDiscountedOrderAgreesWithTheSharedQuote(int qtyA, String priceA, String discountA, int qtyB, String priceB, String delivery) {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID promotion = UUID.randomUUID();
        UUID town = UUID.randomUUID();
        Order order = new Order("kc-alice", UUID.randomUUID(), "ZMW", "OWN_DELIVERY", new BigDecimal(delivery), town, "ORIAH_COLLECTS");
        order.addItem(a, UUID.randomUUID(), "A", qtyA, new BigDecimal(priceA), new BigDecimal(discountA), promotion);
        order.addItem(b, UUID.randomUUID(), "B", qtyB, new BigDecimal(priceB), ZERO, null);

        Quote quote = new Quote(List.of(
                new QuoteLine(a, qtyA, new BigDecimal(priceA), new BigDecimal(discountA), promotion),
                new QuoteLine(b, qtyB, new BigDecimal(priceB), ZERO)), "ZMW", "OWN_DELIVERY", new BigDecimal(delivery), town, "ORIAH_COLLECTS");

        assertThat(order.discountAmount()).isEqualByComparingTo(quote.discount());
        assertThat(order.subtotal()).isEqualByComparingTo(quote.subtotal());
        assertThat(order.total()).isEqualByComparingTo(quote.total());
        assertThat(order.items().get(0).unitPrice()).as("the line keeps the LIST price").isEqualByComparingTo(priceA);
        assertThat(order.items().get(0).discountPerPiece()).isEqualByComparingTo(discountA);
        assertThat(order.items().get(0).promotionId()).isEqualTo(promotion);
        assertThat(order.items().get(1).promotionId()).as("no promotion on a line nothing was taken off").isNull();
    }

    @Test
    @DisplayName("a promotion id beside a zero discount is not kept: a promotion is named only where it discounts")
    void aPromotionWithNoDiscountIsNotKept() {
        Order order = new Order("kc-alice", null, "ZMW", "SELF_COLLECT", ZERO);

        OrderItem line = order.addItem(UUID.randomUUID(), UUID.randomUUID(), "A", 1, new BigDecimal("100.00"), ZERO, UUID.randomUUID());

        assertThat(line.promotionId()).isNull();
    }

    @Test
    @DisplayName("a discount above the price of the piece, or finer than a ngwee, is refused")
    void aBadDiscountIsRefused() {
        Order order = new Order("kc-alice", null, "ZMW", "SELF_COLLECT", ZERO);

        assertThatThrownBy(() -> order.addItem(UUID.randomUUID(), UUID.randomUUID(), "A", 1, new BigDecimal("100.00"),
                new BigDecimal("100.01"), UUID.randomUUID())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> order.addItem(UUID.randomUUID(), UUID.randomUUID(), "A", 1, new BigDecimal("100.00"),
                new BigDecimal("0.001"), UUID.randomUUID())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> order.addItem(UUID.randomUUID(), UUID.randomUUID(), "A", 1, new BigDecimal("100.00"),
                new BigDecimal("-1.00"), null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a courier the recipient pays costs nothing on the order: a charge beside it is refused, and the payer must be one of the two")
    void theRecipientPaysRule() {
        UUID town = UUID.randomUUID();

        assertThat(new Order("kc-alice", UUID.randomUUID(), "ZMW", "YANGO", ZERO, town, "RECIPIENT_PAYS").deliveryPayer()).isEqualTo("RECIPIENT_PAYS");
        assertThatThrownBy(() -> new Order("kc-alice", UUID.randomUUID(), "ZMW", "YANGO", new BigDecimal("30.00"), town, "RECIPIENT_PAYS"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Order("kc-alice", UUID.randomUUID(), "ZMW", "YANGO", ZERO, town, "NOBODY"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("an order takes its hold in two steps: PENDING with a reservation id minted up front, then HELD with the time inventory said and the box it chose")
    void anOrderIsPendingThenHeld() {
        Order order = new Order("kc-alice", null, "ZMW", "SELF_COLLECT", ZERO);
        OrderItem line = order.addItem(UUID.randomUUID(), UUID.randomUUID(), "A", 1, new BigDecimal("100.00"), ZERO, null);
        assertThat(order.holdState()).as("an order built without a hold has none").isNull();

        order.startHold("a".repeat(64), "key-1");

        assertThat(order.holdState()).isEqualTo(HoldState.PENDING);
        assertThat(order.reservationId()).isNotNull();
        assertThat(order.quoteDigest()).isEqualTo("a".repeat(64));
        assertThat(order.idempotencyKey()).isEqualTo("key-1");
        assertThat(line.boxLineId()).as("the box is sold under an id minted with the line").isNotNull().isNotEqualTo(line.id());

        java.time.Instant expires = java.time.Instant.parse("2026-11-13T12:10:00Z");
        UUID box = UUID.randomUUID();
        order.markHeld(java.time.Instant.parse("2026-11-13T12:00:00Z"), expires, java.util.Map.of(line.boxLineId(), box));

        assertThat(order.holdState()).isEqualTo(HoldState.HELD);
        assertThat(order.holdExpiresAt()).isEqualTo(expires);
        assertThat(line.boxVariantId()).isEqualTo(box);
        assertThatThrownBy(() -> order.startHold("b".repeat(64), null)).as("a hold is taken once").isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> order.markHeld(java.time.Instant.now(), expires, java.util.Map.of())).as("only a PENDING order becomes HELD")
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("self-collect needs no delivery address")
    void aMissingAddressIsAllowed() {
        Order order = new Order("kc-alice", null, "ZMW", "SELF_COLLECT", ZERO);

        assertThat(order.shippingAddressId()).isNull();
        assertThat(order.totalsAuthoritative()).isTrue();
    }

    @Test
    @DisplayName("a blank or malformed delivery option is refused")
    void aBlankDeliveryOptionIsRefused() {
        assertThatThrownBy(() -> new Order("kc-alice", null, "ZMW", " ", ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Order("kc-alice", null, "ZMW", "self collect", ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("shipping that is missing, negative or finer than a ngwee is refused")
    void aBadShippingAmountIsRefused() {
        assertThatThrownBy(() -> new Order("kc-alice", null, "ZMW", "SELF_COLLECT", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Order("kc-alice", null, "ZMW", "SELF_COLLECT", new BigDecimal("-1.00")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Order("kc-alice", null, "ZMW", "SELF_COLLECT", new BigDecimal("0.001")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
