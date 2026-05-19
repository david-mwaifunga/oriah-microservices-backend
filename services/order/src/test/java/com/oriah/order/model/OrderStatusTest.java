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
    @DisplayName("cancellation allowed from CREATED and PAID only")
    void cancellation() {
        assertThat(OrderStatus.CREATED.canTransitionTo(OrderStatus.CANCELLED)).isTrue();
        assertThat(OrderStatus.PAID.canTransitionTo(OrderStatus.CANCELLED)).isTrue();
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
    @DisplayName("non-adjacent jumps (CREATED -> SHIPPED, CREATED -> DELIVERED) are blocked")
    void noJumps() {
        assertThat(OrderStatus.CREATED.canTransitionTo(OrderStatus.SHIPPED)).isFalse();
        assertThat(OrderStatus.CREATED.canTransitionTo(OrderStatus.DELIVERED)).isFalse();
        assertThat(OrderStatus.CREATED.canTransitionTo(OrderStatus.FULFILLED)).isFalse();
    }
}
