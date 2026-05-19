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
}
