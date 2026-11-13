package com.oriah.order.events;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.oriah.events.EventJson;
import com.oriah.order.model.Order;
import com.oriah.order.model.OrderStatus;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The order event contract, schema version 1 (MVP_PLAN Phase 1, item 2).
 *
 * <p>Every event on {@code oriah.order} is a FULL snapshot of one order: a
 * consumer never needs an earlier event to make sense of a later one, and a
 * projection can drop an older snapshot that arrives late. Today's six fields
 * come first, unchanged, so notification keeps reading what it reads; then
 * everything a receipt needs -- the amounts that make up the total, how it is
 * delivered, the payment, and every line. All amounts are in {@code currency};
 * one event never mixes currencies.
 *
 * <p>These tests pin the exact bytes. The paid example lives in
 * {@code src/test/resources/contracts/order-paid-v1.json} so the consumers'
 * builders read the same contract this test holds order to.
 */
class OrderEventContractTest {

    private static final UUID ORDER_ID = UUID.fromString("0f9a4c2e-6b1d-4e8f-a3c5-7d9e1b2f4a60");
    private static final String CUSTOMER = "8c2d4e6f-1a3b-4c5d-9e7f-0a1b2c3d4e5f";
    private static final UUID RING = UUID.fromString("1d3f5a7c-9e1b-4d3f-a5c7-e9f1a3c5e7b9");
    private static final UUID BOX = UUID.fromString("6e8a0c2e-4a6c-4e8a-b0c2-e4a6c8e0a2c4");
    private static final UUID INTENT = UUID.fromString("5b7d9f1a-3c5e-4a7b-8d9f-1b3d5f7a9c2e");

    /**
     * Two rings at K1,250.00 and a gift box at K45.50, delivered for K150.00:
     * 2,500.00 + 45.50 = 2,545.50, plus 150.00 delivery, less 0.00 discount =
     * K2,695.50.
     */
    private static Order placedOrder() {
        Order order = new Order(CUSTOMER, UUID.randomUUID(), "ZMW", "OWN_DELIVERY", new BigDecimal("150.00"));
        // The constructor draws a random id; a golden test needs a fixed one.
        ReflectionTestUtils.setField(order, "id", ORDER_ID);
        // The box is added first and the ring's price has no decimals, on
        // purpose: the snapshot lists lines by variant id (the database keeps
        // no line order, so two snapshots of one order must not depend on how
        // its rows came back) and writes every amount as the database stores
        // it, to the ngwee -- 1250.00, never 1250.
        order.addItem(BOX, "Gift box (velvet, small)", 1, new BigDecimal("45.50"));
        order.addItem(RING, "Aurelia 18k Gold Band / Ring Size M", 2, new BigDecimal("1250"));
        return order;
    }

    private static String contract(String name) throws IOException {
        try (InputStream in = OrderEventContractTest.class.getResourceAsStream("/contracts/" + name)) {
            assertThat(in).as("contract file " + name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        }
    }

    @Test
    @DisplayName("order.paid is the full snapshot, field for field and byte for byte (contracts/order-paid-v1.json)")
    void paidMatchesTheContract() throws IOException {
        Order order = placedOrder();
        order.attachPaymentIntent(INTENT);
        order.transitionTo(OrderStatus.PAID);

        assertThat(EventJson.write(OrderEvent.of(order, OrderStatus.CREATED)))
                .isEqualTo(contract("order-paid-v1.json"));
    }

    @Test
    @DisplayName("order.created writes its nulls: no previous status, no payment yet")
    void createdWritesItsNulls() {
        assertThat(EventJson.write(OrderEvent.of(placedOrder(), null))).isEqualTo("""
                {"orderId":"0f9a4c2e-6b1d-4e8f-a3c5-7d9e1b2f4a60",\
                "keycloakId":"8c2d4e6f-1a3b-4c5d-9e7f-0a1b2c3d4e5f",\
                "previousStatus":null,"newStatus":"CREATED",\
                "total":2695.50,"currency":"ZMW",\
                "subtotal":2545.50,"shippingAmount":150.00,"discountAmount":0.00,\
                "deliveryOption":"OWN_DELIVERY","paymentIntentId":null,\
                "lines":[{"variantId":"1d3f5a7c-9e1b-4d3f-a5c7-e9f1a3c5e7b9",\
                "title":"Aurelia 18k Gold Band / Ring Size M","quantity":2,"unitPrice":1250.00},\
                {"variantId":"6e8a0c2e-4a6c-4e8a-b0c2-e4a6c8e0a2c4",\
                "title":"Gift box (velvet, small)","quantity":1,"unitPrice":45.50}]}""");
    }

    @Test
    @DisplayName("every status has its event type, order.<status in lower case>")
    void everyStatusHasItsEventType() {
        Map<OrderStatus, String> expected = Map.of(
                OrderStatus.CREATED, "order.created",
                // Phase 7: payment, inventory and notification declared these two names BEFORE order emitted them (a type nobody declared is refused).
                OrderStatus.PAYMENT_SUBMITTED, "order.payment_submitted",
                OrderStatus.EXPIRED, "order.expired",
                OrderStatus.PAID, "order.paid",
                OrderStatus.FULFILLED, "order.fulfilled",
                OrderStatus.SHIPPED, "order.shipped",
                OrderStatus.DELIVERED, "order.delivered",
                OrderStatus.CANCELLED, "order.cancelled");

        // A status added later fails here until its event type is agreed with
        // the consumers, rather than going out under a name nobody reads.
        assertThat(expected).containsOnlyKeys(OrderStatus.values());
        Arrays.stream(OrderStatus.values()).forEach(status ->
                assertThat(OrderEvent.eventType(status)).as(status.name()).isEqualTo(expected.get(status)));
    }
}
