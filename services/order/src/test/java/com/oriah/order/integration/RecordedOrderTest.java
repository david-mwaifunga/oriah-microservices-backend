package com.oriah.order.integration;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.fasterxml.jackson.databind.JsonNode;
import com.oriah.order.SaleStubs;
import com.oriah.order.client.CatalogAsOfClient;
import com.oriah.order.client.InventoryClient;
import com.oriah.order.client.ShippingClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Recording an order for a caller with no account (Phase 9, slice 9.8): staff (SALES_AGENT) record a sale taken by phone or WhatsApp. The server prices every
 * line and the delivery itself (the person types no price, discount or total: Q12), holds the pieces like any order, and answers with what the caller owes
 * and until when the piece is held. Real Postgres; catalog, shipping, inventory and customer are stood in for.
 */
class RecordedOrderTest extends SaleTestBase {

    private static final String PATH = "/api/v1/admin/orders/recorded";
    private static final UUID TOWN = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID PRICE_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");

    private static RequestPostProcessor sales() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_SALES_AGENT")).jwt(j -> j.subject("kc-sam").tokenValue("sam-token"));
    }

    private static RequestPostProcessor owner() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_SALES_AGENT"), new SimpleGrantedAuthority("ROLE_BACKDATE_SALE")).jwt(j -> j.subject("kc-maya").tokenValue("maya-token"));
    }

    @BeforeEach
    void recordedStubs() {
        priced(RING, "4500.00", null, null);
        SaleStubs.shippingPlaces(shipping, TOWN);
        SaleStubs.shippingNotOffered(shipping);   // no published price for any town unless a test says so: an agreed delivery has no quote to compare with
    }

    /** Catalog's as-of read answering this ring at this list price (and discount per piece, with a promotion) for any instant. */
    private void priced(UUID variant, String price, String discount, UUID promotion) {
        BigDecimal off = discount == null ? new BigDecimal("0.00") : new BigDecimal(discount);
        doReturn(new CatalogAsOfClient.AsOfResponse(Instant.now(), List.of(new CatalogAsOfClient.AsOfEntry(variant, "PRICED", PRODUCT, "Aurelia 18k",
                "Aurelia 18k / Ring Size M", new BigDecimal(price), "ZMW", PRICE_ID, off, promotion, new BigDecimal(price).subtract(off)))))
                .when(catalogAsOf).priceAsOf(any());
    }

    private static String line(UUID variant, int quantity) {
        return "{\"variantId\":\"" + variant + "\",\"quantity\":" + quantity + "}";
    }

    private static String pickupBody(String key, String total) {
        return "{\"kind\":\"LIVE_PHONE\",\"caller\":{\"name\":\"Mulenga Chanda\",\"phone\":\"097 123 4567\"},\"lines\":[" + line(RING, 1) + "],"
                + "\"delivery\":{\"mode\":\"PICKUP\"},\"expectedTotal\":" + total + "}";
    }

    private static String agreedBody(String option, String payer, String amount, String reason, String total) {
        return "{\"kind\":\"LIVE_PHONE\",\"caller\":{\"name\":\"Mulenga Chanda\",\"phone\":\"097 123 4567\"},\"lines\":[" + line(RING, 1) + "],"
                + "\"delivery\":{\"mode\":\"AGREED\",\"optionCode\":\"" + option + "\",\"townId\":\"" + TOWN + "\",\"payer\":\"" + payer + "\""
                + (amount == null ? "" : ",\"agreedAmount\":" + amount) + (reason == null ? "" : ",\"agreedReason\":\"" + reason + "\"") + "},"
                + "\"address\":{\"line1\":\"Plot 12 Great East Road\",\"city\":\"Kabwe\",\"country\":\"ZM\"},\"expectedTotal\":" + total + "}";
    }

    private MvcResult record(RequestPostProcessor who, String key, String body) throws Exception {
        var request = post(PATH).with(who).contentType(MediaType.APPLICATION_JSON).content(body);
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return mvc.perform(request).andReturn();
    }

    private JsonNode ok(MvcResult result, int status) throws Exception {
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(status);
        return json.readTree(result.getResponse().getContentAsString());
    }

    private static String type(JsonNode problem) {
        return problem.get("type").asText();
    }

    // ------------------------------------------------------------------ a live phone sale

    @Test
    @DisplayName("a pickup is recorded for a caller with no account: 201, CREATED and HELD for ten minutes, priced by the server, kept with its recorder and its caller")
    void aPickupIsRecorded() throws Exception {
        JsonNode order = ok(record(sales(), "k-1", pickupBody("k-1", "4500.00")), 201);

        assertThat(order.get("status").asText()).isEqualTo("CREATED");
        assertThat(order.get("channel").asText()).isEqualTo("PHONE");
        assertThat(order.get("kind").asText()).isEqualTo("LIVE_PHONE");
        assertThat(order.get("total").decimalValue()).isEqualByComparingTo("4500.00");
        assertThat(order.get("replay").asBoolean()).isFalse();
        assertThat(order.get("lines").get(0).get("unitPrice").decimalValue()).isEqualByComparingTo("4500.00");
        assertThat(Instant.parse(order.get("holdExpiresAt").asText())).isBefore(Instant.now().plus(Duration.ofMinutes(11)));
        UUID id = UUID.fromString(order.get("id").asText());
        var row = row(id);
        assertThat(row.get("keycloak_id")).isNull();
        assertThat(row.get("channel")).isEqualTo("PHONE");
        assertThat(row.get("recorded_by")).isEqualTo("user:kc-sam");
        assertThat(row.get("caller_name")).isEqualTo("Mulenga Chanda");
        assertThat(row.get("caller_phone")).as("kept the way Zambians type it, tidied").isEqualTo("+260971234567");
        assertThat(row.get("hold_state")).isEqualTo("HELD");
        assertThat(row.get("idempotency_key")).isEqualTo("k-1");
        assertThat(row.get("sale_kind")).isEqualTo("LIVE_PHONE");
        verify(customer, never()).myAddresses();
        verify(inventory, times(1)).reserve(any());
        assertThat(events(id, "order.created")).hasSize(1);
        assertThat(payloadOf(events(id, "order.created").get(0)).get("channel").asText()).isEqualTo("PHONE");
    }

    @Test
    @DisplayName("the server prices the lines: a promotion's discount per piece is kept on the line, and the person typed none of it")
    void thePromotionAppliesByItself() throws Exception {
        UUID promotion = UUID.randomUUID();
        priced(RING, "4500.00", "500.00", promotion);

        JsonNode order = ok(record(sales(), "k-promo", pickupBody("k-promo", "4000.00")), 201);

        assertThat(order.get("subtotal").decimalValue()).isEqualByComparingTo("4500.00");
        assertThat(order.get("discountAmount").decimalValue()).isEqualByComparingTo("500.00");
        assertThat(order.get("lines").get(0).get("discountPerPiece").decimalValue()).isEqualByComparingTo("500.00");
        var item = jdbc.queryForMap("SELECT discount_per_piece, promotion_id FROM order_items WHERE order_id = ?", UUID.fromString(order.get("id").asText()));
        assertThat((BigDecimal) item.get("discount_per_piece")).isEqualByComparingTo("500.00");
        assertThat(item.get("promotion_id")).isEqualTo(promotion);
    }

    @Test
    @DisplayName("nobody types a price, a discount, a currency or a total: a body that carries one is refused with 400 and nothing is placed")
    void noPriceCanBeTyped() throws Exception {
        for (String extra : new String[] {",\"total\":1.00", ",\"discount\":100", ",\"shippingAmount\":0"}) {
            String body = pickupBody("k-typed", "4500.00");
            body = body.substring(0, body.length() - 1) + extra + "}";
            assertThat(record(sales(), "k-typed", body).getResponse().getStatus()).as(extra).isEqualTo(400);
        }
        String lineWithPrice = pickupBody("k-typed", "4500.00").replace("\"quantity\":1}", "\"quantity\":1,\"unitPrice\":1.00}");
        assertThat(record(sales(), "k-typed", lineWithPrice).getResponse().getStatus()).isEqualTo(400);
        String lineWithDiscount = pickupBody("k-typed", "4500.00").replace("\"quantity\":1}", "\"quantity\":1,\"discountPerPiece\":1.00}");
        assertThat(record(sales(), "k-typed", lineWithDiscount).getResponse().getStatus()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders", Integer.class)).isZero();
    }

    @Test
    @DisplayName("the person confirms the total they read to the caller: a missing expected total is 400, a different one is 409 total-differs naming the real total, and nothing is held")
    void theTotalIsConfirmed() throws Exception {
        String missing = pickupBody("k-t", "4500.00").replace(",\"expectedTotal\":4500.00", "");
        MvcResult noTotal = record(sales(), "k-t", missing);
        assertThat(noTotal.getResponse().getStatus()).isEqualTo(400);
        assertThat(noTotal.getResponse().getContentAsString()).contains("expectedTotal");

        JsonNode wrong = ok(record(sales(), "k-t", pickupBody("k-t", "4400.00")), 409);

        assertThat(type(wrong)).endsWith("/total-differs");
        assertThat(wrong.get("total").decimalValue()).isEqualByComparingTo("4500.00");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders", Integer.class)).isZero();
        verify(inventory, never()).reserve(any());
    }

    @Test
    @DisplayName("a phone order that would cost nothing cannot be recorded (payment takes no confirmation below K0.01): 409 zero-total-not-supported")
    void aZeroTotalIsRefused() throws Exception {
        priced(RING, "4500.00", "4500.00", UUID.randomUUID());

        JsonNode refused = ok(record(sales(), "k-zero", pickupBody("k-zero", "0.00")), 409);

        assertThat(type(refused)).endsWith("/zero-total-not-supported");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders", Integer.class)).isZero();
    }

    @Test
    @DisplayName("who may record: SALES_AGENT (and so an owner); a shopper, a packer and an order administrator alone are 403, and no token is 401")
    void whoMayRecord() throws Exception {
        RequestPostProcessor shopper = jwt().authorities(ROLE_USER).jwt(j -> j.subject("kc-alice"));
        RequestPostProcessor packer = jwt().authorities(new SimpleGrantedAuthority("ROLE_FULFILMENT_AGENT")).jwt(j -> j.subject("kc-dave"));
        RequestPostProcessor admin = jwt().authorities(new SimpleGrantedAuthority("ROLE_ORDER_ADMIN")).jwt(j -> j.subject("kc-ada"));

        for (RequestPostProcessor who : new RequestPostProcessor[] {shopper, packer, admin}) {
            assertThat(record(who, "k-who", pickupBody("k-who", "4500.00")).getResponse().getStatus()).isEqualTo(403);
        }
        assertThat(mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", "k-who").content(pickupBody("k-who", "4500.00")))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(record(sales(), "k-who", pickupBody("k-who", "4500.00")).getResponse().getStatus()).isEqualTo(201);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("an Idempotency-Key is required (1 to 128 characters): without one, or too long, it is 400 and nothing is held")
    void aKeyIsRequired() throws Exception {
        assertThat(record(sales(), null, pickupBody("x", "4500.00")).getResponse().getStatus()).isEqualTo(400);
        assertThat(record(sales(), "k".repeat(129), pickupBody("x", "4500.00")).getResponse().getStatus()).isEqualTo(400);
        verify(inventory, never()).reserve(any());
    }

    // ------------------------------------------------------------------ once, however often it is asked

    @Test
    @DisplayName("the same key with the same request again is 200 with the same order and no second hold, even when the price has moved since; another request under the key is 409")
    void aRepeatIsTheSameOrder() throws Exception {
        JsonNode first = ok(record(sales(), "k-rep", pickupBody("k-rep", "4500.00")), 201);
        priced(RING, "5000.00", null, null);

        JsonNode again = ok(record(sales(), "k-rep", pickupBody("k-rep", "4500.00")), 200);
        JsonNode other = ok(record(sales(), "k-rep", pickupBody("k-rep", "4500.00").replace("Mulenga Chanda", "Someone Else")), 409);

        assertThat(again.get("id").asText()).isEqualTo(first.get("id").asText());
        assertThat(again.get("replay").asBoolean()).isTrue();
        assertThat(again.get("total").decimalValue()).as("the order as it was recorded, not as priced now").isEqualByComparingTo("4500.00");
        assertThat(type(other)).endsWith("/idempotency-key-reused");
        verify(inventory, times(1)).reserve(any());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("a key is used once whoever recorded: another member of staff sending the same key and request gets the same order, and the same key for another request is 409")
    void aKeyIsGlobalAmongStaff() throws Exception {
        JsonNode first = ok(record(sales(), "k-two", pickupBody("k-two", "4500.00")), 201);

        JsonNode second = ok(record(owner(), "k-two", pickupBody("k-two", "4500.00")), 200);
        JsonNode other = ok(record(owner(), "k-two", pickupBody("k-two", "4500.00").replace("Mulenga", "Chipo")), 409);

        assertThat(second.get("id").asText()).isEqualTo(first.get("id").asText());
        assertThat(type(other)).endsWith("/idempotency-key-reused");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("the request that makes the order is what a repeat must match: the same key with another box or another quantity is 409")
    void theHashCoversWhatMatters() throws Exception {
        ok(record(sales(), "k-h", pickupBody("k-h", "4500.00")), 201);
        UUID box = UUID.randomUUID();

        String otherBox = pickupBody("k-h", "4500.00").replace("\"quantity\":1}", "\"quantity\":1,\"boxVariantId\":\"" + box + "\"}");
        String otherQuantity = pickupBody("k-h", "9000.00").replace("\"quantity\":1}", "\"quantity\":2}");

        assertThat(type(ok(record(sales(), "k-h", otherBox), 409))).endsWith("/idempotency-key-reused");
        assertThat(type(ok(record(sales(), "k-h", otherQuantity), 409))).endsWith("/idempotency-key-reused");
    }

    @Autowired
    private com.oriah.order.service.RecordedOrderWriter writer;
    @Autowired
    private com.oriah.order.service.RecordedPricing recordedPricing;

    @Test
    @DisplayName("the look under the lock is a second line of defence: two requests with one key that both passed the first look (one made the order while the other priced) cannot make two orders or change the first")
    void theWriterRefusesAnotherRequestUnderTheKey() throws Exception {
        JsonNode first = ok(record(sales(), "k-race", pickupBody("k-race", "4500.00")), 201);
        var request = new com.oriah.order.dto.RecordOrderRequest(com.oriah.order.model.SaleKind.LIVE_PHONE, new com.oriah.order.dto.RecordOrderRequest.Caller("Someone Else", null),
                List.of(new com.oriah.order.dto.RecordOrderRequest.Line(RING, 1, null, null, null)), new com.oriah.order.dto.RecordOrderRequest.Delivery(
                        com.oriah.order.dto.RecordOrderRequest.DeliveryMode.PICKUP, null, null, null, null, null), null, null, null, null, null, new BigDecimal("4500.00"), null, null, null);
        var priced = recordedPricing.price(request, Instant.now(), com.oriah.order.model.SaleKind.LIVE_PHONE);
        var other = new com.oriah.order.model.RecordedSale("user:kc-sam", "Someone Else", null, com.oriah.order.model.SaleKind.LIVE_PHONE, Instant.now(), null, null, "f".repeat(64), null, null);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> writer.begin("k-race", other, priced, request, null))
                .isInstanceOfSatisfying(com.oriah.order.exception.SalesProblem.class, p -> assertThat(p.code()).isEqualTo("idempotency-key-reused"));
        var same = writer.begin("k-race", new com.oriah.order.model.RecordedSale("user:kc-sam", "Mulenga Chanda", "+260971234567", com.oriah.order.model.SaleKind.LIVE_PHONE, Instant.now(),
                null, null, (String) row(UUID.fromString(first.get("id").asText())).get("request_hash"), null, null), priced, request, null);
        assertThat(same.created()).isFalse();
        assertThat(same.order().id().toString()).isEqualTo(first.get("id").asText());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders", Integer.class)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ the box

    @Test
    @DisplayName("a box chosen on the line reaches inventory as the box to take; none chosen leaves it to inventory's default")
    void theBoxIsAsked() throws Exception {
        UUID box = UUID.randomUUID();
        ok(record(sales(), "k-box", pickupBody("k-box", "4500.00").replace("\"quantity\":1}", "\"quantity\":1,\"boxVariantId\":\"" + box + "\"}")), 201);
        ok(record(sales(), "k-nobox", pickupBody("k-nobox", "4500.00").replace("Mulenga Chanda", "Chipo Banda")), 201);

        ArgumentCaptor<InventoryClient.ReserveAsk> asks = ArgumentCaptor.forClass(InventoryClient.ReserveAsk.class);
        verify(inventory, times(2)).reserve(asks.capture());
        assertThat(asks.getAllValues().get(0).lines().get(0).boxVariantId()).isEqualTo(box);
        assertThat(asks.getAllValues().get(1).lines().get(0).boxVariantId()).isNull();
    }

    @Test
    @DisplayName("a box that has run out is 409 insufficient-stock naming the box, and nothing is kept")
    void aBoxThatRanOut() throws Exception {
        UUID box = UUID.randomUUID();
        SaleStubs.inventoryRefuses(inventory, 409, SaleStubs.shortage(box, "BOX", 1, 0));

        MvcResult refused = record(sales(), "k-short", pickupBody("k-short", "4500.00"));

        assertThat(refused.getResponse().getStatus()).isEqualTo(409);
        assertThat(refused.getResponse().getContentAsString()).contains("insufficient-stock").contains(box.toString()).contains("BOX");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders", Integer.class)).as("the order never existed").isZero();
    }

    // ------------------------------------------------------------------ delivery

    @Test
    @DisplayName("a delivery agreed by phone is the order's charge, written once: the total moves by exactly it, who the parcel is for is the caller unless said, and the address is kept")
    void anAgreedDelivery() throws Exception {
        JsonNode order = ok(record(sales(), "k-agreed", agreedBody("OWN_DELIVERY", "ORIAH_COLLECTS", "60.00", null, "4560.00")), 201);

        assertThat(order.get("total").decimalValue()).isEqualByComparingTo("4560.00");
        assertThat(order.get("delivery").get("amount").decimalValue()).isEqualByComparingTo("60.00");
        assertThat(order.get("delivery").get("source").asText()).isEqualTo("AGREED");
        var row = row(UUID.fromString(order.get("id").asText()));
        assertThat(row.get("delivery_price_source")).isEqualTo("AGREED");
        assertThat((BigDecimal) row.get("delivery_agreed_amount")).isEqualByComparingTo("60.00");
        assertThat((BigDecimal) row.get("shipping_amount")).isEqualByComparingTo("60.00");
        assertThat(row.get("recipient_name")).as("the caller, unless someone else is named").isEqualTo("Mulenga Chanda");
        assertThat(row.get("recipient_phone")).isEqualTo("+260971234567");
        assertThat(row.get("address_line1")).isEqualTo("Plot 12 Great East Road");
        assertThat(row.get("address_country")).isEqualTo("ZM");
        assertThat(row.get("delivery_town_id")).isEqualTo(TOWN);
        assertThat(row.get("delivery_payer")).isEqualTo("ORIAH_COLLECTS");
    }

    @Test
    @DisplayName("an agreed delivery that does not fit is refused in plain words: own delivery paid by the recipient, a charge on a recipient-paid courier, no charge, no address, a town or option that is not on the list")
    void anAgreedDeliveryMustFit() throws Exception {
        for (String[] bad : new String[][] {
                {agreedBody("OWN_DELIVERY", "RECIPIENT_PAYS", null, null, "4500.00"), "invalid-delivery"},
                {agreedBody("YANGO", "RECIPIENT_PAYS", "50.00", null, "4550.00"), "invalid-delivery"},
                {agreedBody("YANGO", "ORIAH_COLLECTS", null, null, "4500.00"), "invalid-delivery"},
                {agreedBody("YANGO", "ORIAH_COLLECTS", "0.00", null, "4500.00"), "invalid-delivery"}}) {
            JsonNode problem = ok(record(sales(), "k-bad-" + bad[1] + bad[0].hashCode(), bad[0]), 400);
            assertThat(type(problem)).endsWith("/" + bad[1]);
        }
        String noAddress = agreedBody("YANGO", "ORIAH_COLLECTS", "80.00", null, "4580.00").replace(",\"address\":{\"line1\":\"Plot 12 Great East Road\",\"city\":\"Kabwe\",\"country\":\"ZM\"}", "");
        assertThat(record(sales(), "k-noaddr", noAddress).getResponse().getStatus()).isEqualTo(400);
        String unknownTown = agreedBody("YANGO", "ORIAH_COLLECTS", "80.00", null, "4580.00").replace(TOWN.toString(), UUID.randomUUID().toString());
        assertThat(type(ok(record(sales(), "k-town", unknownTown), 409))).endsWith("/delivery-place-not-offered");
        String unknownOption = agreedBody("CARRIER_PIGEON", "ORIAH_COLLECTS", "80.00", null, "4580.00");
        assertThat(type(ok(record(sales(), "k-option", unknownOption), 409))).endsWith("/delivery-place-not-offered");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders", Integer.class)).isZero();
    }

    @Test
    @DisplayName("an agreed amount that differs from the shop's own quote for that town needs a reason, higher or lower; a quote that agrees needs none")
    void aDifferenceFromTheQuoteNeedsAReason() throws Exception {
        SaleStubs.shippingDelivers(shipping, "OWN_DELIVERY", TOWN, "ORIAH_COLLECTS", "100.00", "ZMW");   // the shop's own quote for this town: K100.00

        JsonNode lower = ok(record(sales(), "k-low", agreedBody("OWN_DELIVERY", "ORIAH_COLLECTS", "60.00", null, "4560.00")), 400);
        JsonNode higher = ok(record(sales(), "k-high", agreedBody("OWN_DELIVERY", "ORIAH_COLLECTS", "150.00", null, "4650.00")), 400);
        JsonNode withReason = ok(record(sales(), "k-why", agreedBody("OWN_DELIVERY", "ORIAH_COLLECTS", "60.00", "a regular customer who lives nearby", "4560.00")), 201);
        JsonNode same = ok(record(sales(), "k-same", agreedBody("OWN_DELIVERY", "ORIAH_COLLECTS", "100.00", null, "4600.00").replace("Mulenga Chanda", "Chipo Banda")), 201);

        assertThat(type(lower)).endsWith("/delivery-reason-required");
        assertThat(type(higher)).endsWith("/delivery-reason-required");
        assertThat(row(UUID.fromString(withReason.get("id").asText())).get("delivery_agreed_reason")).isEqualTo("a regular customer who lives nearby");
        assertThat(row(UUID.fromString(same.get("id").asText())).get("delivery_agreed_reason")).isNull();
    }

    @Test
    @DisplayName("a pickup carries no recipient and no address: either one with a pickup is 400")
    void aPickupTakesNoRecipient() throws Exception {
        String withRecipient = pickupBody("k-pr", "4500.00").replace("\"expectedTotal\"", "\"recipient\":{\"name\":\"A B\",\"phone\":\"0971234567\"},\"expectedTotal\"");
        String withAddress = pickupBody("k-pa", "4500.00").replace("\"expectedTotal\"", "\"address\":{\"line1\":\"x\",\"city\":\"y\",\"country\":\"ZM\"},\"expectedTotal\"");

        assertThat(record(sales(), "k-pr", withRecipient).getResponse().getStatus()).isEqualTo(400);
        assertThat(record(sales(), "k-pa", withAddress).getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    @DisplayName("a counter pickup needs no phone number, a delivery does; a number that cannot be read is refused in plain words and the caller's name is tidied")
    void theCallersPhone() throws Exception {
        String noPhone = pickupBody("k-np", "4500.00").replace(",\"phone\":\"097 123 4567\"", "");
        assertThat(row(UUID.fromString(ok(record(sales(), "k-np", noPhone), 201).get("id").asText())).get("caller_phone")).isNull();

        String deliveryNoPhone = agreedBody("YANGO", "ORIAH_COLLECTS", "80.00", null, "4580.00").replace(",\"phone\":\"097 123 4567\"", "");
        assertThat(record(sales(), "k-dnp", deliveryNoPhone).getResponse().getStatus()).isEqualTo(400);
        String unreadable = pickupBody("k-bp", "4500.00").replace("097 123 4567", "12345");
        MvcResult bad = record(sales(), "k-bp", unreadable);
        assertThat(bad.getResponse().getStatus()).isEqualTo(400);
        assertThat(bad.getResponse().getContentAsString()).contains("phone");
    }

    // ------------------------------------------------------------------ nothing personal leaves

    @Test
    @DisplayName("the caller's name, phone and address are in no event and in no log line of the recording")
    void nothingPersonalLeaves() throws Exception {
        JsonNode order = ok(record(sales(), "k-pii", agreedBody("OWN_DELIVERY", "ORIAH_COLLECTS", "60.00", null, "4560.00")), 201);
        UUID id = UUID.fromString(order.get("id").asText());

        for (String text : jdbc.queryForList("SELECT payload::text FROM outbox WHERE aggregate_id = ?", String.class, id.toString())) {
            assertThat(text).doesNotContain("Mulenga").doesNotContain("971234567").doesNotContain("Great East").doesNotContain("kc-sam");
        }
    }

    // ------------------------------------------------------------------ what the panel found: the date a price is asked at, a published delivery, a lost reply, a named recipient

    @Test
    @DisplayName("a live sale is priced at the moment it is recorded, and dated then")
    void aLiveSaleIsPricedNow() throws Exception {
        Instant before = Instant.now();

        JsonNode order = ok(record(sales(), "k-live-at", pickupBody("k-live-at", "4500.00")), 201);

        ArgumentCaptor<CatalogAsOfClient.AsOfAsk> ask = ArgumentCaptor.forClass(CatalogAsOfClient.AsOfAsk.class);
        verify(catalogAsOf).priceAsOf(ask.capture());
        assertThat(ask.getValue().at()).isBetween(before.minusSeconds(1), Instant.now().plusSeconds(1));
        assertThat(Instant.parse(order.get("occurredAt").asText())).isBetween(before.minusSeconds(1), Instant.now().plusSeconds(1));
    }

    @Test
    @DisplayName("a paper sale dated a few minutes ahead (a clock running fast, which the date rule allows for) is dated AND priced at this moment, one instant for both: catalog allows 30 seconds, and a sale must never be priced on one day and counted on the next")
    void aPaperSaleAheadByAFewMinutesIsPricedNow() throws Exception {
        Instant ahead = Instant.now().plusSeconds(180);
        String body = "{\"kind\":\"LATE_PAPER\",\"caller\":{\"name\":\"Mulenga Chanda\"},\"lines\":[" + line(RING, 1) + "],\"delivery\":{\"mode\":\"PICKUP\"},"
                + "\"occurredAt\":\"" + ahead + "\",\"backdateReason\":\"written on paper a moment ago\",\"expectedTotal\":4500.00}";

        JsonNode order = ok(record(sales(), "k-ahead", body), 201);

        ArgumentCaptor<CatalogAsOfClient.AsOfAsk> ask = ArgumentCaptor.forClass(CatalogAsOfClient.AsOfAsk.class);
        verify(catalogAsOf).priceAsOf(ask.capture());
        assertThat(ask.getValue().at()).as("catalog refuses a moment more than 30 seconds ahead").isBefore(Instant.now().plusSeconds(30));
        assertThat(Instant.parse(order.get("occurredAt").asText())).as("dated at the instant it was priced, not the one typed").isCloseTo(ask.getValue().at(), org.assertj.core.api.Assertions.within(1, java.time.temporal.ChronoUnit.MILLIS));
    }

    @Test
    @DisplayName("the shop's published delivery price is the order's charge: asked for the goods AFTER discount, kept as PUBLISHED with no agreed amount; an amount or a reason sent with it is 400")
    void aPublishedDelivery() throws Exception {
        priced(RING, "4500.00", "500.00", UUID.randomUUID());
        List<ShippingClient.QuoteAsk> asks = new java.util.ArrayList<>();
        org.mockito.Mockito.doAnswer(inv -> {
            ShippingClient.QuoteAsk a = inv.getArgument(0);
            asks.add(a);
            return new ShippingClient.QuoteReply(TOWN, "Kabwe", "OWN_DELIVERY", "Oriah delivery", "ORIAH_COLLECTS", new BigDecimal("80.00"), a.currency(), UUID.randomUUID());
        }).when(shipping).quote(any());
        String published = agreedBody("OWN_DELIVERY", "ORIAH_COLLECTS", null, null, "4080.00").replace("\"AGREED\"", "\"PUBLISHED\"");

        JsonNode order = ok(record(sales(), "k-pub", published), 201);

        assertThat(order.get("total").decimalValue()).isEqualByComparingTo("4080.00");
        assertThat(order.get("delivery").get("source").asText()).isEqualTo("PUBLISHED");
        assertThat(order.get("delivery").get("amount").decimalValue()).isEqualByComparingTo("80.00");
        assertThat(asks).isNotEmpty();
        assertThat(asks.get(asks.size() - 1).goodsTotal()).as("a free-delivery threshold is judged on what the customer pays for the goods").isEqualByComparingTo("4000.00");
        var row = row(UUID.fromString(order.get("id").asText()));
        assertThat(row.get("delivery_price_source")).isEqualTo("PUBLISHED");
        assertThat(row.get("delivery_agreed_amount")).isNull();

        String withAmount = agreedBody("OWN_DELIVERY", "ORIAH_COLLECTS", "60.00", null, "4060.00").replace("\"AGREED\"", "\"PUBLISHED\"");
        assertThat(type(ok(record(sales(), "k-pub-amount", withAmount.replace("Mulenga", "Chipo")), 400))).endsWith("/invalid-delivery");
    }

    @Test
    @DisplayName("a lost reply from inventory leaves the order written but not held; the same request again holds it under the same reservation (200, the same order, announced once)")
    void aRepeatFinishesAnOrderWhoseHoldWasLost() throws Exception {
        SaleStubs.inventoryRefuses(inventory, 503, "{}");
        MvcResult lost = record(sales(), "k-lost", pickupBody("k-lost", "4500.00"));
        assertThat(lost.getResponse().getStatus()).as(lost.getResponse().getContentAsString()).isEqualTo(503);
        UUID id = jdbc.queryForObject("SELECT id FROM orders WHERE idempotency_key = 'k-lost'", UUID.class);
        assertThat(row(id).get("hold_state")).isEqualTo("PENDING");
        assertThat(events(id, "order.created")).isEmpty();
        SaleStubs.inventoryHolds(inventory);

        JsonNode again = ok(record(sales(), "k-lost", pickupBody("k-lost", "4500.00")), 200);

        assertThat(again.get("id").asText()).isEqualTo(id.toString());
        assertThat(row(id).get("hold_state")).isEqualTo("HELD");
        assertThat(events(id, "order.created")).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("a parcel for someone else: the recipient named is kept (tidied), not the caller; the address's optional lines are kept too")
    void aNamedRecipient() throws Exception {
        String body = agreedBody("YANGO", "ORIAH_COLLECTS", "80.00", null, "4580.00").replace("\"address\":{",
                "\"recipient\":{\"name\":\"Bwalya Mumba\",\"phone\":\"0966 111 222\"},\"address\":{\"line2\":\"Behind the market\",\"state\":\"Central\",\"postalCode\":\"10101\",");

        JsonNode order = ok(record(sales(), "k-recip", body), 201);

        var row = row(UUID.fromString(order.get("id").asText()));
        assertThat(row.get("recipient_name")).isEqualTo("Bwalya Mumba");
        assertThat(row.get("recipient_phone")).isEqualTo("+260966111222");
        assertThat(row.get("caller_name")).isEqualTo("Mulenga Chanda");
        assertThat(row.get("address_line2")).isEqualTo("Behind the market");
        assertThat(row.get("address_state")).isEqualTo("Central");
        assertThat(row.get("address_postal_code")).isEqualTo("10101");
    }
}
