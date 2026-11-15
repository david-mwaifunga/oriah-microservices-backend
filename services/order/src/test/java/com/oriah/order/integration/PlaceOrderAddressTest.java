package com.oriah.order.integration;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.oriah.order.TestcontainersConfiguration;
import com.oriah.order.client.CatalogPricingClient;
import com.oriah.order.client.CustomerClient;
import com.oriah.order.client.ShippingClient;
import com.oriah.order.repository.OrderRepository;

import feign.FeignException;
import feign.Request;
import feign.RequestTemplate;

import static com.oriah.order.QuoteFixtures.digest;
import static com.oriah.order.QuoteFixtures.line;
import static com.oriah.order.QuoteFixtures.selfCollect;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * An order can only be sent to one of the caller's own addresses.
 *
 * <h2>The hole</h2>
 *
 * {@code POST /api/v1/orders} stored {@code shippingAddressId} as sent. The
 * gateway routes {@code /api/v1/orders/**} straight to order, so a shopper
 * could skip checkout -- which does check -- and place an order pointing at
 * somebody else's address. Until Phase 7 the only option is self-collect, so
 * nothing was ever delivered there; from Phase 7 the parcel would go to it.
 * Order must not rely on its caller having checked.
 *
 * <h2>What order does now</h2>
 *
 * It asks customer for the caller's own addresses, with the caller's token,
 * and refuses an id that is not among them -- before it prices anything, so
 * catalog, shipping and payment never hear about a refused address. Customer
 * not answering is a 503 and nothing is placed: an address nobody checked is
 * never stored.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestChannelBinderConfiguration.class})
@TestPropertySource(properties = {
        "eureka.client.enabled=false"
})
class PlaceOrderAddressTest {

    private static final SimpleGrantedAuthority ROLE_USER = new SimpleGrantedAuthority("ROLE_USER");

    /** One of alice's own addresses. */
    private static final UUID HOME = UUID.fromString("11111111-2222-3333-4444-555555555555");
    /** Another of alice's, so "in the list" is not the same as "first in the list". */
    private static final UUID OFFICE = UUID.fromString("11111111-2222-3333-4444-666666666666");
    /** Somebody else's address: customer never lists it for alice. */
    private static final UUID STRANGERS = UUID.fromString("99999999-8888-7777-6666-555555555555");

    private static final UUID VARIANT = UUID.fromString("22222222-3333-4444-5555-666666666666");

    /** The message checkout gives for the same refusal, word for word. */
    private static final String NOT_YOURS = "That shipping address is not one of yours.";
    private static final String CANNOT_PLACE_NOW = "We could not place your order just now. "
            + "Nothing has been ordered or charged; please try again.";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private OrderRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private CatalogPricingClient catalog;

    @MockitoBean
    private ShippingClient shipping;

    @MockitoBean
    private com.oriah.order.client.InventoryClient inventory;

    @MockitoBean
    private CustomerClient customer;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void reset() {
        repository.deleteAll();
        doReturn(new CatalogPricingClient.PricingResponse(List.of(
                new CatalogPricingClient.Entry(VARIANT, "PRICED", UUID.randomUUID(),
                        "Aurelia 18k", "Aurelia 18k / Ring Size M", new BigDecimal("4500.00"), "ZMW"))))
                .when(catalog).price(any());
        com.oriah.order.SaleStubs.shippingSelfCollect(shipping);
        com.oriah.order.SaleStubs.inventoryHolds(inventory);
        // alice has two addresses, and STRANGERS is not one of them.
        doReturn(List.of(new CustomerClient.Address(HOME), new CustomerClient.Address(OFFICE)))
                .when(customer).myAddresses();
    }

    private static RequestPostProcessor alice() {
        return jwt().authorities(ROLE_USER).jwt(j -> j.subject("kc-alice"));
    }

    /** One self-collect line at catalog's price, carrying this address field verbatim. */
    private static String body(String addressField) {
        return "{" + addressField + "\"currency\":\"ZMW\",\"deliveryOption\":\"SELF_COLLECT\","
                + "\"quoteDigest\":\"" + selfCollect("ZMW", line(VARIANT, 1, "4500.00")) + "\","
                + "\"items\":[{\"variantId\":\"" + VARIANT + "\",\"title\":\"x\",\"quantity\":1,"
                + "\"unitPrice\":4500.00}]}";
    }

    private static String sentTo(UUID addressId) {
        return body("\"shippingAddressId\":\"" + addressId + "\",");
    }

    private static FeignException customerAnswered(int status) {
        Request request = Request.create(Request.HttpMethod.GET, "/api/v1/customers/me/addresses",
                java.util.Map.of(), null, new RequestTemplate());
        return FeignException.errorStatus("CustomerClient#myAddresses()",
                feign.Response.builder().status(status).reason("x").request(request)
                        .headers(java.util.Map.of()).build());
    }

    /** Refused with this status, nothing written, and nothing priced. */
    private void refusedBeforePricing(String body, int expectedStatus) throws Exception {
        mvc.perform(post("/api/v1/orders").with(alice())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expectedStatus));
        assertThat(repository.count())
                .as("a refused placement must persist nothing")
                .isZero();
        // Before pricing, so a refused address costs no call to catalog or
        // shipping -- and so a probe with somebody else's address learns
        // nothing about prices.
        verifyNoInteractions(catalog);
        verifyNoInteractions(shipping);
    }

    // ----------------------------------------- whose address it is

    @Test
    @DisplayName("somebody else's address is a 400 in checkout's words: nothing saved, catalog never asked")
    void somebodyElsesAddressIsRefused() throws Exception {
        mvc.perform(post("/api/v1/orders").with(alice())
                        .contentType(MediaType.APPLICATION_JSON).content(sentTo(STRANGERS)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(NOT_YOURS));
        assertThat(repository.count()).as("a refused placement must persist nothing").isZero();
        verifyNoInteractions(catalog);
        verifyNoInteractions(shipping);
    }

    @Test
    @DisplayName("a shopper with no addresses at all is refused the same way")
    void noAddressesAtAllIsRefused() throws Exception {
        // customer answers 200 with an empty list for a shopper with no
        // profile yet. That is an answer, not an outage: the address is not
        // theirs.
        doReturn(List.of()).when(customer).myAddresses();

        refusedBeforePricing(sentTo(HOME), 400);
    }

    @Test
    @DisplayName("one of the caller's own addresses is placed, and that id is the one stored")
    void anOwnAddressIsPlacedAndStored() throws Exception {
        mvc.perform(post("/api/v1/orders").with(alice())
                        .contentType(MediaType.APPLICATION_JSON).content(sentTo(OFFICE)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.shippingAddressId").value(OFFICE.toString()));

        // Read from the row: the database is where fulfilment will look.
        assertThat(jdbc.queryForObject("SELECT shipping_address_id FROM orders", UUID.class))
                .isEqualTo(OFFICE);
        verify(customer).myAddresses();
    }

    @Test
    @DisplayName("a delivery, not only a collection, is held to the same rule -- the Phase 7 case")
    void aDeliveryToSomebodyElseIsRefused() throws Exception {
        // From Phase 7 this is the order a parcel follows. The rule must not
        // depend on the option.
        UUID town = UUID.fromString("33333333-3333-4333-8333-333333333333");
        com.oriah.order.SaleStubs.shippingDelivers(shipping, "OWN_DELIVERY", town, "ORIAH_COLLECTS", "125.00", "ZMW");
        String body = "{\"shippingAddressId\":\"" + STRANGERS + "\",\"currency\":\"ZMW\","
                + "\"deliveryOption\":\"OWN_DELIVERY\",\"deliveryTownId\":\"" + town + "\",\"payer\":\"ORIAH_COLLECTS\",\"recipientName\":\"Mulenga Chanda\",\"recipientPhone\":\"+260971234567\","
                + "\"quoteDigest\":\"" + com.oriah.quote.QuoteDigest.of(new com.oriah.quote.Quote(
                        List.of(line(VARIANT, 1, "4500.00")), "ZMW", "OWN_DELIVERY", new BigDecimal("125.00"), town, "ORIAH_COLLECTS")) + "\","
                + "\"items\":[{\"variantId\":\"" + VARIANT + "\",\"title\":\"x\",\"quantity\":1,"
                + "\"unitPrice\":4500.00}]}";

        refusedBeforePricing(body, 400);
    }

    @Test
    @DisplayName("self-collect with no address: customer is not asked at all")
    void selfCollectWithNoAddressNeverAsksCustomer() throws Exception {
        mvc.perform(post("/api/v1/orders").with(alice())
                        .contentType(MediaType.APPLICATION_JSON).content(body("")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.shippingAddressId").doesNotExist());

        verifyNoInteractions(customer);
    }

    @Test
    @DisplayName("customer is asked with NO transaction open")
    void theAddressCheckHappensOutsideTheTransaction() throws Exception {
        // The same rule as catalog and shipping (PlaceOrderPricingTest
        // .pricingHappensOutsideTheTransaction): a network call must never
        // hold a database connection open.
        boolean[] called = {false};
        boolean[] insideTransaction = {false};
        doAnswer(inv -> {
            called[0] = true;
            insideTransaction[0] = TransactionSynchronizationManager.isActualTransactionActive();
            return List.of(new CustomerClient.Address(HOME));
        }).when(customer).myAddresses();

        mvc.perform(post("/api/v1/orders").with(alice())
                        .contentType(MediaType.APPLICATION_JSON).content(sentTo(HOME)))
                .andExpect(status().isCreated());

        // Separately: a flag that defaults to "outside" would pass for a
        // customer that was never asked, which is the bug this class is about.
        assertThat(called[0]).as("customer was never asked whose address it is").isTrue();
        assertThat(insideTransaction[0])
                .as("the customer call held a database connection open")
                .isFalse();
    }

    // ----------------------------------------- customer failing

    @Test
    @DisplayName("customer unreachable: 503, nothing saved -- never an order with an unchecked address")
    void customerUnreachableIs503() throws Exception {
        doThrow(new feign.RetryableException(-1, "connect timed out", Request.HttpMethod.GET,
                (Long) null, Request.create(Request.HttpMethod.GET, "/api/v1/customers/me/addresses",
                        java.util.Map.of(), null, new RequestTemplate())))
                .when(customer).myAddresses();

        mvc.perform(post("/api/v1/orders").with(alice())
                        .contentType(MediaType.APPLICATION_JSON).content(sentTo(HOME)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type").value("https://oriah.dev/problems/upstream-unavailable"))
                // Not "could not price": the address check runs BEFORE pricing,
                // so nothing was priced when customer failed to answer.
                .andExpect(jsonPath("$.detail").value(CANNOT_PLACE_NOW));
        assertThat(repository.count()).isZero();
        verifyNoInteractions(catalog);
    }

    @ParameterizedTest(name = "customer answering {0}")
    @ValueSource(ints = {500, 502, 503, 504})
    @DisplayName("customer answering 5xx is a 503, nothing saved, catalog never asked")
    void aCustomerServerErrorIs503(int status) throws Exception {
        doThrow(customerAnswered(status)).when(customer).myAddresses();

        mvc.perform(post("/api/v1/orders").with(alice())
                        .contentType(MediaType.APPLICATION_JSON).content(sentTo(HOME)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type").value("https://oriah.dev/problems/upstream-unavailable"))
                // Not "could not price": the address check runs BEFORE pricing,
                // so nothing was priced when customer failed to answer.
                .andExpect(jsonPath("$.detail").value(CANNOT_PLACE_NOW));
        assertThat(repository.count()).isZero();
        verifyNoInteractions(catalog);
    }

    @ParameterizedTest(name = "customer answering {0}")
    @ValueSource(ints = {400, 401, 403, 404})
    @DisplayName("customer refusing order's request is OUR fault: 500, not the shopper's 400 and not a retryable 503")
    void aCustomerRefusalIsOurFault(int status) throws Exception {
        // order forwards a token it has itself just accepted, to an endpoint
        // that answers 200 -- an empty list -- even for a shopper with no
        // profile. A 4xx there is a contract or configuration fault: "not
        // yours" would blame the shopper, and a 503 would invite a retry that
        // can never succeed. The same rule order applies to catalog and
        // shipping.
        doThrow(customerAnswered(status)).when(customer).myAddresses();

        refusedBeforePricing(sentTo(HOME), 500);
    }

    @Test
    @DisplayName("customer answering with no list at all is OUR fault too: 500, never read as 'none of yours'")
    void customerAnsweringNothingIsOurFault() throws Exception {
        // An empty list is an answer; a missing body is not one. Reading it as
        // "no addresses" would tell a shopper their own address is not theirs.
        doReturn(null).when(customer).myAddresses();

        refusedBeforePricing(sentTo(HOME), 500);
    }
}
