package com.oriah.order.events;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.oriah.events.EventJson;
import java.time.Instant;

import com.oriah.order.model.Order;
import com.oriah.order.model.OrderItem;
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

    // ids for the version-2 example: every one fixed, so the bytes are
    private static final UUID RING_PRODUCT = UUID.fromString("2f4a6c8e-0b2d-4f4a-8c6e-8a0c2e4a6c81");
    private static final UUID BOX_PRODUCT = UUID.fromString("3a5c7e9f-1d3f-4a5c-9e7f-9b1d3f5a7c92");
    private static final UUID PROMOTION = UUID.fromString("4b6d8f0a-2e4a-4b6d-8f0a-ac2e4a6c8da3");
    private static final UUID TOWN = UUID.fromString("5c7e9a1b-3f5b-4c7e-9a1b-bd3f5b7d9eb4");
    private static final UUID CONFIRMATION = UUID.fromString("6d8fa0b2-4a6c-4d8f-a0b2-ce4a6c8e0fc5");
    private static final UUID RING_LINE = UUID.fromString("7e9ab1c3-5b7d-4e9a-b1c3-df5b7d9f1ad6");
    private static final UUID RING_BOX_LINE = UUID.fromString("8fa0c2d4-6c8e-4fa0-c2d4-e06c8e0a2be7");
    private static final UUID BOX_LINE = UUID.fromString("90b1d3e5-7d9f-40b1-d3e5-f17d9f1b3cf8");
    private static final UUID BOX_LINES_OWN_BOX_LINE = UUID.fromString("a1c2e4f6-8e0a-41c2-e4f6-028e0a2c4d09");
    private static final UUID BOX_FOR_THE_RING = UUID.fromString("b2d3f507-9f1b-42d3-f507-139f1b3d5e1a");

    /**
     * Two rings at K1,250.00 less K50.00 each by a promotion, and a gift box at K45.50, delivered to a town by Oriah for K150.00: 2,500.00 + 45.50 -
     * 100.00 + 150.00 = K2,595.50, paid by mobile money (fee 1%) and settled for the day the money arrived.
     */
    private static Order paidOrder() {
        Order order = new Order(CUSTOMER, UUID.randomUUID(), "ZMW", "OWN_DELIVERY", new BigDecimal("150.00"), TOWN, "ORIAH_COLLECTS");
        ReflectionTestUtils.setField(order, "id", ORDER_ID);
        OrderItem box = order.addItem(BOX, BOX_PRODUCT, "Gift box (velvet, small)", 1, new BigDecimal("45.50"), new BigDecimal("0.00"), null);
        OrderItem ring = order.addItem(RING, RING_PRODUCT, "Aurelia 18k Gold Band / Ring Size M", 2, new BigDecimal("1250"), new BigDecimal("50.00"), PROMOTION);
        ReflectionTestUtils.setField(ring, "id", RING_LINE);
        ReflectionTestUtils.setField(ring, "boxLineId", RING_BOX_LINE);
        ReflectionTestUtils.setField(box, "id", BOX_LINE);
        ReflectionTestUtils.setField(box, "boxLineId", BOX_LINES_OWN_BOX_LINE);
        order.startHold("a".repeat(64), null);
        order.markHeld(Instant.parse("2026-11-12T07:50:00Z"), Instant.parse("2026-11-12T08:00:00Z"), java.util.Map.of(RING_BOX_LINE, BOX_FOR_THE_RING));
        order.beginSettlement(CONFIRMATION);
        order.settle(CONFIRMATION, Instant.parse("2026-11-12T08:00:00Z"), "OR-000123");
        return order;
    }

    private static final OrderPaidEvent.Payment MONEY = new OrderPaidEvent.Payment(CONFIRMATION, "MTN", new BigDecimal("2595.50"), new BigDecimal("25.96"),
            new BigDecimal("2569.54"), Instant.parse("2026-11-12T08:00:00Z"), Instant.parse("2026-11-12T08:00:05Z"));

    @Test
    @DisplayName("order.paid is version 2: the whole sale and the money that paid it, field for field and byte for byte (contracts/order-paid-v2.json)")
    void paidMatchesTheContract() throws IOException {
        Order order = paidOrder();

        assertThat(EventJson.write(OrderPaidEvent.of(order, OrderStatus.CREATED, MONEY, false))).isEqualTo(contract("order-paid-v2.json"));
    }

    @Test
    @DisplayName("a delivery the shop gave up (a waiver) is on order.paid as a trailing field: the charge on the sale is K0.00 and the amount given up is named, with no who or why")
    void paidSaysAWaivedDelivery() {
        Order order = new Order(CUSTOMER, UUID.randomUUID(), "ZMW", "OWN_DELIVERY", new BigDecimal("150.00"), TOWN, "ORIAH_COLLECTS");
        ReflectionTestUtils.setField(order, "id", ORDER_ID);
        order.addItem(RING, RING_PRODUCT, "Aurelia 18k Gold Band / Ring Size M", 1, new BigDecimal("1250"), new BigDecimal("0.00"), null);
        order.startHold("a".repeat(64), null);
        order.markHeld(Instant.parse("2026-11-12T07:50:00Z"), Instant.parse("2026-11-12T08:00:00Z"), java.util.Map.of());
        order.waiveDelivery("user:kc-owner", "a regular customer", Instant.parse("2026-11-12T07:55:00Z"));
        order.beginSettlement(CONFIRMATION);
        order.settle(CONFIRMATION, Instant.parse("2026-11-12T08:00:00Z"), "OR-000124");

        String json = EventJson.write(OrderPaidEvent.of(order, OrderStatus.CREATED, MONEY, false));

        assertThat(json).contains("\"shippingAmount\":0.00").endsWith(",\"republished\":false,\"deliveryWaivedAmount\":150.00}");
        assertThat(json).doesNotContain("kc-owner").doesNotContain("regular customer");
    }

    @Test
    @DisplayName("order.paid starts with version 1's twelve fields in version 1's order, so a consumer reading version 1 (notification) reads it unchanged")
    void paidKeepsVersionOnesFieldsFirst() {
        String json = EventJson.write(OrderPaidEvent.of(paidOrder(), OrderStatus.CREATED, MONEY, false));

        assertThat(json).startsWith("{\"orderId\":\"" + ORDER_ID + "\",\"keycloakId\":\"" + CUSTOMER + "\",\"previousStatus\":\"CREATED\",\"newStatus\":\"PAID\","
                + "\"total\":2595.50,\"currency\":\"ZMW\",\"subtotal\":2545.50,\"shippingAmount\":150.00,\"discountAmount\":100.00,"
                + "\"deliveryOption\":\"OWN_DELIVERY\",\"paymentIntentId\":null,\"lines\":[{\"variantId\":");
        assertThat(json).contains("\"quantity\":2,\"unitPrice\":1250.00,\"lineId\":");
    }

    @Test
    @DisplayName("the same sale announced again is the same bytes except that it says so: no previous status, republished true")
    void aRepublishedAnswerSaysSo() {
        String again = EventJson.write(OrderPaidEvent.of(paidOrder(), null, MONEY, true));

        assertThat(again).contains("\"previousStatus\":null,\"newStatus\":\"PAID\"").endsWith("\"republished\":true}");
        assertThat(again).contains("\"confirmationId\":\"" + CONFIRMATION + "\"");
    }

    @Test
    @DisplayName("order.payment_unsettled is exactly four fields, in the order payment reads them")
    void unsettledMatchesTheContract() {
        UUID order = ORDER_ID;

        assertThat(EventJson.write(new PaymentUnsettledEvent(CONFIRMATION, order, "WRONG_AMOUNT", "ZMW")))
                .isEqualTo("{\"confirmationId\":\"" + CONFIRMATION + "\",\"orderId\":\"" + ORDER_ID + "\",\"reason\":\"WRONG_AMOUNT\",\"currency\":\"ZMW\"}");
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
    @DisplayName("order.cancelled and order.expired end with the reservation id the hold was under: inventory lets go of THAT hold and no other")
    void cancelledAndExpiredCarryTheReservationId() {
        for (OrderStatus ending : List.of(OrderStatus.CANCELLED, OrderStatus.EXPIRED)) {
            Order order = placedOrder();
            order.startHold("a".repeat(64), null);
            order.transitionTo(ending);

            String json = EventJson.write(OrderEvent.of(order, OrderStatus.CREATED));

            assertThat(json).as(ending.name()).endsWith(",\"reservationId\":\"" + order.reservationId() + "\"}");
        }
    }

    @Test
    @DisplayName("no other event carries it: order.created, order.paid and the rest keep the bytes they always had")
    void otherEventsDoNotCarryTheReservationId() {
        Order held = placedOrder();
        held.startHold("a".repeat(64), null);

        assertThat(EventJson.write(OrderEvent.of(held, null))).doesNotContain("reservationId");
        held.transitionTo(OrderStatus.PAYMENT_SUBMITTED);
        assertThat(EventJson.write(OrderEvent.of(held, OrderStatus.CREATED))).doesNotContain("reservationId");
        held.transitionTo(OrderStatus.PAID);
        assertThat(EventJson.write(OrderEvent.of(held, OrderStatus.PAYMENT_SUBMITTED))).doesNotContain("reservationId");
    }

    @Test
    @DisplayName("an order cancelled before it ever took a hold (an older order) carries no reservation id, not a null")
    void anOrderWithNoHoldCarriesNone() {
        Order order = placedOrder();
        order.transitionTo(OrderStatus.CANCELLED);

        assertThat(EventJson.write(OrderEvent.of(order, OrderStatus.CREATED))).doesNotContain("reservationId");
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
