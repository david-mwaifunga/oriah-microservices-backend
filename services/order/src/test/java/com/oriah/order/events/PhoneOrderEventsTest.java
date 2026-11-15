package com.oriah.order.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.oriah.events.EventJson;
import com.oriah.order.model.Order;
import com.oriah.order.model.OrderStatus;
import com.oriah.order.model.RecordedSale;
import com.oriah.order.model.SaleKind;

/**
 * What an order staff recorded for a caller with no account says on {@code oriah.order} (Phase 9, slice 9.6). The event has no owner (the caller has no
 * account), says it was recorded by staff ({@code channel: PHONE}) and when the sale truly happened ({@code occurredAt}), and carries NOTHING PERSONAL: no
 * caller name, phone or address, in any event, ever (the outbox is append-only, so what goes in could never be erased). Both fields are trailing and absent
 * on a web order, so every web event is exactly the bytes it was: no consumer needs a new declaration.
 */
class PhoneOrderEventsTest {

    private static final UUID ORDER_ID = UUID.fromString("0f9a4c2e-6b1d-4e8f-a3c5-7d9e1b2f4a60");
    private static final UUID RING = UUID.fromString("1d3f5a7c-9e1b-4d3f-a5c7-e9f1a3c5e7b9");
    private static final UUID TOWN = UUID.fromString("5c7e9a1b-3f5b-4c7e-9a1b-bd3f5b7d9eb4");
    private static final UUID CONFIRMATION = UUID.fromString("6d8fa0b2-4a6c-4d8f-a0b2-ce4a6c8e0fc5");
    private static final Instant SOLD = Instant.parse("2026-11-12T09:30:00.123456Z");
    private static final String CALLER = "Mulenga Chanda";
    private static final String PHONE = "+260971234567";

    private static Order phoneOrder(SaleKind kind) {
        String reason = kind == SaleKind.LATE_PAPER ? "the paper note was in the drawer" : null;
        // a late paper sale is never compared with today's published price, so it keeps no quote; a live one keeps the shop's K100.00
        RecordedSale sale = new RecordedSale("user:kc-sam", CALLER, PHONE, kind, kind == SaleKind.LATE_PAPER ? SOLD : Instant.now(), reason, "P-17", "a".repeat(64),
                new BigDecimal("60.00"), "a regular customer", kind == SaleKind.LIVE_PHONE ? new BigDecimal("100.00") : null);
        Order order = Order.recorded(sale, "ZMW", "OWN_DELIVERY", new BigDecimal("60.00"), TOWN, "ORIAH_COLLECTS");
        ReflectionTestUtils.setField(order, "id", ORDER_ID);
        order.addItem(RING, "Aurelia 18k / Ring Size M", 1, new BigDecimal("4500.00"));
        order.recordDelivery(CALLER, PHONE, "Plot 12 Great East Road", null, "Lusaka", null, null, "ZM", "Oriah delivery", "Lusaka");
        return order;
    }

    private static final OrderPaidEvent.Payment MONEY = new OrderPaidEvent.Payment(CONFIRMATION, "CASH", new BigDecimal("4560.00"), BigDecimal.ZERO,
            new BigDecimal("4560.00"), Instant.parse("2026-11-12T09:35:00Z"), Instant.parse("2026-11-12T09:35:05Z"));

    @Test
    @DisplayName("order.created of a phone order has no owner and ends with its channel and the date the sale happened")
    void createdSaysPhoneAndWhen() {
        String json = EventJson.write(OrderEvent.of(phoneOrder(SaleKind.LATE_PAPER), null));

        assertThat(json).contains("\"keycloakId\":null");
        assertThat(json).endsWith("\"channel\":\"PHONE\",\"occurredAt\":\"2026-11-12T09:30:00.123456Z\"}");
    }

    @Test
    @DisplayName("nothing personal is in any event of a phone order: no caller, no phone, no address, no recorder, no reason, no slip, no agreed reason")
    void nothingPersonal() {
        Order order = phoneOrder(SaleKind.LATE_PAPER);
        String created = EventJson.write(OrderEvent.of(order, null));
        String cancelled = EventJson.write(OrderEvent.of(order, OrderStatus.CREATED));
        String shipped = EventJson.write(OrderEvent.shipped(order, OrderStatus.FULFILLED, new OrderEvent.Dispatch(SOLD, new BigDecimal("40.00"), "ORIAH_COLLECTS")));
        String paid = EventJson.write(OrderPaidEvent.of(order, OrderStatus.CREATED, MONEY, false));

        for (String json : new String[] {created, cancelled, shipped, paid}) {
            assertThat(json).doesNotContain("Mulenga").doesNotContain("Chanda").doesNotContain("+260").doesNotContain("971234567").doesNotContain("Great East")
                    .doesNotContain("kc-sam").doesNotContain("paper note").doesNotContain("P-17").doesNotContain("regular customer").doesNotContain("Lusaka");
        }
    }

    @Test
    @DisplayName("order.shipped of a phone order keeps its dispatch and then says its channel and date")
    void shippedKeepsItsDispatchFirst() {
        String json = EventJson.write(OrderEvent.shipped(phoneOrder(SaleKind.LATE_PAPER), OrderStatus.FULFILLED,
                new OrderEvent.Dispatch(Instant.parse("2026-11-13T10:00:00Z"), new BigDecimal("40.00"), "ORIAH_COLLECTS")));

        assertThat(json).contains("\"dispatch\":{\"dispatchedAt\":\"2026-11-13T10:00:00Z\",\"farePaid\":40.00,\"payer\":\"ORIAH_COLLECTS\"},\"channel\":\"PHONE\",\"occurredAt\":");
    }

    @Test
    @DisplayName("order.paid of a phone order ends with its channel and the date the sale happened, after 'republished'")
    void paidSaysPhoneAndWhen() {
        String json = EventJson.write(OrderPaidEvent.of(phoneOrder(SaleKind.LATE_PAPER), OrderStatus.CREATED, MONEY, false));

        assertThat(json).contains("\"keycloakId\":null").contains("\"republished\":false,\"channel\":\"PHONE\",\"occurredAt\":\"2026-11-12T09:30:00.123456Z\",");
    }

    @Test
    @DisplayName("order.paid of a delivery agreed by phone then says where the price came from, what was agreed, and the shop's own quote (finance's concession report); a late sale has no quote")
    void paidSaysTheAgreedDelivery() {
        String live = EventJson.write(OrderPaidEvent.of(phoneOrder(SaleKind.LIVE_PHONE), OrderStatus.CREATED, MONEY, false));
        String late = EventJson.write(OrderPaidEvent.of(phoneOrder(SaleKind.LATE_PAPER), OrderStatus.CREATED, MONEY, false));

        assertThat(live).endsWith(",\"deliveryPriceSource\":\"AGREED\",\"deliveryAgreedAmount\":60.00,\"deliveryQuotedAmount\":100.00}");
        assertThat(late).endsWith(",\"deliveryPriceSource\":\"AGREED\",\"deliveryAgreedAmount\":60.00}");
    }

    @Test
    @DisplayName("a published delivery of a phone order says only its source; a web order says none of the four new fields")
    void aPublishedDeliveryAndAWebOrder() {
        RecordedSale sale = new RecordedSale("user:kc-sam", CALLER, PHONE, SaleKind.LIVE_PHONE, Instant.now(), null, null, "b".repeat(64), null, null, null);
        Order published = Order.recorded(sale, "ZMW", "OWN_DELIVERY", new BigDecimal("100.00"), TOWN, "ORIAH_COLLECTS");
        published.addItem(RING, "Aurelia 18k / Ring Size M", 1, new BigDecimal("4500.00"));
        Order web = new Order("kc-alice", null, "ZMW", "SELF_COLLECT", new BigDecimal("0.00"));
        web.addItem(RING, "Aurelia 18k / Ring Size M", 1, new BigDecimal("4500.00"));

        String json = EventJson.write(OrderPaidEvent.of(published, OrderStatus.CREATED, MONEY, false));
        String webJson = EventJson.write(OrderPaidEvent.of(web, OrderStatus.CREATED, MONEY, false));

        assertThat(json).endsWith(",\"deliveryPriceSource\":\"PUBLISHED\"}");
        assertThat(webJson).doesNotContain("deliveryPriceSource").doesNotContain("deliveryAgreedAmount").doesNotContain("deliveryQuotedAmount")
                .doesNotContain("deliveryWaivedAmount");
    }

    @Test
    @DisplayName("a web order's events are exactly what they were: no channel, no occurredAt")
    void aWebOrderIsUnchanged() {
        Order web = new Order("kc-alice", null, "ZMW", "SELF_COLLECT", new BigDecimal("0.00"));
        web.addItem(RING, "Aurelia 18k / Ring Size M", 1, new BigDecimal("4500.00"));

        assertThat(EventJson.write(OrderEvent.of(web, null))).doesNotContain("channel").doesNotContain("occurredAt");
        assertThat(EventJson.write(OrderPaidEvent.of(web, OrderStatus.CREATED, MONEY, false))).doesNotContain("channel").doesNotContain("occurredAt");
    }
}
