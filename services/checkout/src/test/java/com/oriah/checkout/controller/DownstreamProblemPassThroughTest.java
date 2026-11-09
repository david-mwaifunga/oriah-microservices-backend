package com.oriah.checkout.controller;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.oriah.checkout.client.CartClient;
import com.oriah.checkout.client.CatalogPricingClient;
import com.oriah.checkout.client.CustomerClient;
import com.oriah.checkout.client.OrderClient;
import com.oriah.checkout.client.PaymentClient;
import com.oriah.checkout.client.PaymentCreateClient;
import com.oriah.checkout.client.ShippingClient;

import feign.FeignException;
import feign.Request;
import feign.RequestTemplate;
import feign.Response;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What a shopper with an address abroad actually receives from checkout
 * (MVP_PLAN Phase 0, item 1).
 *
 * <p>Shipping refuses a destination outside Zambia with a 422 of type
 * {@code outside-delivery-area}. Checkout used to turn every downstream 4xx
 * into a generic 500 -- "the shop is broken" -- for an answer the shopper can
 * act on by choosing a Zambian address. Here the real CheckoutService and the
 * real error advice run; only the Feign clients are stubbed.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false"
})
class DownstreamProblemPassThroughTest {

    private static final UUID ADDRESS = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID LUSAKA = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID VARIANT = UUID.randomUUID();

    @Autowired
    private MockMvc mvc;

    @Autowired
    private CircuitBreakerRegistry breakers;

    @MockitoBean private CartClient cart;
    @MockitoBean private CatalogPricingClient catalog;
    @MockitoBean private CustomerClient customer;
    @MockitoBean private ShippingClient shipping;
    @MockitoBean private OrderClient order;
    @MockitoBean private PaymentCreateClient paymentCreate;
    @MockitoBean private PaymentClient payment;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void aBagForAShopperWhoseAddressIsAbroad() {
        breakers.getAllCircuitBreakers().forEach(b -> b.reset());
        doReturn(new CartClient.CartView(UUID.randomUUID(), new BigDecimal("4500.00"),
                List.of(new CartClient.CartItem(UUID.randomUUID(), VARIANT, "Aurelia", 1,
                        new BigDecimal("4500.00"), new BigDecimal("4500.00")))))
                .when(cart).getMyCart();
        doReturn(new CatalogPricingClient.PricingResponse(List.of(new CatalogPricingClient.Entry(
                VARIANT, "PRICED", UUID.randomUUID(), "Aurelia", "Aurelia 18k",
                new BigDecimal("4500.00"), "ZMW"))))
                .when(catalog).price(any());
        doReturn(List.of(new CustomerClient.Address(ADDRESS, "US"), new CustomerClient.Address(LUSAKA, "ZM")))
                .when(customer).myAddresses();
        // Shipping as it now behaves: self-collect for Zambia, a refusal for anywhere else.
        doAnswer(inv -> {
            ShippingClient.RateRequest asked = inv.getArgument(0);
            if (!"ZM".equals(asked.destinationCountry())) {
                throw outsideTheDeliveryArea();
            }
            return new ShippingClient.RateResponse("ZM", List.of(new ShippingClient.ShippingOption(
                    "SELF_COLLECT", "Oriah Jewelry", "Self-collect", new BigDecimal("0.00"), "ZMW", null, null)));
        }).when(shipping).rates(any());
    }

    private static FeignException outsideTheDeliveryArea() {
        Request request = Request.create(Request.HttpMethod.POST, "http://shipping/api/v1/shipping/rates",
                Map.of(), new byte[0], StandardCharsets.UTF_8, new RequestTemplate());
        byte[] body = ("{\"type\":\"https://oriah.dev/problems/outside-delivery-area\",\"status\":422,"
                + "\"detail\":\"We deliver within Zambia only.\",\"instance\":\"/api/v1/shipping/rates\"}")
                .getBytes(StandardCharsets.UTF_8);
        return FeignException.errorStatus("ShippingClient#rates(RateRequest)",
                Response.builder().status(422).reason("Unprocessable Content").request(request)
                        .headers(Map.of("Content-Type", List.of("application/problem+json")))
                        .body(body).build());
    }

    /** The digest a preview to {@code address}, self-collected, hands the shopper. */
    private String quoteFor(UUID address) throws Exception {
        return com.jayway.jsonpath.JsonPath.read(post("/api/v1/checkout/preview",
                        "{\"shippingAddressId\":\"" + address + "\",\"deliveryOption\":\"SELF_COLLECT\"}")
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.quoteDigest");
    }

    private ResultActions post(String path, String body) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER")).jwt(j -> j.subject("kc-alice")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    @Test
    @DisplayName("preview with an address abroad: 422 outside-delivery-area, in checkout's own words")
    void previewOutsideZambiaIsUnprocessable() throws Exception {
        post("/api/v1/checkout/preview",
                "{\"shippingAddressId\":\"" + ADDRESS + "\",\"deliveryOption\":\"SELF_COLLECT\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://oriah.dev/problems/outside-delivery-area"))
                .andExpect(jsonPath("$.detail").value("We deliver within Zambia only."))
                // Checkout's answer, not shipping's body echoed: no internal path.
                .andExpect(content().string(not(containsString("/api/v1/shipping"))));
    }

    @Test
    @DisplayName("confirm with an address abroad: 422, and nothing is ordered or charged")
    void confirmOutsideZambiaIsUnprocessableAndNothingIsPlaced() throws Exception {
        // A real digest, from a preview to the shopper's Lusaka address, so
        // the confirm reaches shipping for the address abroad.
        String digest = quoteFor(LUSAKA);

        post("/api/v1/checkout/confirm",
                "{\"shippingAddressId\":\"" + ADDRESS + "\",\"deliveryOption\":\"SELF_COLLECT\","
                        + "\"cardNumber\":\"4242424242424242\",\"quoteDigest\":\"" + digest + "\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("https://oriah.dev/problems/outside-delivery-area"));

        verify(order, never()).place(any());
        verify(paymentCreate, never()).createIntent(any());
    }

    @Test
    @DisplayName("order finding the quote changed is checkout's 409 pricing-changed, and the card is never touched")
    void ordersPricingChangedReachesTheShopperAsA409() throws Exception {
        // order prices everything again before saving and refuses a quote that
        // moved. That answer used to become a generic 500 here; the shopper
        // can act on it by reviewing their bag, so it is passed on by TYPE.
        String digest = quoteFor(LUSAKA);
        Request request = Request.create(Request.HttpMethod.POST, "http://order/api/v1/orders",
                Map.of(), new byte[0], StandardCharsets.UTF_8, new RequestTemplate());
        doThrow(FeignException.errorStatus("OrderClient#place(PlaceOrderRequest)",
                Response.builder().status(409).reason("Conflict").request(request)
                        .headers(Map.of("Content-Type", List.of("application/problem+json")))
                        .body(("{\"type\":\"https://oriah.dev/problems/pricing-changed\",\"status\":409,"
                                + "\"detail\":\"Your basket or its price changed -- please review it before paying.\"}")
                                .getBytes(StandardCharsets.UTF_8)).build()))
                .when(order).place(any());

        post("/api/v1/checkout/confirm",
                "{\"shippingAddressId\":\"" + LUSAKA + "\",\"deliveryOption\":\"SELF_COLLECT\","
                        + "\"cardNumber\":\"4242424242424242\",\"quoteDigest\":\"" + digest + "\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://oriah.dev/problems/pricing-changed"))
                .andExpect(content().string(not(containsString("/api/v1/orders"))));

        verify(paymentCreate, never()).createIntent(any());
        verify(payment, never()).confirm(any(), any(), any());
    }

    @Test
    @DisplayName("order finding a piece no longer sellable is checkout's 409 bag-not-purchasable, not a generic 500")
    void ordersBagNotPurchasableReachesTheShopperAsA409() throws Exception {
        // An admin unpublished a piece between checkout's pricing and order's.
        // The shopper can act on that -- remove it -- so it keeps its type.
        String digest = quoteFor(LUSAKA);
        Request request = Request.create(Request.HttpMethod.POST, "http://order/api/v1/orders",
                Map.of(), new byte[0], StandardCharsets.UTF_8, new RequestTemplate());
        doThrow(FeignException.errorStatus("OrderClient#place(PlaceOrderRequest)",
                Response.builder().status(409).reason("Conflict").request(request)
                        .headers(Map.of("Content-Type", List.of("application/problem+json")))
                        .body(("{\"type\":\"https://oriah.dev/problems/bag-not-purchasable\",\"status\":409,"
                                + "\"detail\":\"not sellable\"}").getBytes(StandardCharsets.UTF_8)).build()))
                .when(order).place(any());

        post("/api/v1/checkout/confirm",
                "{\"shippingAddressId\":\"" + LUSAKA + "\",\"deliveryOption\":\"SELF_COLLECT\","
                        + "\"cardNumber\":\"4242424242424242\",\"quoteDigest\":\"" + digest + "\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://oriah.dev/problems/bag-not-purchasable"));

        verify(paymentCreate, never()).createIntent(any());
    }
}