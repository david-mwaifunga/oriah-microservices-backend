package com.oriah.order.events;

import com.oriah.events.OutboxWriter;
import com.oriah.order.model.Order;
import com.oriah.order.model.OrderStatus;
import com.oriah.order.repository.OrderRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * What the event log refuses to append (Phase 12, plan 4.2): a paid order that is cancelled is announced as {@code order.sale_cancelled}, with what is
 * owed, by its correction. A plain {@code order.cancelled} for it would tell inventory to release a hold that was sold and tell finance nothing, so
 * {@link OrderEventLog#moved} refuses it, the way it refuses a SHIPPED with no dispatch.
 */
class OrderEventLogRefusalsTest {

    private final OutboxWriter outbox = mock(OutboxWriter.class);
    private final OrderEventLog log = new OrderEventLog(outbox, mock(OrderRepository.class));

    private static Order cancelledAfterPayment(boolean packed) {
        Order order = new Order("kc-alice", null, "ZMW", "SELF_COLLECT", BigDecimal.ZERO);
        order.addItem(UUID.randomUUID(), "Aurelia ring", 1, new BigDecimal("100.00"));
        UUID confirmation = UUID.randomUUID();
        order.beginSettlement(confirmation);
        order.settle(confirmation, Instant.parse("2026-11-03T08:00:00Z"), "OR-000123");
        if (packed) {
            order.transitionTo(OrderStatus.FULFILLED);
        }
        order.cancelPaid();
        return order;
    }

    @Test
    void cancelledFromPaidIsRefused() {
        assertThatThrownBy(() -> log.moved(cancelledAfterPayment(false), OrderStatus.PAID, "user:kc-maya"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("order.sale_cancelled");
        assertThatThrownBy(() -> log.moved(cancelledAfterPayment(true), OrderStatus.FULFILLED, "user:kc-maya", Instant.now()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("order.sale_cancelled");

        verify(outbox, never()).append(any());
    }
}
