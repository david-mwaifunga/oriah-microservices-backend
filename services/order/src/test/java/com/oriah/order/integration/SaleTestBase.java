package com.oriah.order.integration;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cloud.stream.binder.test.TestChannelBinderConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oriah.events.EventHeaders;
import com.oriah.order.QuoteFixtures;
import com.oriah.order.SaleStubs;
import com.oriah.order.TestcontainersConfiguration;
import com.oriah.order.client.CatalogPricingClient;
import com.oriah.order.client.CustomerClient;
import com.oriah.order.client.InventoryClient;
import com.oriah.order.client.ShippingClient;
import com.oriah.order.repository.OrderRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * What the sale's integration tests share (Phase 7, slice 7.5): the same four stand-ins for the services order calls, one HELD order to work on, and
 * the way a payment or inventory event reaches order. Every subclass has the same Spring configuration, so they all use one cached context.
 *
 * <p>An order placed here is one ring at K4,500.00, self-collected, held at inventory under a reservation id: {@link #heldOrder()}. Events are
 * delivered by calling the consumer beans directly with the headers the relay would set -- the Inbox, the declaration and the saga are all in play, the
 * broker is not.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestChannelBinderConfiguration.class})
@TestPropertySource(properties = "eureka.client.enabled=false")
abstract class SaleTestBase {

    static final SimpleGrantedAuthority ROLE_USER = new SimpleGrantedAuthority("ROLE_USER");
    static final String ADDRESS = "11111111-2222-3333-4444-555555555555";
    static final UUID RING = UUID.fromString("22222222-3333-4444-5555-666666666666");
    static final UUID PRODUCT = UUID.fromString("77777777-8888-4999-8aaa-bbbbbbbbbbbb");
    /** When the money arrived, in the examples: in the past, as the date of the sale. */
    static final String ARRIVED = "2026-11-12T08:00:00Z";
    private static final AtomicLong SEQ = new AtomicLong(1000);

    @Autowired
    MockMvc mvc;
    @Autowired
    OrderRepository repository;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ObjectMapper json;

    @Autowired
    @Qualifier("paymentEvents")
    Consumer<Message<byte[]>> paymentEvents;
    @Autowired
    @Qualifier("inventoryEvents")
    Consumer<Message<byte[]>> inventoryEvents;

    @MockitoBean
    CatalogPricingClient catalog;
    @MockitoBean
    ShippingClient shipping;
    @MockitoBean
    CustomerClient customer;
    @MockitoBean
    InventoryClient inventory;
    @MockitoBean
    com.oriah.order.client.CatalogLookupClient catalogLookup;
    @MockitoBean
    @SuppressWarnings("unused")
    JwtDecoder jwtDecoder;

    @BeforeEach
    void saleStubs() {
        jdbc.update("DELETE FROM order_settlement");
        repository.deleteAll();
        doReturn(List.of(new CustomerClient.Address(UUID.fromString(ADDRESS)))).when(customer).myAddresses();
        doReturn(new CatalogPricingClient.PricingResponse(List.of(new CatalogPricingClient.Entry(RING, "PRICED", PRODUCT, "Aurelia 18k",
                "Aurelia 18k / Ring Size M", new BigDecimal("4500.00"), "ZMW")))).when(catalog).price(any());
        SaleStubs.shippingSelfCollect(shipping);
        SaleStubs.inventoryHolds(inventory);
        SaleStubs.inventoryExtends(inventory);
        SaleStubs.inventorySells(inventory);
    }

    // ------------------------------------------------------------------ orders

    static RequestPostProcessor alice() {
        return jwt().authorities(ROLE_USER).jwt(j -> j.subject("kc-alice"));
    }

    /** One ring, self-collected, held at inventory: K4,500.00, status CREATED. */
    UUID heldOrder() throws Exception {
        return heldOrder(1);
    }

    /** {@code quantity} rings, self-collected, held at inventory. */
    UUID heldOrder(int quantity) throws Exception {
        String body = "{\"shippingAddressId\":\"" + ADDRESS + "\",\"currency\":\"ZMW\",\"deliveryOption\":\"SELF_COLLECT\","
                + "\"quoteDigest\":\"" + QuoteFixtures.selfCollect("ZMW", QuoteFixtures.line(RING, quantity, "4500.00")) + "\","
                + "\"items\":[{\"variantId\":\"" + RING + "\",\"title\":\"x\",\"quantity\":" + quantity + ",\"unitPrice\":4500.00}]}";
        MvcResult placed = mvc.perform(post("/api/v1/orders").with(alice()).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
        assertThat(placed.getResponse().getStatus()).as(placed.getResponse().getContentAsString()).isEqualTo(201);
        return UUID.fromString(json.readTree(placed.getResponse().getContentAsString()).get("id").asText());
    }

    /** The order after its shopper said they paid (PAYMENT_SUBMITTED). */
    UUID claimedOrder() throws Exception {
        return claimedOrder(1);
    }

    /** {@code quantity} rings, held, and then claimed: a different quantity is a different bag, so a different order. */
    UUID claimedOrder(int quantity) throws Exception {
        UUID id = heldOrder(quantity);
        MvcResult claimed = mvc.perform(post("/api/v1/orders/" + id + "/payment-claim").with(alice()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"method\":\"MTN\",\"reference\":\"MP261113\"}")).andReturn();
        assertThat(claimed.getResponse().getStatus()).as(claimed.getResponse().getContentAsString()).isEqualTo(200);
        return id;
    }

    Map<String, Object> row(UUID orderId) {
        return jdbc.queryForMap("SELECT * FROM orders WHERE id = ?", orderId);
    }

    Map<String, Object> settlement(UUID confirmationId) {
        return jdbc.queryForMap("SELECT * FROM order_settlement WHERE confirmation_id = ?", confirmationId);
    }

    long settlements(UUID confirmationId) {
        return jdbc.queryForObject("SELECT count(*) FROM order_settlement WHERE confirmation_id = ?", Long.class, confirmationId);
    }

    /** The events of this type appended for this order, oldest first (payload as JSON text, with the envelope columns that matter). */
    List<Map<String, Object>> events(UUID orderId, String type) {
        return jdbc.queryForList("SELECT seq, schema_version, topic, actor, occurred_at, payload::text AS payload FROM outbox "
                + "WHERE aggregate_type = 'order' AND aggregate_id = ? AND event_type = ? ORDER BY seq", orderId.toString(), type);
    }

    JsonNode payloadOf(Map<String, Object> event) throws Exception {
        return json.readTree((String) event.get("payload"));
    }

    static Instant at(Object timestamp) {
        return ((Timestamp) timestamp).toInstant();
    }

    // ------------------------------------------------------------------ events in

    static Message<byte[]> message(String aggregateType, UUID aggregateId, String type, int version, String payload, String producer) {
        long seq = SEQ.incrementAndGet();
        return MessageBuilder.withPayload(payload.getBytes(StandardCharsets.UTF_8))
                .setHeader(EventHeaders.EVENT_ID, UUID.randomUUID().toString())
                .setHeader(EventHeaders.EVENT_TYPE, type)
                .setHeader(EventHeaders.AGGREGATE_TYPE, aggregateType)
                .setHeader(EventHeaders.AGGREGATE_ID, aggregateId.toString())
                .setHeader(EventHeaders.SEQ, Long.toString(seq))
                .setHeader(EventHeaders.OUTBOX_ID, Long.toString(seq))
                .setHeader(EventHeaders.OCCURRED_AT, ARRIVED)
                .setHeader(EventHeaders.RECORDED_AT, "2026-11-12T08:00:00.125Z")
                .setHeader(EventHeaders.ACTOR, "user:kc-sam")
                .setHeader(EventHeaders.SCHEMA_VERSION, Integer.toString(version))
                .setHeader(EventHeaders.PRODUCER, producer)
                .setHeader(EventHeaders.KAFKA_KEY, aggregateId.toString().getBytes(StandardCharsets.UTF_8))
                .build();
    }

    /** What payment announces when staff confirm money: gross K4,500.00 less a K45.00 fee unless told otherwise. */
    static String confirmedPayload(UUID confirmation, UUID order, String gross, String currency) {
        return "{\"confirmationId\":\"" + confirmation + "\",\"orderId\":\"" + order + "\",\"method\":\"MTN\",\"reference\":\"MP261113\","
                + "\"gross\":" + gross + ",\"fee\":45.00,\"net\":" + new BigDecimal(gross).subtract(new BigDecimal("45.00")).toPlainString()
                + ",\"currency\":\"" + currency + "\",\"arrivedAt\":\"" + ARRIVED + "\",\"backdateReason\":null,\"confirmedBy\":\"Sam Phiri\","
                + "\"confirmedAt\":\"2026-11-12T08:05:00Z\",\"attempt\":1}";
    }

    /** Delivers {@code payment.confirmed} and returns the confirmation's id. */
    UUID confirmed(UUID order, String gross) {
        return confirmed(UUID.randomUUID(), order, gross, "ZMW");
    }

    UUID confirmed(UUID confirmation, UUID order, String gross, String currency) {
        paymentEvents.accept(message("payment_confirmation", confirmation, "payment.confirmed", 1, confirmedPayload(confirmation, order, gross, currency), "payment"));
        return confirmation;
    }

    /** Delivers {@code payment.confirmed} again for a confirmation already announced (a new event, the same confirmation id). */
    void announcedAgain(UUID confirmation, UUID order, String gross) {
        confirmed(confirmation, order, gross, "ZMW");
    }

    void voided(UUID confirmation, UUID order) {
        paymentEvents.accept(message("payment_confirmation", confirmation, "payment.voided", 1,
                "{\"confirmationId\":\"" + confirmation + "\",\"orderId\":\"" + order + "\",\"method\":\"MTN\",\"reference\":\"MP261113\",\"gross\":4500.00,"
                        + "\"fee\":45.00,\"net\":4455.00,\"currency\":\"ZMW\",\"outcome\":\"PENDING\",\"reason\":\"keyed against the wrong order\","
                        + "\"voidedBy\":\"Sam Phiri\",\"voidedAt\":\"2026-11-12T08:10:00Z\"}", "payment"));
    }

    void rejected(UUID claim, UUID order) {
        paymentEvents.accept(message("payment_claim", claim, "payment.rejected", 1,
                "{\"claimId\":\"" + claim + "\",\"orderId\":\"" + order + "\",\"method\":\"MTN\",\"reference\":\"MP261113\","
                        + "\"proofType\":\"NOT_FOUND_AT_PROVIDER\",\"rejectedBy\":\"Sam Phiri\",\"rejectedAt\":\"2026-11-12T08:20:00Z\"}", "payment"));
    }

    void reservationExpired(UUID reservation, UUID order) {
        inventoryEvents.accept(message("reservation", reservation, "reservation.expired", 1,
                "{\"reservationId\":\"" + reservation + "\",\"orderId\":\"" + order + "\",\"heldAt\":\"2026-11-12T07:50:00Z\","
                        + "\"expiresAt\":\"2026-11-12T08:00:00Z\",\"expiredAt\":\"2026-11-12T08:00:30Z\"}", "inventory"));
    }
}
