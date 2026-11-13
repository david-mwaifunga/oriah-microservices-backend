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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.oriah.inventory.TestcontainersConfiguration;
import com.oriah.inventory.service.BoxCommitmentService;
import com.oriah.inventory.service.BoxCommitmentService.ProductSnapshot;
import com.oriah.inventory.service.StockService;
import com.oriah.inventory.service.StockTestData;

import static com.oriah.inventory.service.StockTestData.box;
import static com.oriah.inventory.service.StockTestData.context;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full-stack integration test for inventory.
 *
 * <p>Boots the entire context, drives HTTP through MockMvc, and runs against a real PostgreSQL 16 container. Exercises one full lifecycle:
 * stock received into a lot -> an order's hold (pieces and their boxes) -> released -> held again -> consumed (the sale, which drains the
 * oldest lots) -> public read.
 *
 * <p>Stock only comes from a received consignment (there is no endpoint that creates it or adds to it), so tests put it on the shelf through
 * the same call the event consumer uses. State is cleaned with a TRUNCATE, which the append-only ledger allows, so each test starts from
 * empty tables.
 *
 * <p>Since Phase 7 a hold is a reservation with an owner and a clock, made by the order system only; the per-variant reserve, release and
 * confirm that moved a bare counter are gone (their conflicts, validation and idempotency are covered by {@code ReservationHttpTest} and
 * {@code ReservationServiceTest}).
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

    private static final String RESERVATIONS = "/api/v1/admin/inventory/reservations";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private StockService stockService;

    @Autowired
    private BoxCommitmentService commitments;

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * Replace the OAuth2 resource-server JwtDecoder so context starts
     * without a live Keycloak. {@code with(jwt())} bypasses it per-request.
     */
    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void cleanDatabase() {
        StockTestData.wipe(jdbc);
        jdbc.execute("DELETE FROM box_commitment");
        jdbc.execute("DELETE FROM product_state");
    }

    /** {@code onHand} units on the shelf, received into a lot. */
    private void seed(UUID variantId, int onHand) {
        StockTestData.stock(stockService, variantId, onHand);
    }

    /** The order system: the oriah-admin service account, which holds INVENTORY_ADMIN. */
    private static RequestPostProcessor orderSystem() {
        return jwt().jwt(j -> j.subject("sa-1").claim("preferred_username", "service-account-oriah-admin")).authorities(INVENTORY_ADMIN);
    }

    @Test
    @DisplayName("full lifecycle: received -> held -> released -> held -> consumed (a sale) -> public read")
    void fullLifecycle() throws Exception {
        UUID variantId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        String variantPath = "/api/v1/inventory/" + variantId;
        UUID box = UUID.randomUUID();
        UUID product = UUID.randomUUID();

        // 1) Ten rings received into a lot, and a box for the product to go out in
        seed(variantId, 10);
        stockService.applyReceipt(box(box, "2026-11-09T08:00:00.123456Z"), context(9001));
        commitments.applyProduct(new ProductSnapshot(product, "SELLABLE", "ACTIVE", box, 0, false), UUID.randomUUID(), "system:test");

        // 2) An order holds 3: the pieces are no longer available to anyone else
        UUID first = UUID.randomUUID();
        mvc.perform(post(RESERVATIONS).with(orderSystem()).contentType(MediaType.APPLICATION_JSON)
                        .content(hold(first, variantId, product, 3)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("HELD"));
        mvc.perform(get(variantPath))
                .andExpect(jsonPath("$.reserved").value(3))
                .andExpect(jsonPath("$.available").value(7));

        // 3) The order is cancelled: the hold is released
        mvc.perform(post(RESERVATIONS + "/" + first + "/release").with(orderSystem()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"));
        mvc.perform(get(variantPath))
                .andExpect(jsonPath("$.reserved").value(0))
                .andExpect(jsonPath("$.available").value(10));

        // 4) Another order holds 2 and pays: a sale. It drains the oldest lot and records what it cost.
        UUID second = UUID.randomUUID();
        mvc.perform(post(RESERVATIONS).with(orderSystem()).contentType(MediaType.APPLICATION_JSON)
                        .content(hold(second, variantId, product, 2)))
                .andExpect(status().isOk());
        mvc.perform(post(RESERVATIONS + "/" + second + "/consume").with(orderSystem()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONSUMED"));

        // 5) Public GET reflects the final state
        mvc.perform(get(variantPath))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.variantId").value(variantId.toString()))
                .andExpect(jsonPath("$.onHand").value(8))
                .andExpect(jsonPath("$.reserved").value(0))
                .andExpect(jsonPath("$.available").value(8));

        // ... and the sale left its mark in the ledger: 2 pieces and their 2 boxes, at the lots' cost.
        assertThat(jdbc.queryForObject("SELECT sum(-qty_delta) FROM stock_movement WHERE reason = 'SALE' AND variant_id = ?", Integer.class, variantId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT sum(-qty_delta) FROM stock_movement WHERE reason = 'SALE' AND variant_id = ?", Integer.class, box)).isEqualTo(2);
    }

    private static String hold(UUID reservationId, UUID variantId, UUID productId, int qty) {
        return """
                {"reservationId":"%s","orderId":"%s","lines":[{"saleLineId":"%s","variantId":"%s","productId":"%s","quantity":%d,"boxLineId":"%s"}]}"""
                .formatted(reservationId, UUID.randomUUID(), UUID.randomUUID(), variantId, productId, qty, UUID.randomUUID());
    }

    @Test
    @DisplayName("GET returns 404 for an unknown variant")
    void getReturns404WhenUnknown() throws Exception {
        UUID unknown = UUID.fromString("44444444-4444-4444-4444-444444444444");

        mvc.perform(get("/api/v1/inventory/{id}", unknown))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("a variant with no stock row cannot be held: it is a shortage with nothing available, not a 404")
    void holdingAVariantWithNoStockIsAShortage() throws Exception {
        UUID box = UUID.randomUUID();
        UUID product = UUID.randomUUID();
        stockService.applyReceipt(box(box, "2026-11-09T08:00:00.123456Z"), context(9002));
        commitments.applyProduct(new ProductSnapshot(product, "SELLABLE", "ACTIVE", box, 0, false), UUID.randomUUID(), "system:test");
        UUID unknown = UUID.fromString("55555555-5555-5555-5555-555555555555");

        mvc.perform(post(RESERVATIONS).with(orderSystem()).contentType(MediaType.APPLICATION_JSON)
                        .content(hold(UUID.randomUUID(), unknown, product, 1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://oriah.dev/problems/insufficient-stock"))
                .andExpect(jsonPath("$.shortages[0].variantId").value(unknown.toString()))
                .andExpect(jsonPath("$.shortages[0].available").value(0));
    }

    @Test
    @DisplayName("there is no way to create stock with a count, or to add to it: stock with no cost makes profit wrong")
    void stockCannotBeCreatedOrAddedTo() throws Exception {
        mvc.perform(post("/api/v1/admin/inventory")
                        .with(jwt().authorities(INVENTORY_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"variantId\":\"" + UUID.randomUUID() + "\",\"onHand\":10}"))
                .andExpect(status().isMethodNotAllowed());
        mvc.perform(post("/api/v1/admin/inventory/" + UUID.randomUUID() + "/restock")
                        .with(jwt().authorities(INVENTORY_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"qty\":5}"))
                .andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory", Integer.class)).isZero();
    }

    @Test
    @DisplayName("admin endpoint without a JWT returns 401")
    void adminEndpointRejectsUnauthenticated() throws Exception {
        UUID anyId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        mvc.perform(post(RESERVATIONS + "/{id}/release", anyId))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/inventory"))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------
    // Not-found paths.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("GET an unknown variant is 404 with a problem body")
    void getUnknownVariantIsAProblem404() throws Exception {
        UUID missing = UUID.randomUUID();
        mvc.perform(get("/api/v1/inventory/" + missing))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.type").value("https://oriah.dev/problems/not-found"))
                .andExpect(jsonPath("$.detail").value(
                        "Inventory not found with the given input data variantId : '" + missing + "'"))
                .andExpect(jsonPath("$.traceId").exists());
    }
}
