package com.oriah.order.service;

import com.oriah.events.EventJson;
import com.oriah.order.model.Order;
import com.oriah.order.model.OrderItem;
import com.oriah.order.model.OrderStatus;
import com.oriah.order.service.Corrections.Correction;
import com.oriah.order.service.Corrections.Line;
import com.oriah.order.service.Corrections.Piece;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two facts order announces when a paid sale is corrected (Phase 12, plan 7.1; FINAL AV15), byte for byte. The contract files were written before
 * any code, and finance, payment and notification were each built against their own copy of them: what order emits must be exactly those bytes.
 */
class SaleCorrectedContractTest {

    private static final UUID ORDER_ID = UUID.fromString("0f9a4c2e-6b1d-4e8f-a3c5-7d9e1b2f4a60");
    private static final String CUSTOMER = "8c2d4e6f-1a3b-4c5d-9e7f-0a1b2c3d4e5f";
    private static final UUID RING = UUID.fromString("1d3f5a7c-9e1b-4d3f-a5c7-e9f1a3c5e7b9");
    private static final UUID RING_PRODUCT = UUID.fromString("2f4a6c8e-0b2d-4f4a-8c6e-8a0c2e4a6c81");
    private static final UUID PROMOTION = UUID.fromString("4b6d8f0a-2e4a-4b6d-8f0a-ac2e4a6c8da3");
    private static final UUID CONFIRMATION = UUID.fromString("6d8fa0b2-4a6c-4d8f-a0b2-ce4a6c8e0fc5");
    private static final UUID RING_LINE = UUID.fromString("7e9ab1c3-5b7d-4e9a-b1c3-df5b7d9f1ad6");

    private static String contract(String name) throws IOException {
        try (InputStream in = SaleCorrectedContractTest.class.getResourceAsStream("/contracts/" + name)) {
            assertThat(in).as("contract file " + name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        }
    }

    private static BigDecimal k(String amount) {
        return new BigDecimal(amount);
    }

    /** Two rings at K100.00 less K10.00 each: paid, and (for a delivery) delivered by Oriah for K45.00. */
    private static Order paidOrder(String option, String delivery, String payer, String arrivedAt) {
        Order order = new Order(CUSTOMER, UUID.randomUUID(), "ZMW", option, k(delivery), null, payer);
        ReflectionTestUtils.setField(order, "id", ORDER_ID);
        OrderItem ring = order.addItem(RING, RING_PRODUCT, "Aurelia 18k Gold Band / Ring Size M", 2, k("100.00"), k("10.00"), PROMOTION);
        ReflectionTestUtils.setField(ring, "id", RING_LINE);
        order.beginSettlement(CONFIRMATION);
        order.settle(CONFIRMATION, Instant.parse(arrivedAt), "OR-000123");
        return order;
    }

    private static Correction correction(UUID id, String kind, String reason, String statusBefore, String arrivedAt, String occurredAt, String recordedAt,
                                         String goods, String discounts, String delivery, String owed) {
        return new Correction(id, ORDER_ID, kind, Corrections.DONE, reason, null, statusBefore, CONFIRMATION, "OR-000123", Instant.parse(arrivedAt),
                Instant.parse(occurredAt), null, k(goods), k(discounts), k(delivery), k("0.00"), k(owed), "ZMW", "CUSTOMER", null, null, "user:kc-maya",
                Instant.parse(recordedAt), 0, null, Instant.parse(recordedAt), null);
    }

    private static Line line(int quantity) {
        return new Line(RING_LINE, RING, RING_PRODUCT, quantity, k("100.00"), k("10.00"), PROMOTION, null, null, "Aurelia 18k Gold Band / Ring Size M");
    }

    @Test
    @DisplayName("order.sale_cancelled is exactly the bytes of contracts/order-sale-cancelled-v1.json (example 1: K225.00 paid, K225.00 owed)")
    void saleCancelledMatchesTheContract() throws IOException {
        Order order = paidOrder("OWN_DELIVERY", "45.00", "ORIAH_COLLECTS", "2026-11-03T08:00:00Z");
        order.cancelPaid();
        Correction cancelled = correction(UUID.fromString("c1a2b3c4-d5e6-4f70-8192-a3b4c5d6e7f8"), "CANCELLATION", "CUSTOMER_ASKED", "PAID",
                "2026-11-03T08:00:00Z", "2026-11-05T09:15:00.123456Z", "2026-11-05T09:15:00.123456Z", "200.00", "20.00", "45.00", "225.00");

        assertThat(EventJson.write(Corrections.event(order, cancelled, List.of(line(2)), null))).isEqualTo(contract("order-sale-cancelled-v1.json"));
    }

    @Test
    @DisplayName("order.return_recorded is exactly the bytes of contracts/order-return-recorded-v1.json (example 2: one ring of two came back, K90.00 owed)")
    void returnRecordedMatchesTheContract() throws IOException {
        Order order = paidOrder("SELF_COLLECT", "0.00", null, "2026-10-28T09:00:00Z");
        order.transitionTo(OrderStatus.FULFILLED);
        order.transitionTo(OrderStatus.DELIVERED);
        UUID returnId = UUID.fromString("d2b3c4d5-e6f7-4081-92a3-b4c5d6e7f809");
        Correction returned = correction(returnId, "RETURN", "CHANGED_MIND", "DELIVERED", "2026-10-28T09:00:00Z", "2026-11-04T10:00:00Z",
                "2026-11-04T14:20:11.500Z", "100.00", "10.00", "0.00", "90.00");
        Piece piece = new Piece(UUID.fromString("5a1e0f3c-7b2d-3c4e-9f60-1a2b3c4d5e6f"), returnId, RING_LINE, 1, "AWAITING", null, null, null, null, null, null, 0);

        assertThat(EventJson.write(Corrections.event(order, returned, List.of(line(1)), List.of(piece)))).isEqualTo(contract("order-return-recorded-v1.json"));
    }

    @Test
    @DisplayName("a correction's event carries identifiers, codes, amounts and dates only: never the note, the back-dating reason or who recorded it")
    void nothingPersonal() {
        Order order = paidOrder("OWN_DELIVERY", "45.00", "ORIAH_COLLECTS", "2026-11-03T08:00:00Z");
        order.cancelPaid();
        Correction withWords = new Correction(UUID.randomUUID(), ORDER_ID, "CANCELLATION", Corrections.DONE, "OTHER", "Mrs Banda rang to say she is travelling",
                "PAID", CONFIRMATION, "OR-000123", Instant.parse("2026-11-03T08:00:00Z"), Instant.parse("2026-11-05T09:15:00Z"), "keyed late after the weekend",
                k("200.00"), k("20.00"), k("45.00"), k("0.00"), k("225.00"), "ZMW", "CUSTOMER", "a-key", "a".repeat(64), "user:kc-maya",
                Instant.parse("2026-11-05T09:15:00Z"), 3, null, Instant.parse("2026-11-05T09:15:00Z"), null);

        String json = EventJson.write(Corrections.event(order, withWords, List.of(line(2)), null));

        assertThat(json).doesNotContain("Banda").doesNotContain("travelling").doesNotContain("keyed late").doesNotContain("kc-maya").doesNotContain("a-key")
                .doesNotContain("Aurelia").doesNotContain("attempts").doesNotContain("pieces");
    }
}
