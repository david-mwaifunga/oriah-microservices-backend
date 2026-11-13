package com.oriah.order.integration;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oriah.order.QuoteFixtures;
import com.oriah.order.SaleStubs;
import com.oriah.order.TestcontainersConfiguration;
import com.oriah.order.client.CatalogPricingClient;
import com.oriah.order.client.CustomerClient;
import com.oriah.order.client.InventoryClient;
import com.oriah.order.client.ShippingClient;
import com.oriah.order.repository.OrderRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Phase 7: a shopper who has paid by mobile money or bank transfer SAYS so, and staff then confirm the money or reject the claim.
 *
 * <p>The claim is not money. Submitting one stretches the hold (from ten minutes to as long as a day) so staff have time to look, moves the order
 * to PAYMENT_SUBMITTED, and tells payment the claim in the one shape payment reads ({@code order.payment_submitted}: claim id, order, method,
 * the reference in its one spelling, when, and until when the pieces are held). The order is written FIRST and inventory asked after, so an
 * unanswered extension leaves the claim recorded and the extension pending for the hold job to finish; the event goes out when the extension is
 * done, carrying the new time.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestChannelBinderConfiguration.class})
@TestPropertySource(properties = "eureka.client.enabled=false")
class PaymentClaimTest {

    private static final SimpleGrantedAuthority ROLE_USER = new SimpleGrantedAuthority("ROLE_USER");
    private static final String ADDRESS = "11111111-2222-3333-4444-555555555555";
    private static final UUID RING = UUID.fromString("22222222-3333-4444-5555-666666666666");
    private static final UUID PRODUCT = UUID.fromString("77777777-8888-4999-8aaa-bbbbbbbbbbbb");

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
        doReturn(List.of(new CustomerClient.Address(UUID.fromString(ADDRESS)))).when(customer).myAddresses();
        doReturn(new CatalogPricingClient.PricingResponse(List.of(new CatalogPricingClient.Entry(RING, "PRICED", PRODUCT, "Aurelia 18k",
                "Aurelia 18k / Ring Size M", new BigDecimal("4500.00"), "ZMW")))).when(catalog).price(any());
        SaleStubs.shippingSelfCollect(shipping);
        SaleStubs.inventoryHolds(inventory);
        SaleStubs.inventoryExtends(inventory);
    }

    // ------------------------------------------------------------------ fixtures

    private static RequestPostProcessor alice() {
        return jwt().authorities(ROLE_USER).jwt(j -> j.subject("kc-alice"));
    }

    private static RequestPostProcessor bob() {
        return jwt().authorities(ROLE_USER).jwt(j -> j.subject("kc-bob"));
    }

    /** One ring, self-collected: a HELD order for K4,500.00. */
    private UUID heldOrder() throws Exception {
        String body = "{\"shippingAddressId\":\"" + ADDRESS + "\",\"currency\":\"ZMW\",\"deliveryOption\":\"SELF_COLLECT\","
                + "\"quoteDigest\":\"" + QuoteFixtures.selfCollect("ZMW", QuoteFixtures.line(RING, 1, "4500.00")) + "\","
                + "\"items\":[{\"variantId\":\"" + RING + "\",\"title\":\"x\",\"quantity\":1,\"unitPrice\":4500.00}]}";
        MvcResult placed = mvc.perform(post("/api/v1/orders").with(alice()).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
        assertThat(placed.getResponse().getStatus()).as(placed.getResponse().getContentAsString()).isEqualTo(201);
        return UUID.fromString(json.readTree(placed.getResponse().getContentAsString()).get("id").asText());
    }

    private MvcResult claim(RequestPostProcessor who, UUID orderId, String method, String reference) throws Exception {
        return mvc.perform(post("/api/v1/orders/" + orderId + "/payment-claim").with(who).contentType(MediaType.APPLICATION_JSON)
                .content("{\"method\":\"" + method + "\",\"reference\":\"" + reference + "\"}")).andReturn();
    }

    private Map<String, Object> row(UUID orderId) {
        return jdbc.queryForMap("SELECT * FROM orders WHERE id = ?", orderId);
    }

    private List<Map<String, Object>> events(UUID orderId, String type) {
        return jdbc.queryForList("SELECT seq, schema_version, topic, actor, occurred_at, payload::text AS payload FROM outbox "
                + "WHERE aggregate_type = 'order' AND aggregate_id = ? AND event_type = ? ORDER BY seq", orderId.toString(), type);
    }

    // ------------------------------------------------------------------ the claim

    @Test
    @DisplayName("a claim moves the order to PAYMENT_SUBMITTED, stretches the hold to a day, and tells payment in its own shape")
    void aClaimIsTakenAndAnnounced() throws Exception {
        UUID id = heldOrder();
        Instant before = Instant.now();

        MvcResult result = claim(alice(), id, "MTN", " mp 2611-13.ab ");

        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("status").asText()).isEqualTo("PAYMENT_SUBMITTED");
        Map<String, Object> row = row(id);
        assertThat(row.get("status")).isEqualTo("PAYMENT_SUBMITTED");
        assertThat(row.get("claim_method")).isEqualTo("MTN");
        assertThat(row.get("claim_reference")).as("in its one spelling: no spaces, upper case").isEqualTo("MP2611-13.AB");
        assertThat(row.get("claim_id")).isNotNull();
        assertThat(row.get("claim_submitted_at")).isNotNull();
        assertThat(row.get("hold_extension_pending")).isEqualTo(false);

        ArgumentCaptor<InventoryClient.ExtendAsk> asked = ArgumentCaptor.forClass(InventoryClient.ExtendAsk.class);
        verify(inventory).extend(eq((UUID) row.get("reservation_id")), asked.capture());
        assertThat(asked.getValue().until()).as("a day from now: inventory caps it at a day from when the hold was made")
                .isCloseTo(before.plus(24, ChronoUnit.HOURS), within(30, ChronoUnit.SECONDS));
        assertThat(((Timestamp) row.get("hold_expires_at")).toInstant()).isCloseTo(asked.getValue().until(), within(1, ChronoUnit.MILLIS));

        List<Map<String, Object>> sent = events(id, "order.payment_submitted");
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).get("schema_version")).isEqualTo(1);
        assertThat(sent.get(0).get("topic")).isEqualTo("oriah.order");
        assertThat(sent.get(0).get("actor")).isEqualTo("user:kc-alice");
        JsonNode event = json.readTree((String) sent.get(0).get("payload"));
        assertThat(event.get("claimId").asText()).isEqualTo(row.get("claim_id").toString());
        assertThat(event.get("orderId").asText()).isEqualTo(id.toString());
        assertThat(event.get("method").asText()).isEqualTo("MTN");
        assertThat(event.get("referenceNormalised").asText()).isEqualTo("MP2611-13.AB");
        assertThat(Instant.parse(event.get("submittedAt").asText())).isEqualTo(((Timestamp) row.get("claim_submitted_at")).toInstant());
        assertThat(Instant.parse(event.get("holdExpiresAt").asText())).isEqualTo(((Timestamp) row.get("hold_expires_at")).toInstant());
        assertThat(event.size()).as("exactly the six fields payment reads").isEqualTo(6);
    }

    @Test
    @DisplayName("a bank transfer can be claimed as well; cash cannot, and nothing else is a method")
    void onlyMobileMoneyAndBankTransferCanBeClaimed() throws Exception {
        UUID id = heldOrder();

        for (String refused : List.of("CASH", "cash", "BITCOIN", "")) {
            assertThat(claim(alice(), id, refused, "MP261113").getResponse().getStatus()).as(refused).isEqualTo(400);
        }
        assertThat(row(id).get("status")).isEqualTo("CREATED");
        verify(inventory, never()).extend(any(), any());

        assertThat(claim(alice(), id, "BANK_TRANSFER", "FT26317/AB12").getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("a reference that is empty, too short, too long or not a reference is a 400, nothing changes and inventory is not asked")
    void aBadReferenceIsRefused() throws Exception {
        UUID id = heldOrder();

        for (String reference : List.of("", "  ", "ab", "x".repeat(65), "MP 26 <script>", "MP​2611")) {
            assertThat(claim(alice(), id, "MTN", reference).getResponse().getStatus()).as("'" + reference + "'").isEqualTo(400);
        }
        assertThat(row(id).get("status")).isEqualTo("CREATED");
        assertThat(row(id).get("claim_id")).isNull();
        verify(inventory, never()).extend(any(), any());
    }

    @Test
    @DisplayName("only the owner can claim for an order: somebody else's, and one that does not exist, both answer 404")
    void onlyTheOwnerClaims() throws Exception {
        UUID id = heldOrder();

        MvcResult theirs = claim(bob(), id, "MTN", "MP261113");
        MvcResult unknown = claim(alice(), UUID.randomUUID(), "MTN", "MP261113");

        assertThat(theirs.getResponse().getStatus()).isEqualTo(404);
        assertThat(unknown.getResponse().getStatus()).isEqualTo(404);
        assertThat(row(id).get("status")).isEqualTo("CREATED");
        verify(inventory, never()).extend(any(), any());
    }

    @Test
    @DisplayName("the same claim again is the same claim (200), and tells nobody twice; another reference is refused")
    void aRepeatIsTheSameClaim() throws Exception {
        UUID id = heldOrder();
        claim(alice(), id, "MTN", "MP261113");

        MvcResult again = claim(alice(), id, "MTN", "mp 261113");
        MvcResult other = claim(alice(), id, "MTN", "MP999999");

        assertThat(again.getResponse().getStatus()).isEqualTo(200);
        assertThat(other.getResponse().getStatus()).isEqualTo(409);
        assertThat(json.readTree(other.getResponse().getContentAsString()).get("type").asText()).endsWith("/payment-already-submitted");
        assertThat(events(id, "order.payment_submitted")).hasSize(1);
        verify(inventory, times(1)).extend(any(), any());
        assertThat(row(id).get("claim_reference")).isEqualTo("MP261113");
    }

    @Test
    @DisplayName("an order that is no longer waiting for payment cannot be claimed: 409 order-not-awaiting-payment")
    void aCancelledOrderCannotBeClaimed() throws Exception {
        UUID id = heldOrder();
        jdbc.update("UPDATE orders SET status = 'CANCELLED' WHERE id = ?", id);

        MvcResult result = claim(alice(), id, "MTN", "MP261113");

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(json.readTree(result.getResponse().getContentAsString()).get("type").asText()).endsWith("/order-not-awaiting-payment");
        verify(inventory, never()).extend(any(), any());
    }

    @Test
    @DisplayName("a hold that has already lapsed cannot be stretched: 409 hold-expired, and inventory is not asked")
    void aLapsedHoldCannotBeStretched() throws Exception {
        UUID id = heldOrder();
        jdbc.update("UPDATE orders SET hold_expires_at = now() - interval '1 minute' WHERE id = ?", id);

        MvcResult result = claim(alice(), id, "MTN", "MP261113");

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(json.readTree(result.getResponse().getContentAsString()).get("type").asText()).endsWith("/hold-expired");
        verify(inventory, never()).extend(any(), any());
        assertThat(row(id).get("claim_id")).isNull();
    }

    @Test
    @DisplayName("while confirmed money is being applied to the order, a claim is refused (409)")
    void anOrderBeingSettledCannotBeClaimed() throws Exception {
        UUID id = heldOrder();
        jdbc.update("UPDATE orders SET settling_confirmation_id = ? WHERE id = ?", UUID.randomUUID(), id);

        assertThat(claim(alice(), id, "MTN", "MP261113").getResponse().getStatus()).isEqualTo(409);
        verify(inventory, never()).extend(any(), any());
    }

    // ------------------------------------------------------------------ inventory not answering, or saying the hold is gone

    @Test
    @DisplayName("inventory not answering: the claim is recorded (200), the extension stays pending and nothing is announced; the hold job finishes it")
    void aSilentInventoryLeavesTheExtensionPendingForTheJob() throws Exception {
        UUID id = heldOrder();
        Instant originalExpiry = ((Timestamp) row(id).get("hold_expires_at")).toInstant();
        doThrow(SaleStubs.feign(503, null)).when(inventory).extend(any(), any());

        MvcResult result = claim(alice(), id, "AIRTEL", "AM261113");

        assertThat(result.getResponse().getStatus()).as("the claim is recorded; the stretch of the hold is the system's to finish").isEqualTo(200);
        Map<String, Object> row = row(id);
        assertThat(row.get("status")).isEqualTo("PAYMENT_SUBMITTED");
        assertThat(row.get("hold_extension_pending")).isEqualTo(true);
        assertThat(((Timestamp) row.get("hold_expires_at")).toInstant()).as("not stretched yet").isEqualTo(originalExpiry);
        assertThat(events(id, "order.payment_submitted")).as("announced when the extension is done, with its new time").isEmpty();

        SaleStubs.inventoryExtends(inventory);
        holdJob.run();

        Map<String, Object> after = row(id);
        assertThat(after.get("hold_extension_pending")).isEqualTo(false);
        assertThat(((Timestamp) after.get("hold_expires_at")).toInstant()).isAfter(originalExpiry.plus(1, ChronoUnit.HOURS));
        assertThat(events(id, "order.payment_submitted")).hasSize(1);

        holdJob.run();
        assertThat(events(id, "order.payment_submitted")).as("a second pass has nothing to finish").hasSize(1);
    }

    @Test
    @DisplayName("inventory saying the hold has lapsed: the order EXPIRES (carrying its reservation id), the shopper is told 409 hold-expired")
    void aHoldInventoryHasLetGoExpiresTheOrder() throws Exception {
        UUID id = heldOrder();
        UUID reservation = (UUID) row(id).get("reservation_id");
        doThrow(SaleStubs.feign(409, "{\"type\":\"https://oriah.dev/problems/reservation-expired\",\"status\":409}")).when(inventory).extend(any(), any());

        MvcResult result = claim(alice(), id, "MTN", "MP261113");

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(json.readTree(result.getResponse().getContentAsString()).get("type").asText()).endsWith("/hold-expired");
        assertThat(row(id).get("status")).isEqualTo("EXPIRED");
        List<Map<String, Object>> expired = events(id, "order.expired");
        assertThat(expired).hasSize(1);
        assertThat(json.readTree((String) expired.get(0).get("payload")).get("reservationId").asText())
                .as("inventory releases by the reservation id the event names").isEqualTo(reservation.toString());
        assertThat(events(id, "order.payment_submitted")).as("a claim on a lost hold is never announced").isEmpty();
    }

    // ------------------------------------------------------------------ the hold job's other duty

    @Autowired
    private com.oriah.order.service.HoldJob holdJob;

    @Test
    @DisplayName("a placement nobody finished (PENDING for over a minute) is given back at inventory and deleted, never announced")
    void aStalePendingOrderIsGivenBackAndDeleted() throws Exception {
        SaleStubs.inventoryRefuses(inventory, 503, null);
        String body = "{\"shippingAddressId\":\"" + ADDRESS + "\",\"currency\":\"ZMW\",\"deliveryOption\":\"SELF_COLLECT\","
                + "\"quoteDigest\":\"" + QuoteFixtures.selfCollect("ZMW", QuoteFixtures.line(RING, 1, "4500.00")) + "\","
                + "\"items\":[{\"variantId\":\"" + RING + "\",\"title\":\"x\",\"quantity\":1,\"unitPrice\":4500.00}]}";
        mvc.perform(post("/api/v1/orders").with(alice()).contentType(MediaType.APPLICATION_JSON).content(body));
        Map<String, Object> pending = jdbc.queryForMap("SELECT id, reservation_id FROM orders WHERE hold_state = 'PENDING'");
        SaleStubs.inventoryHolds(inventory);
        doReturn(new InventoryClient.Reservation((UUID) pending.get("reservation_id"), (UUID) pending.get("id"), "RELEASED", null, null, Instant.now(), List.of()))
                .when(inventory).release(any());

        holdJob.run();
        assertThat(repository.count()).as("a minute has not passed: the shopper's repeat may still finish it").isEqualTo(1);
        verify(inventory, never()).release(any());

        jdbc.update("UPDATE orders SET placed_at = now() - interval '2 minutes' WHERE id = ?", pending.get("id"));
        holdJob.run();

        verify(inventory).release((UUID) pending.get("reservation_id"));
        assertThat(repository.count()).isZero();
        assertThat(events((UUID) pending.get("id"), "order.created")).isEmpty();
    }

    @Test
    @DisplayName("a stale PENDING order whose hold cannot be given back stays for the next pass: nothing is deleted while a hold may still exist")
    void aStalePendingOrderStaysWhileInventoryIsSilent() throws Exception {
        SaleStubs.inventoryRefuses(inventory, 503, null);
        String body = "{\"shippingAddressId\":\"" + ADDRESS + "\",\"currency\":\"ZMW\",\"deliveryOption\":\"SELF_COLLECT\","
                + "\"quoteDigest\":\"" + QuoteFixtures.selfCollect("ZMW", QuoteFixtures.line(RING, 1, "4500.00")) + "\","
                + "\"items\":[{\"variantId\":\"" + RING + "\",\"title\":\"x\",\"quantity\":1,\"unitPrice\":4500.00}]}";
        mvc.perform(post("/api/v1/orders").with(alice()).contentType(MediaType.APPLICATION_JSON).content(body));
        jdbc.update("UPDATE orders SET placed_at = now() - interval '2 minutes'");
        doThrow(SaleStubs.feign(503, null)).when(inventory).release(any());

        holdJob.run();

        assertThat(repository.count()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ racing

    @Test
    @DisplayName("two claims at the same instant for one order: one is taken, the other is told, and payment hears of ONE claim")
    void twoClaimsAtOnceAreOneClaim() throws Exception {
        UUID id = heldOrder();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                results.add(pool.submit(() -> {
                    go.await();
                    return claim(alice(), id, "MTN", "MP261113").getResponse().getStatus();
                }));
            }
            go.countDown();
            for (Future<Integer> result : results) {
                assertThat(result.get(30, TimeUnit.SECONDS)).isIn(200, 409);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(row(id).get("status")).isEqualTo("PAYMENT_SUBMITTED");
        assertThat(events(id, "order.payment_submitted")).as("one claim, announced once").hasSize(1);
    }
}
