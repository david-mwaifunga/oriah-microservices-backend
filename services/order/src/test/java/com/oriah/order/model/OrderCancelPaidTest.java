package com.oriah.order.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A paid order is cancelled only by its correction (Phase 12, plan 4.2): {@link Order#cancelPaid()} is the one way from PAID or FULFILLED to CANCELLED.
 * The plain {@link Order#transitionTo} still refuses it, so no old door (the shopper's cancel, the staff transition, a recorded order's cancel) can
 * undo a sale without putting its stock back and recording what is owed.
 */
class OrderCancelPaidTest {

    private static Order paid() {
        Order order = new Order("kc-alice", null, "ZMW", "SELF_COLLECT", BigDecimal.ZERO);
        order.addItem(UUID.randomUUID(), "Aurelia ring", 1, new BigDecimal("100.00"));
        UUID confirmation = UUID.randomUUID();
        order.beginSettlement(confirmation);
        order.settle(confirmation, Instant.parse("2026-11-03T08:00:00Z"), "OR-000123");
        return order;
    }

    @Test
    void onlyTheCorrectionCancelsAPaidOrder() {
        Order paid = paid();
        assertThatThrownBy(() -> paid.transitionTo(OrderStatus.CANCELLED)).isInstanceOf(IllegalStateException.class).hasMessageContaining("paid");
        assertThat(paid.status()).isEqualTo(OrderStatus.PAID);

        paid.cancelPaid();

        assertThat(paid.status()).isEqualTo(OrderStatus.CANCELLED);
        // The pieces WERE sold: the hold stays consumed (the correction, not a release, is what puts them back).
        assertThat(paid.holdState()).isEqualTo(HoldState.CONSUMED);
        // The sale itself is never rewritten.
        assertThat(paid.receiptNumber()).isEqualTo("OR-000123");
        assertThat(paid.paidAt()).isEqualTo(Instant.parse("2026-11-03T08:00:00Z"));
        assertThat(paid.total()).isEqualByComparingTo("100.00");
    }

    @Test
    void aPackedOrderIsCancelledTheSameWay() {
        Order packed = paid();
        packed.transitionTo(OrderStatus.FULFILLED);
        assertThatThrownBy(() -> packed.transitionTo(OrderStatus.CANCELLED)).isInstanceOf(IllegalStateException.class);

        packed.cancelPaid();

        assertThat(packed.status()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void nothingElseIsCancelledThisWay() {
        Order unpaid = new Order("kc-alice", null, "ZMW", "SELF_COLLECT", BigDecimal.ZERO);
        unpaid.addItem(UUID.randomUUID(), "Aurelia ring", 1, new BigDecimal("100.00"));
        assertThatThrownBy(unpaid::cancelPaid).isInstanceOf(IllegalStateException.class);

        Order handedOver = paid();
        handedOver.transitionTo(OrderStatus.FULFILLED);
        handedOver.transitionTo(OrderStatus.DELIVERED);
        assertThatThrownBy(handedOver::cancelPaid).isInstanceOf(IllegalStateException.class);
        assertThat(handedOver.status()).isEqualTo(OrderStatus.DELIVERED);

        Order cancelled = paid();
        cancelled.cancelPaid();
        assertThatThrownBy(cancelled::cancelPaid).isInstanceOf(IllegalStateException.class);
    }
}
