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
