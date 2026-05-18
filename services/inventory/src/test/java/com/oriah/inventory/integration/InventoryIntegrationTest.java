package com.oriah.inventory.integration;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.oriah.inventory.TestcontainersConfiguration;
import com.oriah.inventory.repository.InventoryRepository;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full-stack integration test for inventory.
 *
 * <p>Boots the entire context, drives HTTP through MockMvc, and runs
 * against a real PostgreSQL 16 container. Exercises one full lifecycle:
 * create -> reserve -> release -> reserve -> confirm -> restock.
 *
 * <p>State is cleaned via {@link InventoryRepository#deleteAll()} so
 * each test starts from an empty inventory table.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "eureka.client.enabled=false"
})
class InventoryIntegrationTest {

    private static final SimpleGrantedAuthority INVENTORY_ADMIN =
            new SimpleGrantedAuthority("ROLE_INVENTORY_ADMIN");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private InventoryRepository repository;

    /**
     * Replace the OAuth2 resource-server JwtDecoder so context starts
     * without a live Keycloak. {@code with(jwt())} bypasses it per-request.
     */
    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void cleanDatabase() {
        repository.deleteAll();
    }

    @Test
    @DisplayName("full lifecycle: create -> reserve -> release -> reserve -> confirm -> restock")
    void fullLifecycle() throws Exception {
        UUID variantId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        String variantPath = "/api/v1/inventory/" + variantId;
        String adminPath = "/api/v1/admin/inventory/" + variantId;

        // 1) Create with onHand=10
        mvc.perform(post("/api/v1/admin/inventory")
                        .with(jwt().authorities(INVENTORY_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"variantId":"%s","onHand":10}
                                """.formatted(variantId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.onHand").value(10))
                .andExpect(jsonPath("$.reserved").value(0))
                .andExpect(jsonPath("$.available").value(10));

        // 2) Reserve 3
        mvc.perform(post(adminPath + "/reserve")
                        .with(jwt().authorities(INVENTORY_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"qty\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reserved").value(3))
                .andExpect(jsonPath("$.available").value(7));

        // 3) Release 1 (customer reduced cart qty)
        mvc.perform(post(adminPath + "/release")
                        .with(jwt().authorities(INVENTORY_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"qty\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reserved").value(2))
                .andExpect(jsonPath("$.available").value(8));

        // 4) Confirm 2 (shipment goes out)
        mvc.perform(post(adminPath + "/confirm")
                        .with(jwt().authorities(INVENTORY_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"qty\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onHand").value(8))
                .andExpect(jsonPath("$.reserved").value(0))
                .andExpect(jsonPath("$.available").value(8));

        // 5) Restock 5 (warehouse delivery)
        mvc.perform(post(adminPath + "/restock")
                        .with(jwt().authorities(INVENTORY_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"qty\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onHand").value(13))
                .andExpect(jsonPath("$.available").value(13));

        // 6) Public GET reflects the final state
        mvc.perform(get(variantPath))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.variantId").value(variantId.toString()))
                .andExpect(jsonPath("$.onHand").value(13))
                .andExpect(jsonPath("$.reserved").value(0))
                .andExpect(jsonPath("$.available").value(13));
    }

    @Test
    @DisplayName("GET returns 404 for an unknown variant")
    void getReturns404WhenUnknown() throws Exception {
        UUID unknown = UUID.fromString("44444444-4444-4444-4444-444444444444");

        mvc.perform(get("/api/v1/inventory/{id}", unknown))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("reserve returns 404 when the variant has no inventory row")
    void reserveReturns404WhenUnknown() throws Exception {
        UUID unknown = UUID.fromString("55555555-5555-5555-5555-555555555555");

        mvc.perform(post("/api/v1/admin/inventory/{id}/reserve", unknown)
                        .with(jwt().authorities(INVENTORY_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"qty\":1}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("POST create rejects null variantId with 400")
    void createRejectsNullVariantId() throws Exception {
        mvc.perform(post("/api/v1/admin/inventory")
                        .with(jwt().authorities(INVENTORY_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"variantId\":null,\"onHand\":10}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("admin endpoint without a JWT returns 401")
    void adminEndpointRejectsUnauthenticated() throws Exception {
        UUID anyId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        mvc.perform(post("/api/v1/admin/inventory/{id}/reserve", anyId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"qty\":1}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("reserve rejects qty < 1 with 400")
    void reserveRejectsZeroQty() throws Exception {
        UUID variantId = UUID.fromString("66666666-6666-6666-6666-666666666666");
        mvc.perform(post("/api/v1/admin/inventory")
                        .with(jwt().authorities(INVENTORY_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"variantId":"%s","onHand":5}
                                """.formatted(variantId)))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/v1/admin/inventory/{id}/reserve", variantId)
                        .with(jwt().authorities(INVENTORY_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"qty\":0}"))
                .andExpect(status().isBadRequest());
    }
}
