package com.oriah.inventory.events;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The fields this consumer READS are the contract with procurement's {@code consignment.received}, so they are pinned
 * here against the literal JSON procurement writes (its field order, its decimal scale), read by a PLAIN mapper with no
 * modules registered: the timestamps are read as text and parsed by hand precisely so reading does not depend on which
 * mapper is injected, or on a java-time module being present.
 */
class ConsignmentReceivedCompatibilityTest {

    private static final ObjectMapper PLAIN = new ObjectMapper();

    /** The ring line of the worked example, exactly as procurement's outbox stores it (after Phase 5's linePosition). */
    private static final String RING = """
            {"consignmentId":"11111111-1111-4111-8111-111111111111","consignmentReference":"C-000045",
             "consignmentLineId":"22222222-2222-4222-8222-222222222222","variantId":"33333333-3333-4333-8333-333333333333",
             "sku":"RING-1a2b3c4d","productTitle":"Aurelia Ring","variantTitle":"Aurelia Ring - RING-1a2b3c4d","kind":"SELLABLE",
             "supplierId":"44444444-4444-4444-8444-444444444444","supplierName":"Dubai Gold","receivedAt":"2026-11-12T10:00:00.123456Z",
             "isEstimated":false,"currency":"ZMW","quantity":7,"goodsTotal":10500.00,"sharedCost":202.90,"landedTotal":10702.90,
             "slices":[{"quantity":4,"unitCost":1528.99},{"quantity":3,"unitCost":1528.98}],"linePosition":1}""";

    @Test
    @DisplayName("the worked example is read field for field, keeping the scale of every amount")
    void theWorkedExample() throws Exception {
        ReceiptSnapshot r = PLAIN.readValue(RING, ReceiptSnapshot.class);

        assertThat(r.consignmentReference()).isEqualTo("C-000045");
        assertThat(r.sku()).isEqualTo("RING-1a2b3c4d");
        assertThat(r.kind()).isEqualTo("SELLABLE");
        assertThat(r.isEstimated()).isFalse();
        assertThat(r.quantity()).isEqualTo(7);
        assertThat(r.goodsTotal()).isEqualTo(new BigDecimal("10500.00"));
        assertThat(r.landedTotal()).isEqualTo(new BigDecimal("10702.90"));
        assertThat(r.slices()).extracting(ReceiptSnapshot.Slice::quantity).containsExactly(4, 3);
        assertThat(r.slices().get(0).unitCost()).isEqualTo(new BigDecimal("1528.99"));
        assertThat(r.linePosition()).isEqualTo(1);
        assertThat(r.receivedAt()).isEqualTo("2026-11-12T10:00:00.123456Z");
        assertThatItIsValid(r);
    }

    @Test
    @DisplayName("a field this consumer does not know is ignored, one it does not receive is null: a newer or older producer still reads")
    void tolerantOfOtherVersions() throws Exception {
        String newer = RING.replace("\"linePosition\":1", "\"linePosition\":1,\"somethingNew\":{\"a\":1}");
        String older = RING.replace(",\"linePosition\":1", "");

        assertThatItIsValid(PLAIN.readValue(newer, ReceiptSnapshot.class));
        ReceiptSnapshot old = PLAIN.readValue(older, ReceiptSnapshot.class);
        assertThat(old.linePosition()).isNull();
        assertThat(old.revision()).isNull();
        assertThatItIsValid(old);
    }

    @Test
    @DisplayName("opening stock: no supplier, an estimate")
    void openingStock() throws Exception {
        String opening = RING.replaceAll("\"supplierId\":\"[^\"]+\"", "\"supplierId\":null")
                .replace("\"supplierName\":\"Dubai Gold\"", "\"supplierName\":null").replace("\"isEstimated\":false", "\"isEstimated\":true");

        ReceiptSnapshot r = PLAIN.readValue(opening, ReceiptSnapshot.class);

        assertThat(r.supplierId()).isNull();
        assertThat(r.supplierName()).isNull();
        assertThat(r.isEstimated()).isTrue();
        assertThatItIsValid(r);
    }

    private static void assertThatItIsValid(ReceiptSnapshot r) {
        r.validate();
        assertThat(r.receivedAtInstant()).isNotNull();
    }
}
