package com.oriah.order.integration;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.fasterxml.jackson.databind.JsonNode;
import com.oriah.order.SaleStubs;
import com.oriah.order.client.CatalogAsOfClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * What sales staff read of a recorded order, and what they can do with it (Phase 9, slice 9.10): find it, read it, hand the caller a receipt, and cancel it
 * while it waits for money. Every answer is an allow-list (a salesperson reads no landed cost or margin, no staff identity, nothing of another service's data:
 * Q29), the caller's phone and the parcel's address stay visible while the order is alive (staff must ring a caller whose hold lapsed) and are withheld once it
 * is finished, and a web order is never reachable through this door.
 */
class RecordedReadTest extends SaleTestBase {

    private static final String PATH = "/api/v1/admin/orders/recorded";
    private static final UUID TOWN = UUID.fromString("33333333-3333-4333-8333-333333333333");
    /** Words that would mean a cost, a margin, a staff identity or another service's internals have reached a salesperson. */
    private static final List<String> FORBIDDEN = List.of("cost", "margin", "fare", "landed", "keycloak", "paymentIntent", "waived", "recordedBy", "actor", "reservation",
            "priceId", "request_hash", "requestHash", "kc-sam", "kc-maya", "user:");

    private static RequestPostProcessor sales() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_SALES_AGENT")).jwt(j -> j.subject("kc-sam").tokenValue("sam-token"));
    }

    @BeforeEach
    void stubs() {
        doReturn(new CatalogAsOfClient.AsOfResponse(Instant.now(), List.of(new CatalogAsOfClient.AsOfEntry(RING, "PRICED", PRODUCT, "Aurelia 18k", "Aurelia 18k / Ring Size M",
                new BigDecimal("4500.00"), "ZMW", UUID.randomUUID(), new BigDecimal("500.00"), UUID.randomUUID(), new BigDecimal("4000.00"))))).when(catalogAsOf).priceAsOf(any());
        SaleStubs.shippingPlaces(shipping, TOWN);
        SaleStubs.shippingNotOffered(shipping);
    }

    private String pickup(String name, String phone) {
        return "{\"kind\":\"LIVE_PHONE\",\"caller\":{\"name\":\"" + name + "\"" + (phone == null ? "" : ",\"phone\":\"" + phone + "\"") + "},\"lines\":[{\"variantId\":\"" + RING
                + "\",\"quantity\":1}],\"delivery\":{\"mode\":\"PICKUP\"},\"expectedTotal\":4000.00}";
    }

    private String delivered(String name, String phone) {
        return "{\"kind\":\"LIVE_PHONE\",\"caller\":{\"name\":\"" + name + "\",\"phone\":\"" + phone + "\"},\"lines\":[{\"variantId\":\"" + RING + "\",\"quantity\":1}],"
                + "\"delivery\":{\"mode\":\"AGREED\",\"optionCode\":\"OWN_DELIVERY\",\"townId\":\"" + TOWN + "\",\"payer\":\"ORIAH_COLLECTS\",\"agreedAmount\":60.00},"
                + "\"address\":{\"line1\":\"Plot 12 Great East Road\",\"city\":\"Kabwe\",\"country\":\"ZM\"},\"expectedTotal\":4060.00}";
    }

    private UUID record(String key, String body) throws Exception {
        MvcResult r = mvc.perform(post(PATH).with(sales()).contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", key).content(body)).andReturn();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(201);
        return UUID.fromString(json.readTree(r.getResponse().getContentAsString()).get("id").asText());
    }

    private MvcResult read(String path) throws Exception {
        return mvc.perform(get(PATH + path).with(sales())).andReturn();
    }

    /** The list with its query parameters set the way a client sends them (never re-encoded by the URL template). */
    private MvcResult list(String... nameValue) throws Exception {
        var request = get(PATH).with(sales());
        for (int i = 0; i < nameValue.length; i += 2) {
            request.param(nameValue[i], nameValue[i + 1]);
        }
        return mvc.perform(request).andReturn();
    }

    private JsonNode ok(MvcResult r) throws Exception {
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        return json.readTree(r.getResponse().getContentAsString());
    }

    private static void noInternals(String text) {
        for (String word : FORBIDDEN) {
            assertThat(text).as("a salesperson must not read '" + word + "'").doesNotContainIgnoringCase(word);
        }
    }

    // ------------------------------------------------------------------ one order

    @Test
    @DisplayName("an order is read with its caller, lines, discount, delivery and total, and nothing a salesperson must not see (no cost, margin, recorder, login, reservation or price id)")
    void anOrderIsRead() throws Exception {
        UUID id = record("k-1", delivered("Mulenga Chanda", "097 123 4567"));

        MvcResult result = read("/" + id);
        JsonNode order = ok(result);

        assertThat(order.get("caller").get("name").asText()).isEqualTo("Mulenga Chanda");
        assertThat(order.get("caller").get("phone").asText()).isEqualTo("+260971234567");
        assertThat(order.get("recipient").get("name").asText()).isEqualTo("Mulenga Chanda");
        assertThat(order.get("address").get("line1").asText()).isEqualTo("Plot 12 Great East Road");
        assertThat(order.get("subtotal").decimalValue()).isEqualByComparingTo("4500.00");
        assertThat(order.get("discountAmount").decimalValue()).isEqualByComparingTo("500.00");
        assertThat(order.get("delivery").get("amount").decimalValue()).isEqualByComparingTo("60.00");
        assertThat(order.get("total").decimalValue()).isEqualByComparingTo("4060.00");
        assertThat(order.get("lines").get(0).get("lineTotal").decimalValue()).isEqualByComparingTo("4000.00");
        noInternals(result.getResponse().getContentAsString());
    }

    @Test
    @DisplayName("a web order, and an order that does not exist, are the same 404 through this door; a shopper, a packer and an order administrator alone are 403")
    void onlyRecordedOrdersAndOnlySales() throws Exception {
        UUID web = heldOrder();

        assertThat(read("/" + web).getResponse().getStatus()).isEqualTo(404);
        assertThat(read("/" + UUID.randomUUID()).getResponse().getStatus()).isEqualTo(404);
        assertThat(read("/" + web + "/receipt").getResponse().getStatus()).isEqualTo(404);
        for (RequestPostProcessor other : new RequestPostProcessor[] {alice(), jwt().authorities(new SimpleGrantedAuthority("ROLE_FULFILMENT_AGENT")).jwt(j -> j.subject("kc-dave")),
                jwt().authorities(new SimpleGrantedAuthority("ROLE_ORDER_ADMIN")).jwt(j -> j.subject("kc-ada"))}) {
            assertThat(mvc.perform(get(PATH + "/" + web).with(other)).andReturn().getResponse().getStatus()).isEqualTo(403);
            assertThat(mvc.perform(get(PATH).with(other)).andReturn().getResponse().getStatus()).isEqualTo(403);
        }
    }

    @Test
    @DisplayName("the caller's phone and the parcel's address stay readable while the order is alive (a hold that lapsed still needs the caller rung) and are withheld once it is delivered")
    void thePersonIsWithheldOnceFinished() throws Exception {
        UUID id = record("k-pii", delivered("Mulenga Chanda", "097 123 4567"));
        jdbc.update("UPDATE orders SET status = 'EXPIRED', hold_state = 'RELEASED' WHERE id = ?", id);

        JsonNode expired = ok(read("/" + id));
        assertThat(expired.get("caller").get("phone").asText()).isEqualTo("+260971234567");
        assertThat(expired.get("address").get("line1").asText()).isEqualTo("Plot 12 Great East Road");

        jdbc.update("UPDATE orders SET status = 'DELIVERED' WHERE id = ?", id);
        MvcResult done = read("/" + id);
        JsonNode delivered = ok(done);
        assertThat(delivered.get("caller").get("name").asText()).isEqualTo("Mulenga Chanda");
        assertThat(delivered.get("caller").get("phone").asText()).as("masked").isEqualTo("+2609*****567");
        assertThat(delivered.has("recipient")).isFalse();
        assertThat(delivered.has("address")).isFalse();
        assertThat(done.getResponse().getContentAsString()).doesNotContain("Great East").doesNotContain("971234567");
    }

    // ------------------------------------------------------------------ the list

    @Test
    @DisplayName("recorded orders are listed newest first with their caller and total, never a web order; a phone number is masked in the list")
    void theList() throws Exception {
        UUID first = record("k-l1", pickup("Mulenga Chanda", "0971234567"));
        UUID second = record("k-l2", pickup("Chipo Banda", "0977000111").replace("4000.00", "4000.00"));
        heldOrder();

        MvcResult result = list();
        JsonNode page = ok(result);

        assertThat(page.get("content")).hasSize(2);
        assertThat(page.get("content").get(0).get("id").asText()).isEqualTo(second.toString());
        assertThat(page.get("content").get(1).get("id").asText()).isEqualTo(first.toString());
        assertThat(page.get("content").get(0).get("callerName").asText()).isEqualTo("Chipo Banda");
        assertThat(page.get("content").get(0).get("callerPhone").asText()).isEqualTo("+2609*****111");
        assertThat(result.getResponse().getContentAsString()).doesNotContain("0977000111").doesNotContain("+260977000111");
        noInternals(result.getResponse().getContentAsString());
    }

    @Test
    @DisplayName("an order is found by the caller's phone in any spelling (exact), by name (a prefix of three letters or more), by receipt number and by slip; a short name matches nothing")
    void search() throws Exception {
        UUID mulenga = record("k-s1", pickup("Mulenga Chanda", "097 123 4567"));
        record("k-s2", pickup("Chipo Banda", "0977000111"));
        UUID paid = record("k-s3", pickup("Natasha Phiri", "0966000222"));
        confirmed(paid, "4000.00");
        String receipt = (String) row(paid).get("receipt_number");
        jdbc.update("UPDATE orders SET slip_reference = 'P-77' WHERE id = ?", mulenga);

        assertThat(ids(list("q", "+260971234567"))).containsExactly(mulenga.toString());
        assertThat(ids(list("q", "0971234567"))).containsExactly(mulenga.toString());
        assertThat(ids(list("q", "mul"))).containsExactly(mulenga.toString());
        assertThat(ids(list("q", receipt))).containsExactly(paid.toString());
        assertThat(ids(list("q", "P-77"))).containsExactly(mulenga.toString());
        assertThat(ids(list("q", "mu"))).isEmpty();
        assertThat(ids(list("status", "PAID"))).containsExactly(paid.toString());
        assertThat(list("size", "500").getResponse().getStatus()).isEqualTo(200);
        assertThat(ok(list("size", "500")).get("size").asInt()).isLessThanOrEqualTo(50);
    }

    private List<String> ids(MvcResult r) throws Exception {
        List<String> ids = new java.util.ArrayList<>();
        ok(r).get("content").forEach(o -> ids.add(o.get("id").asText()));
        return ids;
    }

    // ------------------------------------------------------------------ the receipt

    @Test
    @DisplayName("the receipt: not ready (409) until the order is paid; then our receipt number, the day it was paid, the caller's name only, every line and a sum that adds up to the total")
    void theReceipt() throws Exception {
        UUID id = record("k-rc", delivered("Mulenga Chanda", "097 123 4567"));
        MvcResult early = read("/" + id + "/receipt");
        assertThat(early.getResponse().getStatus()).isEqualTo(409);
        assertThat(json.readTree(early.getResponse().getContentAsString()).get("type").asText()).endsWith("/receipt-not-ready");

        confirmed(id, "4060.00");
        MvcResult result = read("/" + id + "/receipt");
        JsonNode receipt = ok(result);

        assertThat(receipt.get("receiptNumber").asText()).matches("OR-\\d{6}");
        assertThat(Instant.parse(receipt.get("paidAt").asText())).isEqualTo(Instant.parse(ARRIVED));
        assertThat(receipt.get("callerName").asText()).isEqualTo("Mulenga Chanda");
        assertThat(receipt.get("notice").asText()).containsIgnoringCase("not a tax invoice");
        BigDecimal lines = BigDecimal.ZERO;
        for (JsonNode line : receipt.get("lines")) {
            lines = lines.add(line.get("lineTotal").decimalValue());
        }
        assertThat(lines.subtract(BigDecimal.ZERO).add(receipt.get("delivery").get("amount").decimalValue())).isEqualByComparingTo(receipt.get("total").decimalValue());
        String text = result.getResponse().getContentAsString();
        assertThat(text).doesNotContain("+260").doesNotContain("971234567").doesNotContain("Great East").doesNotContain("Plot");
        noInternals(text);
    }

    @Test
    @DisplayName("a receipt for a courier the recipient pays says so plainly: K0.00 on this receipt, the courier is paid directly")
    void aRecipientPaidReceipt() throws Exception {
        String body = delivered("Mulenga Chanda", "097 123 4567").replace("\"optionCode\":\"OWN_DELIVERY\"", "\"optionCode\":\"YANGO\"").replace("ORIAH_COLLECTS", "RECIPIENT_PAYS")
                .replace(",\"agreedAmount\":60.00", "").replace("\"expectedTotal\":4060.00", "\"expectedTotal\":4000.00");
        UUID id = record("k-rp", body);
        confirmed(id, "4000.00");

        JsonNode receipt = ok(read("/" + id + "/receipt"));

        assertThat(receipt.get("delivery").get("amount").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(receipt.get("delivery").get("note").asText()).containsIgnoringCase("courier directly");
    }

    // ------------------------------------------------------------------ cancel and preview

    @Test
    @DisplayName("sales staff cancel a recorded order that is waiting for money: it is CANCELLED, its hold goes, the recipient and address are forgotten and the caller stays; a paid one cannot be, and a web order is 404")
    void cancel() throws Exception {
        UUID waiting = record("k-c1", delivered("Mulenga Chanda", "097 123 4567"));
        UUID paid = record("k-c2", pickup("Chipo Banda", "0977000111"));
        confirmed(paid, "4000.00");
        UUID web = heldOrder();

        MvcResult cancelled = mvc.perform(post(PATH + "/" + waiting + "/cancel").with(sales())).andReturn();

        assertThat(ok(cancelled).get("status").asText()).isEqualTo("CANCELLED");
        var row = row(waiting);
        assertThat(row.get("recipient_name")).isNull();
        assertThat(row.get("address_line1")).isNull();
        assertThat(row.get("caller_name")).isEqualTo("Mulenga Chanda");
        assertThat(events(waiting, "order.cancelled")).hasSize(1);
        assertThat(mvc.perform(post(PATH + "/" + paid + "/cancel").with(sales())).andReturn().getResponse().getStatus()).isEqualTo(409);
        assertThat(mvc.perform(post(PATH + "/" + web + "/cancel").with(sales())).andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(row(paid).get("status")).isEqualTo("PAID");
    }

    @Test
    @DisplayName("a preview prices the sale with nothing written and nothing held, and needs no caller and no key: the total to read to the caller")
    void preview() throws Exception {
        String body = "{\"kind\":\"LIVE_PHONE\",\"caller\":{\"name\":\"x\"},\"lines\":[{\"variantId\":\"" + RING + "\",\"quantity\":2}],\"delivery\":{\"mode\":\"PICKUP\"},\"expectedTotal\":1.00}";

        MvcResult result = mvc.perform(post(PATH + "/preview").with(sales()).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
        JsonNode priced = ok(result);

        assertThat(priced.get("total").decimalValue()).isEqualByComparingTo("8000.00");
        assertThat(priced.get("discountAmount").decimalValue()).isEqualByComparingTo("1000.00");
        assertThat(priced.get("lines").get(0).get("unitPrice").decimalValue()).isEqualByComparingTo("4500.00");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders", Integer.class)).isZero();
        org.mockito.Mockito.verify(inventory, org.mockito.Mockito.never()).reserve(any());
        noInternals(result.getResponse().getContentAsString());
    }

    @Test
    @DisplayName("the boxes a sale can go out in are catalog's, read with the salesperson's own token: by name and SKU, no price; catalog not answering is 503, never an empty list")
    void boxes() throws Exception {
        UUID box = UUID.randomUUID();
        doReturn(List.of(new com.oriah.order.client.CatalogLookupClient.Box(box, UUID.randomUUID(), "Velvet box", "Velvet box - Large", "BOX-L"))).when(catalogLookup).packaging();

        MvcResult result = read("/boxes");
        JsonNode boxes = ok(result);

        assertThat(boxes.get(0).get("variantId").asText()).isEqualTo(box.toString());
        assertThat(boxes.get(0).get("title").asText()).isEqualTo("Velvet box");
        assertThat(result.getResponse().getContentAsString()).doesNotContainIgnoringCase("price").doesNotContainIgnoringCase("cost");

        org.mockito.Mockito.doThrow(SaleStubs.feign(503, "{}")).when(catalogLookup).packaging();
        assertThat(read("/boxes").getResponse().getStatus()).isEqualTo(503);
    }

    @Test
    @DisplayName("the list of places to deliver to is shipping's, with no price, for a salesperson to pick from")
    void places() throws Exception {
        MvcResult result = read("/delivery-places");

        JsonNode places = ok(result);

        assertThat(places.get("provinces").get(0).get("towns").get(0).get("id").asText()).isEqualTo(TOWN.toString());
        assertThat(places.get("options")).isNotEmpty();
        assertThat(result.getResponse().getContentAsString()).doesNotContainIgnoringCase("amount").doesNotContainIgnoringCase("price");
    }
}
