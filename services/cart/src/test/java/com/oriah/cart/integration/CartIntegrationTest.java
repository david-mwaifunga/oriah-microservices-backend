package com.oriah.cart.integration;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.oriah.cart.TestcontainersConfiguration;
import com.oriah.cart.repository.CartRepository;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"eureka.client.enabled=false"})
class CartIntegrationTest {

    private static final SimpleGrantedAuthority ROLE_USER = new SimpleGrantedAuthority("ROLE_USER");

    @Autowired
    private MockMvc mvc;
    @Autowired
    private CartRepository repository;
    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    private static final UUID V1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID V2 = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    @DisplayName("GET on empty cart returns subtotal=0 and no items")
    void emptyCart() throws Exception {
        mvc.perform(get("/api/v1/carts/me").with(jwtForUser("alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.subtotal").value(0));
    }

    @Test
    @DisplayName("add item, adding same variant again bumps quantity")
    void addBumpsQuantity() throws Exception {
        addItem("alice", V1, 1, "100.00", "Aurelia");
        addItem("alice", V1, 2, "100.00", "Aurelia");

        mvc.perform(get("/api/v1/carts/me").with(jwtForUser("alice")))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].quantity").value(3))
                .andExpect(jsonPath("$.subtotal").value(300.00));
    }

    @Test
    @DisplayName("PATCH quantity overwrites; DELETE item removes")
    void patchAndRemove() throws Exception {
        addItem("bob", V1, 2, "50.00", "Lume");

        mvc.perform(patch("/api/v1/carts/me/items/" + V1).with(jwtForUser("bob"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].quantity").value(5))
                .andExpect(jsonPath("$.subtotal").value(250.00));

        mvc.perform(delete("/api/v1/carts/me/items/" + V1).with(jwtForUser("bob")))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/carts/me").with(jwtForUser("bob")))
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    @DisplayName("DELETE on whole cart clears all items")
    void clearAll() throws Exception {
        addItem("carol", V1, 1, "10.00", "A");
        addItem("carol", V2, 2, "20.00", "B");
        mvc.perform(delete("/api/v1/carts/me").with(jwtForUser("carol")))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/carts/me").with(jwtForUser("carol")))
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    @DisplayName("each user sees only their own cart (sub isolation)")
    void perSubIsolation() throws Exception {
        addItem("alice", V1, 1, "100.00", "Aurelia");
        addItem("bob", V2, 3, "20.00", "Lume");

        mvc.perform(get("/api/v1/carts/me").with(jwtForUser("alice")))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].variantId").value(V1.toString()));

        mvc.perform(get("/api/v1/carts/me").with(jwtForUser("bob")))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].variantId").value(V2.toString()));
    }

    @Test
    @DisplayName("/me without JWT is 401")
    void requiresJwt() throws Exception {
        mvc.perform(get("/api/v1/carts/me")).andExpect(status().isUnauthorized());
    }

    private void addItem(String sub, UUID variantId, int qty, String price, String title) throws Exception {
        String body = """
                {"variantId":"%s","quantity":%d,"unitPrice":%s,"title":"%s"}
                """.formatted(variantId, qty, price, title);
        mvc.perform(post("/api/v1/carts/me/items").with(jwtForUser(sub))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor jwtForUser(String sub) {
        return jwt().authorities(ROLE_USER).jwt(j -> j.subject(sub));
    }
}
