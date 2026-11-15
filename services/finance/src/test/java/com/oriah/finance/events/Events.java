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

    // ------------------------------------------------------------------ payment

    /** {@code payment.confirmed}: money reached Oriah on {@code arrivedAt}; {@code attempt} counts the announcements of the same confirmation. */
    static byte[] confirmed(UUID confirmation, UUID order, String gross, String fee, Instant arrivedAt, int attempt) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("confirmationId", confirmation);
        m.put("orderId", order);
        m.put("method", "MTN");
        m.put("reference", "MP2411ABC");
        m.put("gross", k(gross));
        m.put("fee", k(fee));
        m.put("net", k(gross).subtract(k(fee)));
        m.put("currency", "ZMW");
        m.put("arrivedAt", arrivedAt);
        m.put("backdateReason", "the customer paid on a Saturday");
        m.put("confirmedBy", "user:kc-sam");
        m.put("confirmedAt", arrivedAt.plusSeconds(600));
        m.put("attempt", attempt);
        return bytes(m);
    }

    static EventContext confirmedContext(UUID confirmation, Instant arrivedAt) {
        return ctx("payment.confirmed", "payment_confirmation", confirmation, arrivedAt);
    }

    /** {@code payment.voided}: the confirmation was taken back at {@code voidedAt}; its money arrived on {@code arrivedAt} (absent on an event written before the field). */
    static byte[] voided(UUID confirmation, UUID order, String gross, String fee, String outcome, Instant voidedAt, Instant arrivedAt) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("confirmationId", confirmation);
        m.put("orderId", order);
        m.put("method", "MTN");
        m.put("reference", "MP2411ABC");
        m.put("gross", k(gross));
        m.put("fee", k(fee));
        m.put("net", k(gross).subtract(k(fee)));
        m.put("currency", "ZMW");
        m.put("outcome", outcome);
        m.put("reason", "keyed against the wrong order, Mary Banda said so");
        m.put("voidedBy", "user:kc-sam");
        m.put("voidedAt", voidedAt);
        if (arrivedAt != null) {
            m.put("arrivedAt", arrivedAt);
        }
        return bytes(m);
    }

    static EventContext voidedContext(UUID confirmation, Instant voidedAt) {
        return ctx("payment.voided", "payment_confirmation", confirmation, voidedAt);
    }

    /** {@code payment.held}: the money could not pay a sale and is owed back to the customer from {@code arrivedAt}. */
    static byte[] held(UUID confirmation, UUID order, String owed, Instant heldAt, Instant arrivedAt) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("confirmationId", confirmation);
        m.put("orderId", order);
        m.put("reason", "WRONG_AMOUNT");
        m.put("owedGross", k(owed));
        m.put("currency", "ZMW");
        m.put("heldAt", heldAt);
        if (arrivedAt != null) {
            m.put("arrivedAt", arrivedAt);
        }
        return bytes(m);
    }

    static EventContext heldContext(UUID confirmation, Instant heldAt) {
        return ctx("payment.held", "payment_confirmation", confirmation, heldAt);
    }

    // ------------------------------------------------------------------ procurement

    /** One bill for the month inside {@code consignment.period_costs}. */
    record Bill(int position, String costType, String amount, String effectiveOn) {
    }

    /** {@code consignment.period_costs}: revision 1 at receipt, a later one a correction (taking back {@code replaces}). */
    static byte[] periodCosts(UUID consignment, int revision, Instant receivedAt, Instant amendedAt, List<Bill> costs, List<Bill> replaces) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("consignmentId", consignment);
        m.put("consignmentReference", "C-000045");
        m.put("revision", revision);
        m.put("currency", "ZMW");
        m.put("receivedAt", receivedAt);
        m.put("amendedAt", amendedAt);
        m.put("reason", amendedAt == null ? null : "the courier's bill was different");
        m.put("costs", bills(costs));
        m.put("replaces", bills(replaces));
        m.put("recordedAt", amendedAt == null ? receivedAt : amendedAt);
        return bytes(m);
    }

    private static List<Map<String, Object>> bills(List<Bill> bills) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Bill b : bills) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("position", b.position());
            m.put("costType", b.costType());
            m.put("amount", k(b.amount()));
            m.put("effectiveOn", b.effectiveOn());
            out.add(m);
        }
        return out;
    }

    static EventContext periodContext(UUID consignment, Instant occurredAt, int revision) {
        return new EventContext(UUID.randomUUID(), "consignment.period_costs", "consignment", consignment.toString(), revision, 200L, occurredAt, occurredAt.plusSeconds(1),
                "user:kc-maya", "procurement", 1);
    }

    // ------------------------------------------------------------------ inventory

    /** One row of the stock ledger as inventory announces it. */
    static final class Stock {
        /** Unique within the run: the ledger keys a movement's row by this number, so two tests drawing the same one would silently share (or contradict) a row. */
        private static final java.util.concurrent.atomic.AtomicLong NEXT_MOVEMENT = new java.util.concurrent.atomic.AtomicLong(10_000_000);
        long movementId = NEXT_MOVEMENT.incrementAndGet();
        UUID actionId = UUID.randomUUID();
        String actionKind = "SALE";
        String reason = "SALE";
        String role = "ORIGINAL";
        String kind = "SELLABLE";
        UUID productId = UUID.randomUUID();
        int qtyDelta = -1;
        String unitCost = "100.00";
        boolean estimated = false;
        Long reversesMovementId;
        Long replacesMovementId;
        UUID reversesActionId;
        String reversesReason;
        Instant occurredAt = Instant.parse("2026-10-10T10:00:00Z");
        final Map<String, Object> overrides = new LinkedHashMap<>();

        Stock movement(long v) { movementId = v; return this; }
        Stock action(UUID v) { actionId = v; return this; }
        Stock sale(String kind, int qty, String cost) { actionKind = "SALE"; reason = "SALE"; this.kind = kind; qtyDelta = qty; unitCost = cost; return this; }
        Stock writeOff(String reason, int qty, String cost) { actionKind = "WRITE_OFF"; this.reason = reason; qtyDelta = qty; unitCost = cost; return this; }
        Stock takeBack(String reversesReason, int qty, String cost) {
            actionKind = "TAKE_BACK"; reason = "TAKEN_BACK"; qtyDelta = qty; unitCost = cost; this.reversesReason = reversesReason; reversesActionId = UUID.randomUUID();
            reversesMovementId = 1L; return this;
        }
        Stock role(String v) { role = v; return this; }
        Stock product(UUID v) { productId = v; return this; }
        Stock estimated() { estimated = true; return this; }
        Stock at(String instant) { occurredAt = Instant.parse(instant); return this; }
        Stock set(String field, Object value) { overrides.put(field, value); return this; }

        EventContext context() {
            return new EventContext(UUID.randomUUID(), "stock.cost_recorded", "stock_action", actionId.toString(), 1, movementId, occurredAt, occurredAt.plusSeconds(2),
                    "user:kc-maya", "inventory", 1);
        }

        byte[] body() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("movementId", movementId);
            m.put("actionId", actionId);
            m.put("actionKind", actionKind);
            m.put("reason", reason);
            m.put("role", role);
            m.put("variantId", UUID.randomUUID());
            m.put("lotId", UUID.randomUUID());
            m.put("consignmentLineId", UUID.randomUUID());
            m.put("kind", kind);
            m.put("productId", productId);
            m.put("qtyDelta", qtyDelta);
            m.put("unitCost", k(unitCost));
            m.put("currency", "ZMW");
            m.put("estimated", estimated);
            if (reversesMovementId != null) {
                m.put("reversesMovementId", reversesMovementId);
            }
            if (replacesMovementId != null) {
                m.put("replacesMovementId", replacesMovementId);
            }
            if (reversesActionId != null) {
                m.put("reversesActionId", reversesActionId);
                m.put("reversesReason", reversesReason);
            }
            m.put("recordedAt", occurredAt.plusSeconds(2));
            m.putAll(overrides);
            return bytes(m);
        }
    }

    // ------------------------------------------------------------------ consignments, products, orders

    static byte[] consignmentLine(UUID consignment, UUID line, Integer revision, boolean estimated, String landed, boolean supplier) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("consignmentId", consignment);
        m.put("consignmentReference", "C-000045");
        m.put("consignmentLineId", line);
        m.put("variantId", UUID.randomUUID());
        m.put("sku", "RING-1");
        m.put("productTitle", "Aurelia Ring");
        m.put("variantTitle", "Aurelia Ring - M");
        m.put("kind", "SELLABLE");
        m.put("supplierId", supplier ? UUID.randomUUID() : null);
        m.put("supplierName", supplier ? "Dubai Gold" : null);
        m.put("receivedAt", Instant.parse("2026-10-01T08:00:00Z"));
        m.put("isEstimated", estimated);
        m.put("currency", "ZMW");
        m.put("quantity", 7);
        m.put("goodsTotal", k("10500.00"));
        m.put("sharedCost", k("202.90"));
        m.put("landedTotal", k(landed));
        m.put("slices", List.of(Map.of("quantity", 7, "unitCost", k("1528.99"))));
        m.put("linePosition", 1);
        if (revision != null) {
            m.put("revision", revision);
            m.put("reason", "the courier was K500");
            m.put("amendedAt", Instant.parse("2026-10-05T08:00:00Z"));
        }
        return bytes(m);
    }

    static EventContext lineContext(String type, UUID line, long seq) {
        return new EventContext(UUID.randomUUID(), type, "consignment_line", line.toString(), seq, 300L + seq, Instant.parse("2026-10-01T08:00:00Z"),
                Instant.parse("2026-10-01T08:00:01Z"), "user:kc-maya", "procurement", 1);
    }

    static byte[] product(UUID id, String title, String kind) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("title", title);
        m.put("tags", List.of());
        m.put("productType", "RING");
        m.put("status", "ACTIVE");
        m.put("variants", List.of());
        m.put("kind", kind);
        return bytes(m);
    }

    static byte[] productDeleted(UUID id) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        return bytes(m);
    }

    static EventContext productContext(String type, UUID id, long seq) {
        return new EventContext(UUID.randomUUID(), type, "product", id.toString(), seq, 400L + seq, Instant.parse("2026-10-01T08:00:00Z"), Instant.parse("2026-10-01T08:00:01Z"),
                "user:kc-maya", "catalog", 1);
    }

    static byte[] orderCreated(UUID order, String channel) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("orderId", order);
        m.put("keycloakId", channel == null ? "kc-alice-0001" : null);
        m.put("newStatus", "CREATED");
        m.put("total", k("100.00"));
        m.put("currency", "ZMW");
        if (channel != null) {
            m.put("channel", channel);
        }
        return bytes(m);
    }
}
