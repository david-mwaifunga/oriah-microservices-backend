package com.oriah.finance.events;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.oriah.events.EventJson;

/**
 * The events the other services write, as finance's tests need them: the envelope (what the Inbox hands an applier) and the body, built the way each producer
 * writes it. Every figure is derived the way the producer derives it (a sale's total is its lines at list, less discounts, plus delivery), so a test states only what it
 * means to test.
 */
final class Events {

    private Events() {
    }

    static BigDecimal k(String amount) {
        return new BigDecimal(amount);
    }

    static EventContext ctx(String type, String aggregateType, UUID aggregateId, Instant occurredAt) {
        return ctx(type, aggregateType, aggregateId, occurredAt, 1);
    }

    static EventContext ctx(String type, String aggregateType, UUID aggregateId, Instant occurredAt, int schemaVersion) {
        return new EventContext(UUID.randomUUID(), type, aggregateType, aggregateId.toString(), 1, 100L, occurredAt, occurredAt.plusSeconds(5), "system:test", "test",
                schemaVersion);
    }

    static byte[] bytes(Map<String, Object> body) {
        return EventJson.write(body).getBytes(StandardCharsets.UTF_8);
    }

    /** A line sold: {@code qty} of a piece at list price {@code list}, less {@code discount} on each. */
    record Sold(UUID lineId, UUID variantId, UUID productId, int qty, String list, String discount) {

        static Sold of(UUID productId, int qty, String list, String discount) {
            return new Sold(UUID.randomUUID(), UUID.randomUUID(), productId, qty, list, discount);
        }
    }

    /** An {@code order.paid} (version 2) under construction. */
    static final class Paid {
        UUID order = UUID.randomUUID();
        UUID confirmation = UUID.randomUUID();
        Instant arrivedAt = Instant.parse("2026-10-14T08:00:00Z");
        final List<Sold> lines = new ArrayList<>();
        String shipping = "0.00";
        String payer = null;
        String method = "MTN";
        String fee = "0.00";
        String waived = null;
        boolean republished = false;
        String channel = null;
        Instant saleOccurredAt = null;
        final Map<String, Object> overrides = new LinkedHashMap<>();
        int version = 2;

        Paid line(Sold s) { lines.add(s); return this; }
        Paid shipping(String v) { shipping = v; return this; }
        Paid payer(String v) { payer = v; return this; }
        Paid fee(String v) { fee = v; return this; }
        Paid arrived(String instant) { arrivedAt = Instant.parse(instant); return this; }
        Paid waived(String v) { waived = v; return this; }
        Paid republished() { republished = true; return this; }
        Paid phone(String saleOccurred) { channel = "PHONE"; saleOccurredAt = Instant.parse(saleOccurred); return this; }
        Paid set(String field, Object value) { overrides.put(field, value); return this; }
        Paid order(UUID v) { order = v; return this; }
        Paid confirmation(UUID v) { confirmation = v; return this; }

        BigDecimal subtotal() { return lines.stream().map(l -> k(l.list()).multiply(BigDecimal.valueOf(l.qty()))).reduce(BigDecimal.ZERO, BigDecimal::add); }
        BigDecimal discount() { return lines.stream().map(l -> k(l.discount()).multiply(BigDecimal.valueOf(l.qty()))).reduce(BigDecimal.ZERO, BigDecimal::add); }
        BigDecimal total() { return subtotal().subtract(discount()).add(k(shipping)); }

        EventContext context() {
            return ctx("order.paid", "order", order, arrivedAt, version);
        }

        byte[] body() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("orderId", order);
            m.put("keycloakId", channel == null ? "kc-alice-0001" : null);
            m.put("previousStatus", "CREATED");
            m.put("newStatus", "PAID");
            m.put("total", total());
            m.put("currency", "ZMW");
            m.put("subtotal", subtotal());
            m.put("shippingAmount", k(shipping));
            m.put("discountAmount", discount());
            m.put("deliveryOption", "OWN_DELIVERY");
            m.put("paymentIntentId", null);
            List<Map<String, Object>> ls = new ArrayList<>();
            for (Sold s : lines) {
                Map<String, Object> l = new LinkedHashMap<>();
                l.put("variantId", s.variantId());
                l.put("title", "A piece of jewellery");
                l.put("quantity", s.qty());
                l.put("unitPrice", k(s.list()));
                l.put("lineId", s.lineId());
                l.put("productId", s.productId());
                l.put("discountPerPiece", k(s.discount()));
                l.put("promotionId", null);
                l.put("boxVariantId", null);
                l.put("boxLineId", UUID.randomUUID());
                ls.add(l);
            }
            m.put("lines", ls);
            if (version >= 2) {
                m.put("confirmationId", confirmation);
                m.put("receiptNumber", "OR-000123");
                m.put("arrivedAt", arrivedAt);
                m.put("recordedAt", arrivedAt.plusSeconds(5));
                m.put("paymentMethod", method);
                m.put("paymentGross", total());
                m.put("paymentFee", k(fee));
                m.put("paymentNet", total().subtract(k(fee)));
                m.put("deliveryTownId", UUID.randomUUID());
                m.put("deliveryPayer", payer);
                m.put("republished", republished);
                if (channel != null) {
                    m.put("channel", channel);
                    m.put("occurredAt", saleOccurredAt);
                }
                if (waived != null) {
                    m.put("deliveryWaivedAmount", k(waived));
                }
            }
            m.putAll(overrides);
            return bytes(m);
        }
    }

    /** An {@code order.shipped}: the parcel went at {@code dispatchedAt}, and the fare Oriah paid the courier, if any. */
    static byte[] shipped(UUID order, Instant dispatchedAt, String farePaid, String payer) {
        Map<String, Object> dispatch = new LinkedHashMap<>();
        dispatch.put("dispatchedAt", dispatchedAt);
        if (farePaid != null) {
            dispatch.put("farePaid", k(farePaid));
        }
        dispatch.put("payer", payer);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("orderId", order);
        m.put("keycloakId", "kc-alice-0001");
        m.put("previousStatus", "FULFILLED");
        m.put("newStatus", "SHIPPED");
        m.put("dispatch", dispatch);
        return bytes(m);
    }
}
