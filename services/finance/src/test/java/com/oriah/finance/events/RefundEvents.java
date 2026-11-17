package com.oriah.finance.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.oriah.finance.events.Events.Paid;
import com.oriah.finance.events.Events.Sold;

import static com.oriah.finance.events.Events.k;

/**
 * The Phase 12 events as their producers write them (field for field as in the contract files of order and payment): a paid order cancelled before it left
 * ({@code order.sale_cancelled}), pieces that came back ({@code order.return_recorded}), what payment says is owed for one ({@code payment.refund_due_recorded}) and a
 * debt closed without a payout ({@code payment.refund_closed}). Every figure is derived the way the producer derives it, so a test states only what it means to test.
 */
final class RefundEvents {

    private RefundEvents() {
    }

    /** A line of a correction: {@code qty} pieces of a line that was sold. */
    record Back(Sold line, int qty) {
    }

    /** A correction of a paid sale under construction. */
    static final class Corrected {
        UUID id = UUID.randomUUID();
        String kind = "CANCELLATION";
        UUID order;
        UUID confirmation;
        Instant saleArrivedAt;
        Instant occurredAt = Instant.parse("2026-11-05T09:15:00Z");
        Instant envelopeAt = null;
        final List<Back> lines = new ArrayList<>();
        String delivery = "0.00";
        String waived = "0.00";
        final Map<String, Object> overrides = new LinkedHashMap<>();
        final Map<UUID, Map<String, Object>> lineOverrides = new LinkedHashMap<>();

        /** The whole order, as a cancellation before dispatch takes it back: every piece, the delivery charge paid, the waiver if there was one. */
        static Corrected cancelling(Paid paid) {
            Corrected c = of(paid);
            paid.lines.forEach(line -> c.lines.add(new Back(line, line.qty())));
            c.delivery = paid.shipping;
            c.waived = paid.waived == null ? "0.00" : paid.waived;
            return c;
        }

        /** {@code qty} pieces of one line came back after the parcel had gone: no delivery, no waiver. */
        static Corrected returning(Paid paid, Sold line, int qty) {
            Corrected c = of(paid);
            c.kind = "RETURN";
            c.occurredAt = Instant.parse("2026-11-04T10:00:00Z");
            c.lines.add(new Back(line, qty));
            return c;
        }

        private static Corrected of(Paid paid) {
            Corrected c = new Corrected();
            c.order = paid.order;
            c.confirmation = paid.confirmation;
            c.saleArrivedAt = paid.arrivedAt;
            return c;
        }

        Corrected at(String instant) { occurredAt = Instant.parse(instant); return this; }
        Corrected envelope(String instant) { envelopeAt = Instant.parse(instant); return this; }
        Corrected set(String field, Object value) { overrides.put(field, value); return this; }
        Corrected line(Sold line, String field, Object value) { lineOverrides.computeIfAbsent(line.lineId(), id -> new LinkedHashMap<>()).put(field, value); return this; }

        BigDecimal goods() { return lines.stream().map(b -> k(b.line().list()).multiply(BigDecimal.valueOf(b.qty()))).reduce(BigDecimal.ZERO, BigDecimal::add); }
        BigDecimal discounts() { return lines.stream().map(b -> k(b.line().discount()).multiply(BigDecimal.valueOf(b.qty()))).reduce(BigDecimal.ZERO, BigDecimal::add); }
        BigDecimal owed() { return goods().subtract(discounts()).add(k(delivery)); }

        EventContext context() {
            return Events.ctx("CANCELLATION".equals(kind) ? "order.sale_cancelled" : "order.return_recorded", "order", order, envelopeAt != null ? envelopeAt : occurredAt);
        }

        byte[] body() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("correctionId", id);
            m.put("orderId", order);
            m.put("kind", kind);
            m.put("reasonCode", "CANCELLATION".equals(kind) ? "CUSTOMER_ASKED" : "CHANGED_MIND");
            m.put("statusBefore", "CANCELLATION".equals(kind) ? "PAID" : "DELIVERED");
            m.put("statusAfter", "CANCELLATION".equals(kind) ? "CANCELLED" : "DELIVERED");
            m.put("confirmationId", confirmation);
            m.put("receiptNumber", "OR-000123");
            m.put("saleArrivedAt", saleArrivedAt);
            m.put("occurredAt", occurredAt);
            m.put("recordedAt", occurredAt.plusSeconds(2));
            m.put("currency", "ZMW");
            List<Map<String, Object>> ls = new ArrayList<>();
            List<Map<String, Object>> pieces = new ArrayList<>();
            for (Back back : lines) {
                Sold s = back.line();
                Map<String, Object> l = new LinkedHashMap<>();
                l.put("lineId", s.lineId());
                l.put("variantId", s.variantId());
                l.put("productId", s.productId());
                l.put("quantity", back.qty());
                l.put("unitPrice", k(s.list()));
                l.put("discountPerPiece", k(s.discount()));
                l.put("promotionId", null);
                l.put("lineRefund", k(s.list()).subtract(k(s.discount())).multiply(BigDecimal.valueOf(back.qty())));
                l.putAll(lineOverrides.getOrDefault(s.lineId(), Map.of()));
                ls.add(l);
                for (int i = 0; i < back.qty(); i++) {
                    pieces.add(Map.of("pieceId", UUID.randomUUID(), "lineId", s.lineId()));
                }
            }
            m.put("lines", ls);
            if ("RETURN".equals(kind)) {
                m.put("pieces", pieces);
            }
            m.put("goodsAtList", goods());
            m.put("discounts", discounts());
            m.put("deliveryRefunded", k(delivery));
            m.put("waiverTakenBack", k(waived));
            m.put("owedGross", owed());
            m.put("feeBearer", "CUSTOMER");
            m.put("deliveryOption", "OWN_DELIVERY");
            m.put("deliveryPayer", "ORIAH_COLLECTS");
            // What finance must never read: who the customer is.
            m.put("keycloakId", "8c2d4e6f-1a3b-4c5d-9e7f-0a1b2c3d4e5f");
            m.putAll(overrides);
            return Events.bytes(m);
        }
    }

    static byte[] due(UUID dueId, UUID order, UUID confirmation, String owed, String status, Instant owedSince) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dueId", dueId);
        m.put("kind", "CANCELLATION");
        m.put("orderId", order);
        m.put("confirmationId", confirmation);
        m.put("owedGross", k(owed));
        m.put("currency", "ZMW");
        m.put("feeBearer", "CUSTOMER");
        m.put("status", status);
        m.put("owedSince", owedSince);
        m.put("recordedAt", owedSince.plusSeconds(2));
        return Events.bytes(m);
    }

    static EventContext dueContext(UUID dueId, Instant at) {
        return Events.ctx("payment.refund_due_recorded", "refund_due", dueId, at);
    }

    static byte[] closed(UUID dueId, UUID order, UUID confirmation, String owed, Instant closedAt) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dueId", dueId);
        m.put("orderId", order);
        m.put("confirmationId", confirmation);
        m.put("owedGross", k(owed));
        m.put("currency", "ZMW");
        m.put("outcome", "MONEY_NEVER_ARRIVED");
        m.put("closedAt", closedAt);
        m.put("recordedAt", closedAt.plusSeconds(1));
        return Events.bytes(m);
    }

    static EventContext closedContext(UUID dueId, Instant at) {
        return Events.ctx("payment.refund_closed", "refund_due", dueId, at);
    }
}
