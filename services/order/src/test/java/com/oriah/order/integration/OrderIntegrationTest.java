package com.oriah.order.integration;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cloud.stream.binder.test.TestChannelBinderConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oriah.order.TestcontainersConfiguration;
import com.oriah.order.repository.OrderRepository;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestChannelBinderConfiguration.class})
@TestPropertySource(properties = {
        "eureka.client.enabled=false",
        "spring.cloud.stream.bindings.orderEvents-out-0.destination=oriah.order"
})
class OrderIntegrationTest {

    private static final SimpleGrantedAuthority ROLE_USER = new SimpleGrantedAuthority("ROLE_USER");
    private static final SimpleGrantedAuthority ROLE_ORDER_ADMIN = new SimpleGrantedAuthority("ROLE_ORDER_ADMIN");

    @Autowired
    private MockMvc mvc;
    @Autowired
    private OrderRepository repository;
    @Autowired
    private ObjectMapper objectMapper;
    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    @DisplayName("POST /orders creates a CREATED order with snapshotted items + correct total")
    void placeOrder() throws Exception {
        String body = """
                {
                  "shippingAddressId": "11111111-1111-1111-1111-111111111111",
                  "currency": "USD",
                  "items": [
                    {"variantId":"%s","title":"Aurelia","quantity":2,"unitPrice":100.00},
                    {"variantId":"%s","title":"Lume","quantity":1,"unitPrice":50.00}
                  ]
                }
                """.formatted(UUID.randomUUID(), UUID.randomUUID());

        mvc.perform(post("/api/v1/orders").with(jwtFor("alice"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.subtotal").value(250.00))
                .andExpect(jsonPath("$.total").value(250.00))
                .andExpect(jsonPath("$.items.length()").value(2));
    }

    @Test
    @DisplayName("/orders/me lists only the calling user's orders")
    void perUserListing() throws Exception {
        placeAliceOrder();
        placeAliceOrder();
        placeBobOrder();

        mvc.perform(get("/api/v1/orders/me").with(jwtFor("alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2));

        mvc.perform(get("/api/v1/orders/me").with(jwtFor("bob")))
                .andExpect(jsonPath("$.content.length()").value(1));
    }

    @Test
    @DisplayName("POST /orders/{id}/pay attaches paymentIntent and transitions to PAID")
    void payTransitionsToPaid() throws Exception {
        String orderId = placeAliceOrder();
        UUID paymentIntent = UUID.randomUUID();
        mvc.perform(post("/api/v1/orders/" + orderId + "/pay").with(jwtFor("alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentIntentId\":\"" + paymentIntent + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.paymentIntentId").value(paymentIntent.toString()));
    }

    @Test
    @DisplayName("POST /orders/{id}/cancel from CREATED is allowed")
    void cancelFromCreated() throws Exception {
        String orderId = placeAliceOrder();
        mvc.perform(post("/api/v1/orders/" + orderId + "/cancel").with(jwtFor("alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    @DisplayName("admin transition: PAID -> FULFILLED -> SHIPPED -> DELIVERED")
    void adminFullPath() throws Exception {
        String orderId = placeAliceOrder();
        // pay first (USER endpoint)
        mvc.perform(post("/api/v1/orders/" + orderId + "/pay").with(jwtFor("alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentIntentId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isOk());

        for (String target : new String[]{"FULFILLED", "SHIPPED", "DELIVERED"}) {
            mvc.perform(post("/api/v1/admin/orders/" + orderId + "/transition")
                            .with(jwt().authorities(ROLE_ORDER_ADMIN))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"targetStatus\":\"" + target + "\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(target));
        }
    }

    @Test
    @DisplayName("regular USER cannot hit admin transition endpoint")
    void userForbiddenFromAdmin() throws Exception {
        String orderId = placeAliceOrder();
        mvc.perform(post("/api/v1/admin/orders/" + orderId + "/transition").with(jwtFor("alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetStatus\":\"FULFILLED\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("cross-user GET /orders/{id} is 404 (no leak)")
    void crossUserLeak() throws Exception {
        String orderId = placeAliceOrder();
        mvc.perform(get("/api/v1/orders/" + orderId).with(jwtFor("bob")))
                .andExpect(status().isNotFound());
    }

    private String placeAliceOrder() throws Exception {
        return placeOrderAs("alice");
    }

    private String placeBobOrder() throws Exception {
        return placeOrderAs("bob");
    }

    private String placeOrderAs(String sub) throws Exception {
        String body = """
                {
                  "shippingAddressId": "11111111-1111-1111-1111-111111111111",
                  "currency": "USD",
                  "items": [
                    {"variantId":"%s","title":"X","quantity":1,"unitPrice":10.00}
                  ]
                }
                """.formatted(UUID.randomUUID());
        MvcResult res = mvc.perform(post("/api/v1/orders").with(jwtFor(sub))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        JsonNode json = objectMapper.readTree(res.getResponse().getContentAsString());
        return json.get("id").asText();
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor jwtFor(String sub) {
        return jwt().authorities(ROLE_USER).jwt(j -> j.subject(sub));
    }

    // ------------------------------------------------------------------
    // Not-found paths -- and the cross-user property they protect.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("GET an unknown order is 404 with a problem body")
    void getUnknownOrderIsAProblem404() throws Exception {
        UUID missing = UUID.randomUUID();
        mvc.perform(get("/api/v1/orders/" + missing).with(jwtFor("alice")))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.type").value("https://oriah.dev/problems/not-found"))
                .andExpect(jsonPath("$.detail").value(
                        "Order not found with the given input data id : '" + missing + "'"))
                .andExpect(jsonPath("$.traceId").exists());
    }

    @Test
    @DisplayName("someone else's order is INDISTINGUISHABLE from one that does not exist")
    void othersOrderIsIndistinguishableFromMissing() throws Exception {
        // The property crossUserLeak protects, now asserted on the BODY as well
        // as the status. OrderController filters by
        // keycloakId().equals(jwt.getSubject()) and then 404s, deliberately
        // collapsing "not yours" into "not found" so bob cannot discover that
        // an order id is real.
        //
        // Adding a detail was the risk here: "this order belongs to another
        // customer" would confirm existence. The shared
        // ResourceNotFoundException phrasing says the same thing in both cases,
        // and this test holds that -- it is what stops a later, friendlier
        // message reopening the leak.
        String aliceOrder = placeAliceOrder();
        UUID fictional = UUID.randomUUID();

        String notYours = bodyOf404(get("/api/v1/orders/" + aliceOrder).with(jwtFor("bob")));
        String neverExisted = bodyOf404(get("/api/v1/orders/" + fictional).with(jwtFor("bob")));

        org.assertj.core.api.Assertions.assertThat(
                        stripTraceId(notYours).replace(aliceOrder, "<id>"))
                .as("bob must not be able to tell alice's real order id from a fictional one")
                .isEqualTo(stripTraceId(neverExisted).replace(fictional.toString(), "<id>"));
        org.assertj.core.api.Assertions.assertThat(notYours)
                .as("the body must not hint at ownership")
                .doesNotContain("alice").doesNotContain("belongs").doesNotContain("another");
    }

    @Test
    @DisplayName("pay / cancel / admin get / admin transition on an unknown id are problem 404s")
    void otherUnknownOrderPathsAreProblem404s() throws Exception {
        UUID missing = UUID.randomUUID();
        var orderAdmin = jwt().authorities(
                new org.springframework.security.core.authority.SimpleGrantedAuthority(
                        "ROLE_ORDER_ADMIN"));

        // The body must be VALID, or bean validation answers 400 before the
        // handler is reached and the 404 is never exercised -- this assertion
        // failed that way first time round.
        mvc.perform(post("/api/v1/orders/" + missing + "/pay").with(jwtFor("alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentIntentId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value(
                        "Order not found with the given input data id : '" + missing + "'"));

        mvc.perform(post("/api/v1/orders/" + missing + "/cancel").with(jwtFor("alice")))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

        mvc.perform(get("/api/v1/admin/orders/" + missing).with(orderAdmin))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

        // The field is targetStatus, not "to". Getting that wrong made this
        // assertion fail with 400 (@NotNull on a null targetStatus) rather than
        // reaching the 404 it was written for -- the same way the /pay
        // assertion above did. Bean validation runs before the handler, so a
        // not-found test with an invalid body tests validation instead.
        mvc.perform(post("/api/v1/admin/orders/" + missing + "/transition").with(orderAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetStatus\":\"PAID\"}"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    private String bodyOf404(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req)
            throws Exception {
        return mvc.perform(req)
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
    }

    /**
     * Blanks the traceId property, which is per-request and so the one field
     * that legitimately differs between two otherwise-identical 404 bodies.
     *
     * <p>The {@code .} stands in for the JSON quote character rather than an
     * escaped {@code \"}: the surrounding context is a regex inside a Java
     * string, so a literal quote needs four levels of escaping and reads far
     * worse than it matches. Any character in those positions is a quote in
     * practice.
     */
    private static String stripTraceId(String json) {
        return json.replaceAll(",?.traceId.:.[0-9a-f]+.", "");
    }
}
