package com.oriah.order.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OrderStatusTest {

    @Test
    @DisplayName("happy path CREATED -> PAID -> FULFILLED -> SHIPPED -> DELIVERED is all allowed")
    void happyPathTransitions() {
        assertThat(OrderStatus.CREATED.canTransitionTo(OrderStatus.PAID)).isTrue();
        assertThat(OrderStatus.PAID.canTransitionTo(OrderStatus.FULFILLED)).isTrue();
        assertThat(OrderStatus.FULFILLED.canTransitionTo(OrderStatus.SHIPPED)).isTrue();
        assertThat(OrderStatus.SHIPPED.canTransitionTo(OrderStatus.DELIVERED)).isTrue();
    }

    @Test
    @DisplayName("an order is cancelled only while it is unpaid: from CREATED, never from PAID (a paid sale is not undone here; refunds are a later phase) and never from PAYMENT_SUBMITTED")
    void cancellation() {
        // Phase 7 retired PAID -> CANCELLED: a paid sale is never edited or undone by a status change; undoing one is a refund, and refunds are
        // Phase 12. This assertion used to say the opposite.
        assertThat(OrderStatus.CREATED.canTransitionTo(OrderStatus.CANCELLED)).isTrue();
        assertThat(OrderStatus.PAID.canTransitionTo(OrderStatus.CANCELLED)).isFalse();
        assertThat(OrderStatus.PAYMENT_SUBMITTED.canTransitionTo(OrderStatus.CANCELLED)).isFalse();
        assertThat(OrderStatus.EXPIRED.canTransitionTo(OrderStatus.CANCELLED)).isFalse();
        assertThat(OrderStatus.FULFILLED.canTransitionTo(OrderStatus.CANCELLED)).isFalse();
        assertThat(OrderStatus.SHIPPED.canTransitionTo(OrderStatus.CANCELLED)).isFalse();
        assertThat(OrderStatus.DELIVERED.canTransitionTo(OrderStatus.CANCELLED)).isFalse();
    }

    @Test
    @DisplayName("terminal states cannot transition out")
    void terminalStates() {
        assertThat(OrderStatus.DELIVERED.isTerminal()).isTrue();
        assertThat(OrderStatus.CANCELLED.isTerminal()).isTrue();
        for (OrderStatus next : OrderStatus.values()) {
            assertThat(OrderStatus.DELIVERED.canTransitionTo(next)).isFalse();
            assertThat(OrderStatus.CANCELLED.canTransitionTo(next)).isFalse();
        }
    }

    @Test
    @DisplayName("a packed order is shipped or, for a pickup, handed over: FULFILLED -> DELIVERED is an edge of the graph, and Order refuses it for anything but a pickup, and shipping a pickup")
    void aPickupIsHandedOver() {
        assertThat(OrderStatus.FULFILLED.canTransitionTo(OrderStatus.SHIPPED)).isTrue();
        assertThat(OrderStatus.FULFILLED.canTransitionTo(OrderStatus.DELIVERED)).isTrue();
        assertThat(OrderStatus.PAID.canTransitionTo(OrderStatus.DELIVERED)).isFalse();
        assertThat(OrderStatus.SHIPPED.canTransitionTo(OrderStatus.FULFILLED)).isFalse();

        com.oriah.order.model.Order pickup = packed("SELF_COLLECT");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> pickup.transitionTo(OrderStatus.SHIPPED)).isInstanceOf(IllegalStateException.class).hasMessageContaining("never shipped");
        pickup.transitionTo(OrderStatus.DELIVERED);
        assertThat(pickup.status()).isEqualTo(OrderStatus.DELIVERED);

        for (String option : new String[] {"YANGO", "OWN_DELIVERY"}) {
            com.oriah.order.model.Order parcel = packed(option);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> parcel.transitionTo(OrderStatus.DELIVERED)).as(option).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("shipped first");
            parcel.transitionTo(OrderStatus.SHIPPED);
            parcel.transitionTo(OrderStatus.DELIVERED);
        }
    }

    private static com.oriah.order.model.Order packed(String option) {
        boolean pickup = "SELF_COLLECT".equals(option);
        com.oriah.order.model.Order order = new com.oriah.order.model.Order("kc", null, "ZMW", option, java.math.BigDecimal.ZERO,
                pickup ? null : java.util.UUID.randomUUID(), pickup ? null : "ORIAH_COLLECTS");
        order.addItem(java.util.UUID.randomUUID(), "ring", 1, new java.math.BigDecimal("100.00"));
        order.transitionTo(OrderStatus.PAYMENT_SUBMITTED);
        order.transitionTo(OrderStatus.PAID);
        order.transitionTo(OrderStatus.FULFILLED);
        return order;
    }

    @Test
    @DisplayName("non-adjacent jumps (CREATED -> SHIPPED, CREATED -> DELIVERED) are blocked")
    void noJumps() {
        assertThat(OrderStatus.CREATED.canTransitionTo(OrderStatus.SHIPPED)).isFalse();
        assertThat(OrderStatus.CREATED.canTransitionTo(OrderStatus.DELIVERED)).isFalse();
        assertThat(OrderStatus.CREATED.canTransitionTo(OrderStatus.FULFILLED)).isFalse();
    }

    // ---------------------------------------------------------------- Phase 7: the hold and the payment claim

    @Test
    @DisplayName("an unpaid order may have a payment claim submitted, lapse (EXPIRED), be cancelled or be paid; a submitted one is paid or lapses")
    void theUnpaidStates() {
        assertThat(OrderStatus.CREATED.canTransitionTo(OrderStatus.PAYMENT_SUBMITTED)).isTrue();
        assertThat(OrderStatus.CREATED.canTransitionTo(OrderStatus.EXPIRED)).isTrue();
        assertThat(OrderStatus.CREATED.canTransitionTo(OrderStatus.CANCELLED)).isTrue();
        assertThat(OrderStatus.CREATED.canTransitionTo(OrderStatus.PAID)).isTrue();

        assertThat(OrderStatus.PAYMENT_SUBMITTED.canTransitionTo(OrderStatus.PAID)).isTrue();
        assertThat(OrderStatus.PAYMENT_SUBMITTED.canTransitionTo(OrderStatus.EXPIRED)).isTrue();
        assertThat(OrderStatus.PAYMENT_SUBMITTED.canTransitionTo(OrderStatus.CREATED)).as("a claim is not withdrawn").isFalse();
        assertThat(OrderStatus.PAYMENT_SUBMITTED.canTransitionTo(OrderStatus.FULFILLED)).isFalse();
    }

    @Test
    @DisplayName("a lapsed order can still be paid (money that arrives after the hold lapsed settles the sale if the piece is still free) and goes nowhere else")
    void aLapsedOrderCanStillBePaid() {
        assertThat(OrderStatus.EXPIRED.canTransitionTo(OrderStatus.PAID)).isTrue();
        for (OrderStatus next : OrderStatus.values()) {
            if (next != OrderStatus.PAID) {
                assertThat(OrderStatus.EXPIRED.canTransitionTo(next)).as("EXPIRED -> " + next).isFalse();
            }
        }
        assertThat(OrderStatus.EXPIRED.isTerminal()).isFalse();
    }

    @Test
    @DisplayName("PAID is reached from exactly three states (CREATED, PAYMENT_SUBMITTED, EXPIRED), and never from PAID itself")
    void whoCanReachPaid() {
        for (OrderStatus from : OrderStatus.values()) {
            boolean allowed = from == OrderStatus.CREATED || from == OrderStatus.PAYMENT_SUBMITTED || from == OrderStatus.EXPIRED;
            assertThat(from.canTransitionTo(OrderStatus.PAID)).as(from + " -> PAID").isEqualTo(allowed);
        }
    }

    @Test
    @DisplayName("the only status a person may ask for through the generic admin transition is CANCELLED (an unpaid order); packing, sending and delivering are the dispatch steps")
    void adminMayAskForOnly() {
        assertThat(OrderStatus.adminMayRequest(OrderStatus.FULFILLED)).as("the dispatch step pack").isFalse();
        assertThat(OrderStatus.adminMayRequest(OrderStatus.SHIPPED)).as("the dispatch step ship").isFalse();
        assertThat(OrderStatus.adminMayRequest(OrderStatus.DELIVERED)).as("the dispatch step deliver").isFalse();
        assertThat(OrderStatus.isDispatchStep(OrderStatus.FULFILLED) && OrderStatus.isDispatchStep(OrderStatus.SHIPPED) && OrderStatus.isDispatchStep(OrderStatus.DELIVERED)).isTrue();
        assertThat(OrderStatus.isDispatchStep(OrderStatus.CANCELLED) || OrderStatus.isDispatchStep(OrderStatus.PAID)).isFalse();
        assertThat(OrderStatus.adminMayRequest(OrderStatus.CANCELLED)).isTrue();
        assertThat(OrderStatus.adminMayRequest(OrderStatus.PAID)).as("only money, through the settlement saga, makes an order paid").isFalse();
        assertThat(OrderStatus.adminMayRequest(OrderStatus.EXPIRED)).as("only the hold lapsing, or a rejected claim").isFalse();
        assertThat(OrderStatus.adminMayRequest(OrderStatus.PAYMENT_SUBMITTED)).as("only the shopper's claim").isFalse();
        assertThat(OrderStatus.adminMayRequest(OrderStatus.CREATED)).isFalse();
    }

    @Test
    @DisplayName("a status name fits the column (20 characters) and reads as the event type payment, inventory and notification declare")
    void namesFitAndMatchTheEvents() {
        for (OrderStatus status : OrderStatus.values()) {
            assertThat(status.name().length()).as(status.name()).isLessThanOrEqualTo(20);
        }
        assertThat("order." + OrderStatus.PAYMENT_SUBMITTED.name().toLowerCase()).isEqualTo("order.payment_submitted");
        assertThat("order." + OrderStatus.EXPIRED.name().toLowerCase()).isEqualTo("order.expired");
    }
}
