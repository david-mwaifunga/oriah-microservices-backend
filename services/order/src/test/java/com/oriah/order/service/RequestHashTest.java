package com.oriah.order.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.oriah.order.dto.RecordOrderRequest;
import com.oriah.order.dto.RecordOrderRequest.Address;
import com.oriah.order.dto.RecordOrderRequest.Caller;
import com.oriah.order.dto.RecordOrderRequest.Delivery;
import com.oriah.order.dto.RecordOrderRequest.DeliveryMode;
import com.oriah.order.dto.RecordOrderRequest.Line;
import com.oriah.order.dto.RecordOrderRequest.Recipient;
import com.oriah.order.model.SaleKind;

/**
 * The fingerprint of a recording request (Phase 9): the same Idempotency-Key with the same request is the same order, with another request it is refused. It
 * covers everything that makes the order (the caller, every line with its box, the delivery, the recipient, the address, the date, the slip) and nothing that
 * only confirms it (the total the person read out, the reason for a late date): a repeat after a lost reply may differ in those and is still the same order.
 */
class RequestHashTest {

    private static final UUID RING = UUID.fromString("22222222-3333-4444-5555-666666666666");
    private static final UUID BOX = UUID.fromString("b0b0b0b0-0000-4000-8000-000000000001");
    private static final UUID TOWN = UUID.fromString("33333333-3333-4333-8333-333333333333");

    private static RecordOrderRequest request(Line line, Delivery delivery, Instant at, String slip, String reason, String total) {
        return new RecordOrderRequest(at == null ? SaleKind.LIVE_PHONE : SaleKind.LATE_PAPER, new Caller("Mulenga Chanda", "0971234567"), List.of(line), delivery,
                new Recipient("Chipo Banda", "0977000111"), new Address("Plot 12", null, "Kabwe", null, null, "ZM"), at, reason, slip, new BigDecimal(total), null, null, null);
    }

    private static Line ring(int quantity, UUID box) {
        return new Line(RING, quantity, box, null, null);
    }

    private static Delivery agreed(String amount) {
        return new Delivery(DeliveryMode.AGREED, "OWN_DELIVERY", TOWN, "ORIAH_COLLECTS", new BigDecimal(amount), "a regular customer");
    }

    private static String hash(RecordOrderRequest r) {
        return RequestHash.of(r, "Mulenga Chanda", "+260971234567", "Chipo Banda", "+260977000111");
    }

    @Test
    @DisplayName("the hash is 64 lower-case hex characters and the same request always gives the same one")
    void theShape() {
        RecordOrderRequest r = request(ring(1, null), agreed("60.00"), null, null, null, "4560.00");

        assertThat(hash(r)).matches("^[0-9a-f]{64}$").isEqualTo(hash(request(ring(1, null), agreed("60.00"), null, null, null, "4560.00")));
    }

    @Test
    @DisplayName("what the total says and the reason for a late date do not matter to a repeat: a lost reply is retried with whatever the screen sends")
    void whatConfirmsIsNotPartOfIt() {
        Instant at = Instant.parse("2026-11-10T09:00:00Z");
        String base = hash(request(ring(1, null), agreed("60.00"), at, "P-1", "one reason", "4560.00"));

        assertThat(hash(request(ring(1, null), agreed("60.00"), at, "P-1", "another reason entirely", "4999.00"))).isEqualTo(base);
    }

    @Test
    @DisplayName("every part that makes the order changes the hash: the box, the quantity, the amount agreed, its reason, the date, the slip, the caller and the recipient")
    void whatMakesTheOrderIsPartOfIt() {
        Instant at = Instant.parse("2026-11-10T09:00:00Z");
        String base = hash(request(ring(1, null), agreed("60.00"), at, "P-1", "a reason", "4560.00"));

        assertThat(hash(request(ring(1, BOX), agreed("60.00"), at, "P-1", "a reason", "4560.00"))).as("box").isNotEqualTo(base);
        assertThat(hash(request(ring(2, null), agreed("60.00"), at, "P-1", "a reason", "4560.00"))).as("quantity").isNotEqualTo(base);
        assertThat(hash(request(ring(1, null), agreed("70.00"), at, "P-1", "a reason", "4560.00"))).as("amount").isNotEqualTo(base);
        assertThat(hash(request(ring(1, null), new Delivery(DeliveryMode.AGREED, "OWN_DELIVERY", TOWN, "ORIAH_COLLECTS", new BigDecimal("60.00"), "another reason"), at, "P-1",
                "a reason", "4560.00"))).as("agreed reason").isNotEqualTo(base);
        assertThat(hash(request(ring(1, null), agreed("60.00"), at.plusSeconds(1), "P-1", "a reason", "4560.00"))).as("date").isNotEqualTo(base);
        assertThat(hash(request(ring(1, null), agreed("60.00"), at, "P-2", "a reason", "4560.00"))).as("slip").isNotEqualTo(base);
        assertThat(RequestHash.of(request(ring(1, null), agreed("60.00"), at, "P-1", "a reason", "4560.00"), "Someone Else", "+260971234567", "Chipo Banda", "+260977000111"))
                .as("caller").isNotEqualTo(base);
        assertThat(RequestHash.of(request(ring(1, null), agreed("60.00"), at, "P-1", "a reason", "4560.00"), "Mulenga Chanda", "+260971234567", "Chipo Banda", "+260977000222"))
                .as("recipient phone").isNotEqualTo(base);
    }

    @Test
    @DisplayName("two lines are not one line of the same piece twice, and the order of lines matters")
    void lines() {
        RecordOrderRequest one = request(ring(2, null), agreed("60.00"), null, null, null, "9060.00");
        RecordOrderRequest two = new RecordOrderRequest(SaleKind.LIVE_PHONE, new Caller("Mulenga Chanda", "0971234567"), List.of(ring(1, null), ring(1, null)), agreed("60.00"),
                new Recipient("Chipo Banda", "0977000111"), new Address("Plot 12", null, "Kabwe", null, null, "ZM"), null, null, null, new BigDecimal("9060.00"), null, null, null);

        assertThat(hash(one)).isNotEqualTo(hash(two));
    }

    @Test
    @DisplayName("a field that contains the separator cannot pass for two fields")
    void aSeparatorInsideAFieldIsNotAFieldBoundary() {
        RecordOrderRequest r = request(ring(1, null), agreed("60.00"), null, "P-1", null, "4560.00");

        assertThat(RequestHash.of(r, "a|b", "c", "x", "y")).isNotEqualTo(RequestHash.of(r, "a", "b|c", "x", "y"));
    }
}
