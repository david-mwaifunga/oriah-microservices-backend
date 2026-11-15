package com.oriah.order.integration;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.oriah.order.SaleStubs;
import com.oriah.order.client.CustomerClient;
import com.oriah.quote.Quote;
import com.oriah.quote.QuoteDigest;
import com.oriah.quote.QuoteLine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Correcting who a paid parcel is for and where it goes (Phase 8, slice 8.4d). The customer rings to say the number or the street was wrong. Before the
 * parcel is packed it may be corrected, by an order administrator only (a packer who could redirect a paid parcel could send it to an accomplice), with a
 * reason; the previous details are kept in an append-only history, nothing about them is published, and once the order is packed the label says where it
 * goes, so the correction is closed.
 */
class DeliveryCorrectionTest extends SaleTestBase {

    private static final UUID TOWN = UUID.fromString("33333333-3333-4333-8333-333333333333");

    @Autowired
    private PlatformTransactionManager transactions;

    private static RequestPostProcessor admin() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_ORDER_ADMIN")).jwt(j -> j.subject("kc-maya"));
    }

    private static RequestPostProcessor packer() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_FULFILMENT_AGENT")).jwt(j -> j.subject("kc-dave"));
    }

    private UUID paidDelivery() throws Exception {
        SaleStubs.shippingDelivers(shipping, "YANGO", TOWN, "ORIAH_COLLECTS", "150.00", "ZMW");
        org.mockito.Mockito.doReturn(List.of(new CustomerClient.Address(UUID.fromString(ADDRESS), "Plot 12 Great East Road", "Rhodes Park", "Lusaka",
                "Lusaka Province", "10101", "ZM"))).when(customer).myAddresses();
        String digest = QuoteDigest.of(new Quote(List.of(new QuoteLine(RING, 1, new BigDecimal("4500.00"), new BigDecimal("0.00"), null)), "ZMW", "YANGO",
                new BigDecimal("150.00"), TOWN, "ORIAH_COLLECTS"));
        String body = "{\"shippingAddressId\":\"" + ADDRESS + "\",\"currency\":\"ZMW\",\"deliveryOption\":\"YANGO\",\"deliveryTownId\":\"" + TOWN
                + "\",\"payer\":\"ORIAH_COLLECTS\",\"recipientName\":\"Mulenga Chanda\",\"recipientPhone\":\"0971234567\",\"quoteDigest\":\"" + digest + "\","
                + "\"items\":[{\"variantId\":\"" + RING + "\",\"title\":\"x\",\"quantity\":1,\"unitPrice\":4500.00}]}";
        MvcResult placed = mvc.perform(post("/api/v1/orders").with(alice()).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
        UUID id = UUID.fromString(json.readTree(placed.getResponse().getContentAsString()).get("id").asText());
        confirmed(id, "4650.00");
        return id;
    }

    private static String correction(String name, String phone, String line1, String city, String reason) {
        return "{" + (name == null ? "" : "\"recipientName\":\"" + name + "\",") + (phone == null ? "" : "\"recipientPhone\":\"" + phone + "\",")
                + (line1 == null ? "" : "\"address\":{\"line1\":\"" + line1 + "\",\"city\":\"" + city + "\",\"country\":\"ZM\",\"line2\":\"Block C\"},")
                + (reason == null ? "" : "\"reason\":\"" + reason + "\"") + "}";
    }

    private MvcResult correct(RequestPostProcessor who, UUID id, String body) throws Exception {
        return mvc.perform(post("/api/v1/admin/orders/" + id + "/delivery-details").with(who).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    private JsonNode body(MvcResult r) throws Exception {
        return json.readTree(r.getResponse().getContentAsString());
    }

    private List<Map<String, Object>> history(UUID id) {
        return jdbc.queryForList("SELECT * FROM order_delivery_correction WHERE order_id = ? ORDER BY seq", id);
    }

    @Test
    @DisplayName("an order administrator corrects the recipient and address of a PAID order: the new details replace the old, the old are kept in the history with who and why, and nothing is published")
    void aPaidOrderIsCorrected() throws Exception {
        UUID id = paidDelivery();
        long eventsBefore = jdbc.queryForObject("SELECT count(*) FROM outbox WHERE aggregate_type = 'order' AND aggregate_id = ?", Long.class, id.toString());

        MvcResult done = correct(admin(), id, correction("Chanda Mulenga", "097 765 4321", "Stand 7 Freedom Way", "Kabwe", "the customer rang: wrong street and number"));

        assertThat(done.getResponse().getStatus()).as(done.getResponse().getContentAsString()).isEqualTo(200);
        Map<String, Object> row = row(id);
        assertThat(row.get("recipient_name")).isEqualTo("Chanda Mulenga");
        assertThat(row.get("recipient_phone")).isEqualTo("+260977654321");
        assertThat(row.get("address_line1")).isEqualTo("Stand 7 Freedom Way");
        assertThat(row.get("address_line2")).isEqualTo("Block C");
        assertThat(row.get("address_city")).isEqualTo("Kabwe");
        assertThat(row.get("address_state")).as("a full replacement: what is not sent is not kept").isNull();
        assertThat(row.get("delivery_option_name")).as("the option and the town the price was for are not the person's to change").isEqualTo("Oriah delivery");
        assertThat(body(done).get("delivery").get("recipientName").asText()).isEqualTo("Chanda Mulenga");

        Map<String, Object> was = history(id).get(0);
        assertThat(was.get("seq")).isEqualTo(1);
        assertThat(was.get("previous_recipient_name")).isEqualTo("Mulenga Chanda");
        assertThat(was.get("previous_recipient_phone")).isEqualTo("+260971234567");
        assertThat(was.get("previous_address_line1")).isEqualTo("Plot 12 Great East Road");
        assertThat(was.get("previous_address_city")).isEqualTo("Lusaka");
        assertThat(was.get("reason")).isEqualTo("the customer rang: wrong street and number");
        assertThat(was.get("corrected_by")).isEqualTo("user:kc-maya");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox WHERE aggregate_type = 'order' AND aggregate_id = ?", Long.class, id.toString()))
                .as("no event: personal data never goes into the outbox").isEqualTo(eventsBefore);

        correct(admin(), id, correction("Chanda Mulenga", "0977654321", "Stand 9 Freedom Way", "Kabwe", "one more digit of the stand number"));
        assertThat(history(id)).extracting(h -> h.get("seq")).containsExactly(1, 2);
        assertThat(history(id).get(1).get("previous_address_line1")).isEqualTo("Stand 7 Freedom Way");
    }

    @Test
    @DisplayName("the correction is judged by the same rules as placement: a reason of 5 to 200 characters, a readable name and phone, a whole address; nothing is changed when it is refused")
    void aBadCorrectionIsRefused() throws Exception {
        UUID id = paidDelivery();

        for (String bad : new String[] {
                correction("Chanda Mulenga", "0977654321", "Stand 7", "Kabwe", null),
                correction("Chanda Mulenga", "0977654321", "Stand 7", "Kabwe", "tiny"),
                correction("Chanda Mulenga", "12345", "Stand 7", "Kabwe", "the customer rang"),
                correction("12345", "0977654321", "Stand 7", "Kabwe", "the customer rang"),
                correction("Chanda Mulenga", "0977654321", null, null, "the customer rang"),
                correction(null, null, "Stand 7", "Kabwe", "the customer rang")}) {
            assertThat(correct(admin(), id, bad).getResponse().getStatus()).as(bad).isEqualTo(400);
        }
        assertThat(row(id).get("recipient_name")).isEqualTo("Mulenga Chanda");
        assertThat(history(id)).isEmpty();
    }

    @Test
    @DisplayName("once the order is packed the label says where it goes: the correction is closed (409 delivery-details-locked); an unpaid order, a pickup and an unknown order are refused too")
    void whenItIsClosed() throws Exception {
        UUID packed = paidDelivery();
        mvc.perform(post("/api/v1/admin/orders/" + packed + "/dispatch/pack").with(packer()).contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn();
        SaleStubs.shippingSelfCollect(shipping);
        UUID unpaid = heldOrder();
        UUID pickup = heldOrder(2);
        confirmed(pickup, "9000.00");

        MvcResult locked = correct(admin(), packed, correction("Chanda Mulenga", "0977654321", "Stand 7", "Kabwe", "the customer rang"));
        assertThat(locked.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(locked).get("type").asText()).endsWith("/delivery-details-locked");
        assertThat(row(packed).get("recipient_name")).isEqualTo("Mulenga Chanda");
        assertThat(body(correct(admin(), unpaid, correction("Chanda Mulenga", "0977654321", "Stand 7", "Kabwe", "the customer rang"))).get("type").asText())
                .endsWith("/order-not-paid");
        assertThat(body(correct(admin(), pickup, correction("Chanda Mulenga", "0977654321", "Stand 7", "Kabwe", "the customer rang"))).get("type").asText())
                .endsWith("/no-delivery-details");
        assertThat(correct(admin(), UUID.randomUUID(), correction("Chanda Mulenga", "0977654321", "Stand 7", "Kabwe", "the customer rang")).getResponse().getStatus())
                .isEqualTo(404);
    }

    @Test
    @DisplayName("only an order administrator corrects: a packer, a sales person and a shopper are 403")
    void whoMayCorrect() throws Exception {
        UUID id = paidDelivery();
        String body = correction("Chanda Mulenga", "0977654321", "Stand 7", "Kabwe", "the customer rang");

        assertThat(correct(packer(), id, body).getResponse().getStatus()).isEqualTo(403);
        assertThat(correct(jwt().authorities(new SimpleGrantedAuthority("ROLE_SALES_AGENT")), id, body).getResponse().getStatus()).isEqualTo(403);
        assertThat(correct(alice(), id, body).getResponse().getStatus()).isEqualTo(403);
        assertThat(row(id).get("recipient_name")).isEqualTo("Mulenga Chanda");
    }

    @Test
    @DisplayName("a correction waits for whoever else is changing the order and judges it AFTER: an order packed at that very moment is closed, never corrected from a stale look")
    void aCorrectionWaitsForTheOrderLock() throws Exception {
        UUID id = paidDelivery();
        CountDownLatch holding = new CountDownLatch(1);
        Thread other = new Thread(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            jdbc.queryForObject("SELECT id FROM orders WHERE id = ? FOR UPDATE", UUID.class, id);
            holding.countDown();
            try {
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            jdbc.update("UPDATE orders SET status = 'FULFILLED' WHERE id = ?", id);
        }));
        other.start();
        assertThat(holding.await(20, TimeUnit.SECONDS)).isTrue();

        MvcResult result = correct(admin(), id, correction("Chanda Mulenga", "0977654321", "Stand 7", "Kabwe", "the customer rang"));
        other.join();

        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(409);
        assertThat(body(result).get("type").asText()).endsWith("/delivery-details-locked");
        assertThat(row(id).get("recipient_name")).isEqualTo("Mulenga Chanda");
    }

    @Test
    @DisplayName("the history is append-only: the database refuses to change or delete a correction")
    void theHistoryIsAppendOnly() throws Exception {
        UUID id = paidDelivery();
        correct(admin(), id, correction("Chanda Mulenga", "0977654321", "Stand 7", "Kabwe", "the customer rang"));

        assertThatThrownBy(() -> jdbc.update("UPDATE order_delivery_correction SET reason = 'rewritten history' WHERE order_id = ?", id)).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM order_delivery_correction WHERE order_id = ?", id)).hasMessageContaining("append-only");
    }

    @Test
    @DisplayName("an order that is cancelled forgets who it was for and where it was going: personal data is not kept for an order that will never be sent (the correction history is the audit)")
    void aCancelledOrderForgetsItsDelivery() throws Exception {
        SaleStubs.shippingDelivers(shipping, "YANGO", TOWN, "ORIAH_COLLECTS", "150.00", "ZMW");
        org.mockito.Mockito.doReturn(List.of(new CustomerClient.Address(UUID.fromString(ADDRESS), "Plot 12 Great East Road", null, "Lusaka", null, "10101", "ZM")))
                .when(customer).myAddresses();
        String digest = QuoteDigest.of(new Quote(List.of(new QuoteLine(RING, 1, new BigDecimal("4500.00"), new BigDecimal("0.00"), null)), "ZMW", "YANGO",
                new BigDecimal("150.00"), TOWN, "ORIAH_COLLECTS"));
        String body = "{\"shippingAddressId\":\"" + ADDRESS + "\",\"currency\":\"ZMW\",\"deliveryOption\":\"YANGO\",\"deliveryTownId\":\"" + TOWN
                + "\",\"payer\":\"ORIAH_COLLECTS\",\"recipientName\":\"Mulenga Chanda\",\"recipientPhone\":\"0971234567\",\"quoteDigest\":\"" + digest + "\","
                + "\"items\":[{\"variantId\":\"" + RING + "\",\"title\":\"x\",\"quantity\":1,\"unitPrice\":4500.00}]}";
        UUID id = UUID.fromString(json.readTree(mvc.perform(post("/api/v1/orders").with(alice()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getContentAsString()).get("id").asText());
        assertThat(row(id).get("recipient_name")).isEqualTo("Mulenga Chanda");

        MvcResult cancelled = mvc.perform(post("/api/v1/orders/" + id + "/cancel").with(alice())).andReturn();

        assertThat(cancelled.getResponse().getStatus()).isEqualTo(200);
        Map<String, Object> row = row(id);
        for (String column : List.of("recipient_name", "recipient_phone", "address_line1", "address_line2", "address_city", "address_state", "address_postal_code",
                "address_country")) {
            assertThat(row.get(column)).as(column).isNull();
        }
        assertThat(row.get("delivery_town_name")).as("a town is not personal").isEqualTo("Kabwe");
        assertThat(json.readTree(cancelled.getResponse().getContentAsString()).has("delivery")).isFalse();
    }
}
