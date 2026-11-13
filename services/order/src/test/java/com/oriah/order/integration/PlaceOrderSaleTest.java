package com.oriah.order.integration;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cloud.stream.binder.test.TestChannelBinderConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oriah.order.SaleStubs;
import com.oriah.order.TestcontainersConfiguration;
import com.oriah.order.client.CatalogPricingClient;
import com.oriah.order.client.CustomerClient;
import com.oriah.order.client.InventoryClient;
import com.oriah.order.client.ShippingClient;
import com.oriah.order.repository.OrderRepository;
import com.oriah.quote.Quote;
import com.oriah.quote.QuoteDigest;
import com.oriah.quote.QuoteLine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 7: placing an order is taking a HOLD on the pieces for a shopper who has not paid yet.
 *
 * <p>The order writes its own row first (PENDING: nobody has been told), then asks inventory to hold the pieces and their boxes, then
 * marks itself HELD and only then announces {@code order.created}. A refused hold leaves nothing behind; an unanswered one leaves the
 * PENDING row for the shopper's repeat (or the hold job) to finish with the SAME reservation id, which inventory answers once.
 *
 * <p>Money here is catalog's list price, less the promotion's discount per piece, plus delivery that SHIPPING priced for the town and
 * payer the shopper chose (never computed here); the digest the shopper saw binds every part of it ({@code oriah-quote/2}).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestChannelBinderConfiguration.class})
@TestPropertySource(properties = "eureka.client.enabled=false")
class PlaceOrderSaleTest {

    private static final SimpleGrantedAuthority ROLE_USER = new SimpleGrantedAuthority("ROLE_USER");
    private static final String ADDRESS = "11111111-2222-3333-4444-555555555555";
    private static final UUID RING = UUID.fromString("22222222-3333-4444-5555-666666666666");
    private static final UUID PRODUCT = UUID.fromString("77777777-8888-4999-8aaa-bbbbbbbbbbbb");
    private static final UUID TOWN = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID PROMOTION = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final String TITLE = "Aurelia 18k Gold Band / Ring Size M";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private OrderRepository repository;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper json;

    @MockitoBean
    private CatalogPricingClient catalog;
    @MockitoBean
    private ShippingClient shipping;
    @MockitoBean
    private CustomerClient customer;
    @MockitoBean
    private InventoryClient inventory;
    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void stubs() {
        repository.deleteAll();
        org.mockito.Mockito.reset(catalog, shipping, customer, inventory);
        doReturn(List.of(new CustomerClient.Address(UUID.fromString(ADDRESS)))).when(customer).myAddresses();
        catalogPrices("4500.00", "0.00", null);
        SaleStubs.shippingSelfCollect(shipping);
        SaleStubs.inventoryHolds(inventory);
    }

    // ------------------------------------------------------------------ fixtures

    /** Catalog: the ring lists at {@code list}, a promotion (when given) takes {@code discountPerPiece} off each piece. */
    private void catalogPrices(String list, String discountPerPiece, UUID promotion) {
        BigDecimal net = new BigDecimal(list).subtract(new BigDecimal(discountPerPiece));
        doReturn(new CatalogPricingClient.PricingResponse(List.of(new CatalogPricingClient.Entry(RING, "PRICED", PRODUCT, "Aurelia 18k", TITLE,
                new BigDecimal(list), "ZMW", new BigDecimal(discountPerPiece), promotion, net)))).when(catalog).price(any());
    }

    private static RequestPostProcessor alice() {
        return jwt().authorities(ROLE_USER).jwt(j -> j.subject("kc-alice"));
    }

    private static RequestPostProcessor bob() {
        return jwt().authorities(ROLE_USER).jwt(j -> j.subject("kc-bob"));
    }

    /** The digest of what the shopper saw: this quantity of the ring, this discount, and this delivery. */
    private static String digest(int quantity, String discountPerPiece, UUID promotion, String option, String delivery, UUID town, String payer) {
        return QuoteDigest.of(new Quote(List.of(new QuoteLine(RING, quantity, new BigDecimal("4500.00"), new BigDecimal(discountPerPiece), promotion)),
                "ZMW", option, new BigDecimal(delivery), town, payer));
    }

    private static String selfCollectDigest(int quantity) {
        return digest(quantity, "0.00", null, "SELF_COLLECT", "0.00", null, null);
    }

    private static String body(int quantity, String option, UUID town, String payer, String quoteDigest) {
        return "{\"shippingAddressId\":\"" + ADDRESS + "\",\"currency\":\"ZMW\",\"deliveryOption\":\"" + option + "\","
                + (town == null ? "" : "\"deliveryTownId\":\"" + town + "\",")
                + (payer == null ? "" : "\"payer\":\"" + payer + "\",")
                + "\"quoteDigest\":\"" + quoteDigest + "\","
                + "\"items\":[{\"variantId\":\"" + RING + "\",\"title\":\"x\",\"quantity\":" + quantity + ",\"unitPrice\":4500.00}]}";
    }

    private static String selfCollectBody(int quantity) {
        return body(quantity, "SELF_COLLECT", null, null, selfCollectDigest(quantity));
    }

    private MvcResult place(RequestPostProcessor who, String body, String idempotencyKey) throws Exception {
        var request = post("/api/v1/orders").with(who).contentType(MediaType.APPLICATION_JSON).content(body);
        if (idempotencyKey != null) {
            request = request.header("Idempotency-Key", idempotencyKey);
        }
        return mvc.perform(request).andReturn();
    }

    private UUID placed(String body) throws Exception {
        MvcResult result = place(alice(), body, null);
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(201);
        return UUID.fromString(json.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    private Map<String, Object> orderRow(UUID id) {
        return jdbc.queryForMap("SELECT * FROM orders WHERE id = ?", id);
    }

    private long createdEvents(UUID orderId) {
        return jdbc.queryForObject("SELECT count(*) FROM outbox WHERE aggregate_type = 'order' AND aggregate_id = ? AND event_type = 'order.created'",
                Long.class, orderId.toString());
    }

    private long allCreatedEvents() {
        return jdbc.queryForObject("SELECT count(*) FROM outbox WHERE event_type = 'order.created'", Long.class);
    }

    // ------------------------------------------------------------------ money

    @Test
    @DisplayName("a promotion's discount is stored on the line, per piece, and taken off the total")
    void aDiscountIsStoredPerLineAndTakenOffTheTotal() throws Exception {
        catalogPrices("4500.00", "500.00", PROMOTION);

        UUID id = placed(body(2, "SELF_COLLECT", null, null, digest(2, "500.00", PROMOTION, "SELF_COLLECT", "0.00", null, null)));

        Map<String, Object> row = orderRow(id);
        assertThat((BigDecimal) row.get("subtotal")).isEqualByComparingTo("9000.00");
        assertThat((BigDecimal) row.get("discount_amount")).isEqualByComparingTo("1000.00");
        assertThat((BigDecimal) row.get("total")).isEqualByComparingTo("8000.00");
        Map<String, Object> line = jdbc.queryForMap("SELECT * FROM order_items WHERE order_id = ?", id);
        assertThat((BigDecimal) line.get("unit_price")).as("the line keeps the LIST price").isEqualByComparingTo("4500.00");
        assertThat((BigDecimal) line.get("discount_per_piece")).isEqualByComparingTo("500.00");
        assertThat(line.get("promotion_id")).isEqualTo(PROMOTION);
        assertThat(line.get("product_id")).isEqualTo(PRODUCT);
    }

    @Test
    @DisplayName("the promotion behind a discount is bound by the digest: the same K500 off from no promotion is another quote")
    void thePromotionIsBoundByTheDigest() throws Exception {
        catalogPrices("4500.00", "500.00", PROMOTION);

        MvcResult result = place(alice(), body(2, "SELF_COLLECT", null, null, digest(2, "500.00", null, "SELF_COLLECT", "0.00", null, null)), null);

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(repository.count()).isZero();
        verify(inventory, never()).reserve(any());
    }

    @Test
    @DisplayName("delivery is priced by shipping for the town and payer the shopper chose, on the goods AFTER discount")
    void deliveryIsQuotedForTheTownAndPayerOnGoodsAfterDiscount() throws Exception {
        catalogPrices("4500.00", "500.00", PROMOTION);
        SaleStubs.shippingDelivers(shipping, "OWN_DELIVERY", TOWN, "ORIAH_COLLECTS", "125.00", "ZMW");

        UUID id = placed(body(2, "OWN_DELIVERY", TOWN, "ORIAH_COLLECTS",
                digest(2, "500.00", PROMOTION, "OWN_DELIVERY", "125.00", TOWN, "ORIAH_COLLECTS")));

        ArgumentCaptor<ShippingClient.QuoteAsk> asked = ArgumentCaptor.forClass(ShippingClient.QuoteAsk.class);
        verify(shipping).quote(asked.capture());
        assertThat(asked.getValue().townId()).isEqualTo(TOWN);
        assertThat(asked.getValue().optionCode()).isEqualTo("OWN_DELIVERY");
        assertThat(asked.getValue().payer()).isEqualTo("ORIAH_COLLECTS");
        assertThat(asked.getValue().goodsTotal()).as("the free-delivery threshold is judged on the goods after discount").isEqualByComparingTo("8000.00");
        assertThat(asked.getValue().currency()).isEqualTo("ZMW");
        Map<String, Object> row = orderRow(id);
        assertThat((BigDecimal) row.get("shipping_amount")).isEqualByComparingTo("125.00");
        assertThat((BigDecimal) row.get("total")).isEqualByComparingTo("8125.00");
        assertThat(row.get("delivery_town_id")).isEqualTo(TOWN);
        assertThat(row.get("delivery_payer")).isEqualTo("ORIAH_COLLECTS");
    }

    @Test
    @DisplayName("a courier the recipient pays costs nothing on the order: the total is the goods, and the payer is still recorded")
    void aCourierTheRecipientPaysAddsNothingToWhatIsOwed() throws Exception {
        SaleStubs.shippingDelivers(shipping, "YANGO", TOWN, "RECIPIENT_PAYS", "0.00", "ZMW");

        UUID id = placed(body(1, "YANGO", TOWN, "RECIPIENT_PAYS", digest(1, "0.00", null, "YANGO", "0.00", TOWN, "RECIPIENT_PAYS")));

        Map<String, Object> row = orderRow(id);
        assertThat((BigDecimal) row.get("shipping_amount")).isEqualByComparingTo("0.00");
        assertThat((BigDecimal) row.get("total")).isEqualByComparingTo("4500.00");
        assertThat(row.get("delivery_payer")).isEqualTo("RECIPIENT_PAYS");
    }

    @Test
    @DisplayName("the town is bound by the digest: a quote made for another town is refused, nothing is held")
    void theTownIsBoundByTheDigest() throws Exception {
        SaleStubs.shippingDelivers(shipping, "OWN_DELIVERY", TOWN, "ORIAH_COLLECTS", "125.00", "ZMW");
        UUID otherTown = UUID.fromString("33333333-3333-4333-8333-333333333334");

        MvcResult result = place(alice(), body(1, "OWN_DELIVERY", TOWN, "ORIAH_COLLECTS",
                digest(1, "0.00", null, "OWN_DELIVERY", "125.00", otherTown, "ORIAH_COLLECTS")), null);

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(repository.count()).isZero();
        verify(inventory, never()).reserve(any());
    }

    @Test
    @DisplayName("a delivery needs a town: without one the request is a 400 naming the field, and nobody is asked anything")
    void aDeliveryNeedsATown() throws Exception {
        MvcResult result = place(alice(), body(1, "OWN_DELIVERY", null, null, selfCollectDigest(1)), null);

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(result.getResponse().getContentAsString()).contains("deliveryTownId");
        verify(shipping, never()).quote(any());
        verify(inventory, never()).reserve(any());
        assertThat(repository.count()).isZero();
    }

    @Test
    @DisplayName("shipping not offering the town (409) is a changed quote for the shopper, nothing is held")
    void aTownNoLongerOfferedIsAChangedQuote() throws Exception {
        doAnswer(inv -> {
            throw SaleStubs.feign(409, "{\"type\":\"https://oriah.dev/problems/town-not-offered\",\"status\":409}");
        }).when(shipping).quote(any());

        MvcResult result = place(alice(), body(1, "OWN_DELIVERY", TOWN, "ORIAH_COLLECTS", selfCollectDigest(1)), null);

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(result.getResponse().getContentAsString()).contains("pricing-changed");
        verify(inventory, never()).reserve(any());
    }

    // ------------------------------------------------------------------ the hold

    @Test
    @DisplayName("a placed order holds its pieces at inventory under its own reservation id, one box line per piece, and is then HELD")
    void aPlacedOrderIsHeld() throws Exception {
        Instant expires = Instant.now().plusSeconds(600);
        doAnswer(inv -> SaleStubs.held(inv.getArgument(0), expires)).when(inventory).reserve(any());

        MvcResult result = place(alice(), selfCollectBody(2), null);

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        JsonNode created = json.readTree(result.getResponse().getContentAsString());
        UUID id = UUID.fromString(created.get("id").asText());
        assertThat(created.get("holdExpiresAt").asText()).as("the shopper is told when the hold lapses").isNotBlank();
        ArgumentCaptor<InventoryClient.ReserveAsk> asked = ArgumentCaptor.forClass(InventoryClient.ReserveAsk.class);
        verify(inventory).reserve(asked.capture());
        Map<String, Object> row = orderRow(id);
        Map<String, Object> line = jdbc.queryForMap("SELECT * FROM order_items WHERE order_id = ?", id);
        InventoryClient.ReserveAsk ask = asked.getValue();
        assertThat(ask.reservationId()).isEqualTo(row.get("reservation_id"));
        assertThat(ask.orderId()).isEqualTo(id);
        assertThat(ask.holdSeconds()).as("the default hold, which inventory knows").isNull();
        assertThat(ask.lines()).hasSize(1);
        InventoryClient.LineAsk l = ask.lines().get(0);
        assertThat(l.saleLineId()).as("the line is sold under the order line's own id").isEqualTo(line.get("id"));
        assertThat(l.variantId()).isEqualTo(RING);
        assertThat(l.productId()).isEqualTo(PRODUCT);
        assertThat(l.quantity()).isEqualTo(2);
        assertThat(l.boxLineId()).isEqualTo(line.get("box_line_id"));
        assertThat(row.get("hold_state")).isEqualTo("HELD");
        assertThat(((java.sql.Timestamp) row.get("hold_expires_at")).toInstant()).isCloseTo(expires, org.assertj.core.api.Assertions.within(1, java.time.temporal.ChronoUnit.MILLIS));
        assertThat(line.get("box_variant_id")).as("the box inventory chose is remembered on the line").isEqualTo(SaleStubs.BOX_VARIANT);
        assertThat(createdEvents(id)).as("announced once, after the hold").isEqualTo(1);
    }

    @Test
    @DisplayName("inventory is asked outside any transaction, and by then the order exists but nobody has been told")
    void inventoryIsAskedOutsideATransactionAfterTheRowIsWritten() throws Exception {
        long[] seen = new long[3];
        doAnswer(inv -> {
            InventoryClient.ReserveAsk ask = inv.getArgument(0);
            seen[0] = TransactionSynchronizationManager.isActualTransactionActive() ? 1 : 0;
            seen[1] = jdbc.queryForObject("SELECT count(*) FROM orders WHERE id = ? AND hold_state = 'PENDING'", Long.class, ask.orderId());
            seen[2] = jdbc.queryForObject("SELECT count(*) FROM outbox WHERE aggregate_id = ?", Long.class, ask.orderId().toString());
            return SaleStubs.held(ask, Instant.now().plusSeconds(600));
        }).when(inventory).reserve(any());

        place(alice(), selfCollectBody(1), null);

        assertThat(seen[0]).as("a database connection is not held across a call to another service").isZero();
        assertThat(seen[1]).as("the order is written PENDING BEFORE inventory is asked").isEqualTo(1);
        assertThat(seen[2]).as("nothing is announced before the hold").isZero();
    }

    @Test
    @DisplayName("not enough stock: 409 insufficient-stock naming every short line; nothing is saved and nothing is announced")
    void shortStockLeavesNothingBehind() throws Exception {
        long before = allCreatedEvents();
        SaleStubs.inventoryRefuses(inventory, 409, SaleStubs.shortage(RING, "GOODS", 2, 1));

        MvcResult result = place(alice(), selfCollectBody(2), null);

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        JsonNode problem = json.readTree(result.getResponse().getContentAsString());
        assertThat(problem.get("type").asText()).endsWith("/insufficient-stock");
        assertThat(problem.get("shortages").get(0).get("variantId").asText()).isEqualTo(RING.toString());
        assertThat(problem.get("shortages").get(0).get("available").asInt()).isEqualTo(1);
        assertThat(repository.count()).as("the PENDING row of a refused hold is deleted").isZero();
        assertThat(allCreatedEvents()).isEqualTo(before);
    }

    @Test
    @DisplayName("any other refusal inventory gives is passed on under its own name (no-default-box), and nothing is left behind")
    void anotherInventoryRefusalIsPassedOn() throws Exception {
        SaleStubs.inventoryRefuses(inventory, 409,
                "{\"type\":\"https://oriah.dev/problems/no-default-box\",\"title\":\"Conflict\",\"status\":409,\"detail\":\"product has no default box\"}");

        MvcResult result = place(alice(), selfCollectBody(1), null);

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(json.readTree(result.getResponse().getContentAsString()).get("type").asText()).endsWith("/no-default-box");
        assertThat(repository.count()).isZero();
    }

    @Test
    @DisplayName("inventory not answering: 503 with Retry-After; the PENDING row stays, unannounced, and the shopper's repeat finishes it with the SAME reservation id")
    void aSilentInventoryIsFinishedByTheRepeat() throws Exception {
        SaleStubs.inventoryRefuses(inventory, 503, null);

        MvcResult first = place(alice(), selfCollectBody(1), null);

        assertThat(first.getResponse().getStatus()).isEqualTo(503);
        assertThat(first.getResponse().getHeader("Retry-After")).isNotNull();
        assertThat(repository.count()).isEqualTo(1);
        Map<String, Object> pending = jdbc.queryForMap("SELECT id, reservation_id, hold_state FROM orders");
        assertThat(pending.get("hold_state")).isEqualTo("PENDING");
        assertThat(createdEvents((UUID) pending.get("id"))).isZero();

        SaleStubs.inventoryHolds(inventory);
        MvcResult second = place(alice(), selfCollectBody(1), null);

        assertThat(second.getResponse().getStatus()).as(second.getResponse().getContentAsString()).isEqualTo(201);
        assertThat(repository.count()).as("the same order, finished").isEqualTo(1);
        ArgumentCaptor<InventoryClient.ReserveAsk> asked = ArgumentCaptor.forClass(InventoryClient.ReserveAsk.class);
        verify(inventory, times(2)).reserve(asked.capture());
        assertThat(asked.getAllValues().get(1).reservationId()).isEqualTo(pending.get("reservation_id")).isEqualTo(asked.getAllValues().get(0).reservationId());
        assertThat(orderRow((UUID) pending.get("id")).get("hold_state")).isEqualTo("HELD");
        assertThat(createdEvents((UUID) pending.get("id"))).isEqualTo(1);
    }

    @Test
    @DisplayName("a repeat whose placement the hold job removed while it was being finished is told to try again (503), and the hold it just took is given back")
    void aPlacementTheJobRemovedMidFlightIsRefusedAndItsHoldReleased() throws Exception {
        UUID[] reserved = new UUID[1];
        doAnswer(call -> {
            InventoryClient.ReserveAsk ask = call.getArgument(0);
            reserved[0] = ask.reservationId();
            // the hold job claims (removes) the stale row while inventory is still answering
            jdbc.update("DELETE FROM order_items");
            jdbc.update("DELETE FROM orders");
            return SaleStubs.held(ask, Instant.now().plusSeconds(600));
        }).when(inventory).reserve(any());

        MvcResult result = place(alice(), selfCollectBody(1), null);

        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(503);
        assertThat(result.getResponse().getHeader("Retry-After")).isNotNull();
        verify(inventory).release(reserved[0]);
        assertThat(repository.count()).as("the shopper is told nothing was placed, and nothing was").isZero();
    }

    @Test
    @DisplayName("an order whose placement is still being finished cannot be cancelled: 409 placement-in-progress, and nothing is announced for an order nobody was told about")
    void aPendingOrderCannotBeCancelled() throws Exception {
        SaleStubs.inventoryRefuses(inventory, 503, null);
        place(alice(), selfCollectBody(1), null);
        UUID id = (UUID) jdbc.queryForMap("SELECT id FROM orders").get("id");

        MvcResult cancelled = mvc.perform(post("/api/v1/orders/" + id + "/cancel").with(alice())).andReturn();

        assertThat(cancelled.getResponse().getStatus()).as(cancelled.getResponse().getContentAsString()).isEqualTo(409);
        assertThat(json.readTree(cancelled.getResponse().getContentAsString()).get("type").asText()).endsWith("/placement-in-progress");
        Map<String, Object> row = orderRow(id);
        assertThat(row.get("status")).isEqualTo("CREATED");
        assertThat(row.get("hold_state")).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox WHERE aggregate_id = ?", Long.class, id.toString())).isZero();
    }

    // ------------------------------------------------------------------ the delivery address is part of what is placed

    private static final String OTHER_ADDRESS = "aaaaaaaa-2222-3333-4444-555555555555";

    private void theShopperHasTwoAddresses() {
        doReturn(List.of(new CustomerClient.Address(UUID.fromString(ADDRESS)), new CustomerClient.Address(UUID.fromString(OTHER_ADDRESS))))
                .when(customer).myAddresses();
        SaleStubs.shippingDelivers(shipping, "OWN_DELIVERY", TOWN, "ORIAH_COLLECTS", "125.00", "ZMW");
    }

    /** One ring delivered to TOWN, to this address. The quote's fingerprint does not carry the address: two addresses in one town price alike. */
    private static String deliveredTo(String address) {
        return body(1, "OWN_DELIVERY", TOWN, "ORIAH_COLLECTS", digest(1, "0.00", null, "OWN_DELIVERY", "125.00", TOWN, "ORIAH_COLLECTS")).replace(ADDRESS, address);
    }

    @Test
    @DisplayName("the same Idempotency-Key and quote for ANOTHER delivery address is refused (409 idempotency-key-reused): a key is not a way to change where an order goes")
    void aKeyReusedForAnotherAddressIsRefused() throws Exception {
        theShopperHasTwoAddresses();
        place(alice(), deliveredTo(ADDRESS), "key-addr");

        MvcResult second = place(alice(), deliveredTo(OTHER_ADDRESS), "key-addr");

        assertThat(second.getResponse().getStatus()).isEqualTo(409);
        assertThat(json.readTree(second.getResponse().getContentAsString()).get("type").asText()).endsWith("/idempotency-key-reused");
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("the same bag for ANOTHER address while the first is still live is refused, naming the live order: the shopper is not told it succeeded when the parcel would go elsewhere")
    void theSameBagToAnotherAddressIsRefusedNamingTheLiveOrder() throws Exception {
        theShopperHasTwoAddresses();
        UUID first = placed(deliveredTo(ADDRESS));

        MvcResult second = place(alice(), deliveredTo(OTHER_ADDRESS), null);

        assertThat(second.getResponse().getStatus()).as(second.getResponse().getContentAsString()).isEqualTo(409);
        JsonNode problem = json.readTree(second.getResponse().getContentAsString());
        assertThat(problem.get("type").asText()).endsWith("/live-order-has-another-address");
        assertThat(problem.get("orderId").asText()).isEqualTo(first.toString());
        assertThat(repository.count()).isEqualTo(1);
        verify(inventory, times(1)).reserve(any());
    }

    @Test
    @DisplayName("the same bag to the SAME address is still the same order (200): only a different address is refused")
    void theSameBagToTheSameAddressIsTheSameOrder() throws Exception {
        theShopperHasTwoAddresses();
        UUID first = placed(deliveredTo(OTHER_ADDRESS));

        MvcResult second = place(alice(), deliveredTo(OTHER_ADDRESS), null);

        assertThat(second.getResponse().getStatus()).isEqualTo(200);
        assertThat(json.readTree(second.getResponse().getContentAsString()).get("id").asText()).isEqualTo(first.toString());
    }

    @Test
    @DisplayName("a self-collect repeat with another address is still the same order: where the parcel goes is no part of a pickup")
    void aSelfCollectRepeatIgnoresTheAddress() throws Exception {
        theShopperHasTwoAddresses();
        SaleStubs.shippingSelfCollect(shipping);
        UUID first = placed(selfCollectBody(1));

        MvcResult second = place(alice(), selfCollectBody(1).replace(ADDRESS, OTHER_ADDRESS), null);

        assertThat(second.getResponse().getStatus()).isEqualTo(200);
        assertThat(json.readTree(second.getResponse().getContentAsString()).get("id").asText()).isEqualTo(first.toString());
    }

    // ------------------------------------------------------------------ once, however often it is asked

    @Test
    @DisplayName("the same Idempotency-Key and the same quote is the same order (200), held once")
    void aRepeatedKeyIsTheSameOrder() throws Exception {
        MvcResult first = place(alice(), selfCollectBody(1), "key-1");
        MvcResult second = place(alice(), selfCollectBody(1), "key-1");

        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(second.getResponse().getStatus()).isEqualTo(200);
        assertThat(json.readTree(second.getResponse().getContentAsString()).get("id"))
                .isEqualTo(json.readTree(first.getResponse().getContentAsString()).get("id"));
        assertThat(repository.count()).isEqualTo(1);
        verify(inventory, times(1)).reserve(any());
    }

    @Test
    @DisplayName("the same Idempotency-Key with another quote is refused (409 idempotency-key-reused): the key is not a way to change an order")
    void aKeyReusedForAnotherQuoteIsRefused() throws Exception {
        place(alice(), selfCollectBody(1), "key-2");

        MvcResult second = place(alice(), selfCollectBody(2), "key-2");

        assertThat(second.getResponse().getStatus()).isEqualTo(409);
        assertThat(json.readTree(second.getResponse().getContentAsString()).get("type").asText()).endsWith("/idempotency-key-reused");
        assertThat(repository.count()).isEqualTo(1);
        verify(inventory, times(1)).reserve(any());
    }

    @Test
    @DisplayName("a reloaded page that sends the same quote with a new key gets the live order back, not a second hold")
    void theSameBagTwiceIsOneLiveOrder() throws Exception {
        MvcResult first = place(alice(), selfCollectBody(1), "key-a");
        MvcResult second = place(alice(), selfCollectBody(1), "key-b");

        assertThat(second.getResponse().getStatus()).isEqualTo(200);
        assertThat(json.readTree(second.getResponse().getContentAsString()).get("id"))
                .isEqualTo(json.readTree(first.getResponse().getContentAsString()).get("id"));
        verify(inventory, times(1)).reserve(any());
    }

    @Test
    @DisplayName("two shoppers with the same bag each get their own order and hold")
    void twoShoppersTheSameBagAreTwoOrders() throws Exception {
        place(alice(), selfCollectBody(1), null);
        MvcResult second = place(bob(), selfCollectBody(1), null);

        assertThat(second.getResponse().getStatus()).isEqualTo(201);
        assertThat(repository.count()).isEqualTo(2);
    }

    // ------------------------------------------------------------------ caps

    @Test
    @DisplayName("a shopper may hold three orders at once: the fourth is 409 hold-limit-reached and asks inventory nothing")
    void aShopperMayHoldThreeOrders() throws Exception {
        place(alice(), selfCollectBody(1), null);
        place(alice(), selfCollectBody(2), null);
        place(alice(), selfCollectBody(3), null);

        MvcResult fourth = place(alice(), selfCollectBody(4), null);

        assertThat(fourth.getResponse().getStatus()).isEqualTo(409);
        JsonNode problem = json.readTree(fourth.getResponse().getContentAsString());
        assertThat(problem.get("type").asText()).endsWith("/hold-limit-reached");
        assertThat(problem.get("detail").asText()).as("says what they hold").contains("3");
        assertThat(repository.count()).isEqualTo(3);
        verify(inventory, times(3)).reserve(any());
    }

    @Test
    @DisplayName("an old order from before holds existed (no hold at all, so it can never be settled) holds nothing, and does not count against the limit")
    void anOrderThatHoldsNothingDoesNotCount() throws Exception {
        place(alice(), selfCollectBody(1), null);
        place(alice(), selfCollectBody(2), null);
        place(alice(), selfCollectBody(3), null);
        jdbc.update("UPDATE orders SET hold_state = NULL, reservation_id = NULL");

        MvcResult fourth = place(alice(), selfCollectBody(4), null);

        assertThat(fourth.getResponse().getStatus()).as(fourth.getResponse().getContentAsString()).isEqualTo(201);
    }

    @Test
    @DisplayName("five placements at the same instant by one shopper: the per-shopper lock lets exactly three through, the others are told the limit, and only three holds are taken")
    void theLimitHoldsWhenPlacementsRace() throws Exception {
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(5);
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        try {
            List<java.util.concurrent.Future<Integer>> results = new java.util.ArrayList<>();
            for (int quantity = 1; quantity <= 5; quantity++) {
                String body = selfCollectBody(quantity);
                results.add(pool.submit(() -> {
                    go.await();
                    return place(alice(), body, null).getResponse().getStatus();
                }));
            }
            go.countDown();
            int created = 0;
            int refused = 0;
            for (java.util.concurrent.Future<Integer> result : results) {
                int status = result.get(60, java.util.concurrent.TimeUnit.SECONDS);
                if (status == 201) {
                    created++;
                } else if (status == 409) {
                    refused++;
                }
            }
            assertThat(created).as("orders placed").isEqualTo(3);
            assertThat(refused).as("told the limit").isEqualTo(2);
        } finally {
            pool.shutdownNow();
        }
        assertThat(repository.count()).isEqualTo(3);
        verify(inventory, times(3)).reserve(any());
    }

    @Test
    @DisplayName("a shopper may hold twenty pieces at once, across their orders")
    void aShopperMayHoldTwentyPieces() throws Exception {
        place(alice(), selfCollectBody(15), null);

        MvcResult over = place(alice(), selfCollectBody(6), null);

        assertThat(over.getResponse().getStatus()).isEqualTo(409);
        assertThat(json.readTree(over.getResponse().getContentAsString()).get("type").asText()).endsWith("/hold-limit-reached");
        MvcResult exactly = place(alice(), selfCollectBody(5), null);
        assertThat(exactly.getResponse().getStatus()).as("fifteen and five make twenty").isEqualTo(201);
    }

    @Test
    @DisplayName("an order that has ended no longer counts against the shopper: the limit is on what is HELD")
    void anEndedOrderFreesTheLimit() throws Exception {
        UUID first = placed(selfCollectBody(1));
        place(alice(), selfCollectBody(2), null);
        place(alice(), selfCollectBody(3), null);
        jdbc.update("UPDATE orders SET status = 'CANCELLED' WHERE id = ?", first);

        MvcResult next = place(alice(), selfCollectBody(4), null);

        assertThat(next.getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    @DisplayName("an order read back shows, on each line, what the promotion took off, which promotion it was, the product and the box that goes with it")
    void theResponseShowsTheDiscountAndTheBox() throws Exception {
        catalogPrices("4500.00", "500.00", PROMOTION);
        UUID id = placed(body(2, "SELF_COLLECT", null, null, digest(2, "500.00", PROMOTION, "SELF_COLLECT", "0.00", null, null)));

        MvcResult read = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/orders/" + id).with(alice())).andReturn();

        assertThat(read.getResponse().getStatus()).isEqualTo(200);
        JsonNode line = json.readTree(read.getResponse().getContentAsString()).get("items").get(0);
        assertThat(line.get("unitPrice").decimalValue()).as("the LIST price, so a discount is never hidden inside it").isEqualByComparingTo("4500.00");
        assertThat(line.get("discountPerPiece").decimalValue()).isEqualByComparingTo("500.00");
        assertThat(line.get("promotionId").asText()).isEqualTo(PROMOTION.toString());
        assertThat(line.get("productId").asText()).isEqualTo(PRODUCT.toString());
        assertThat(line.get("boxVariantId").asText()).isEqualTo(SaleStubs.BOX_VARIANT.toString());
    }

    @Test
    @DisplayName("the placement answers 201 with a Location, and a 503 from the hold carries nothing the shopper must not repeat")
    void theResponseShape() throws Exception {
        mvc.perform(post("/api/v1/orders").with(alice()).contentType(MediaType.APPLICATION_JSON).content(selfCollectBody(1)))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.status").value("CREATED"));
    }
}
