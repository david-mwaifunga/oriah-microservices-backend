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

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.oriah.inventory.TestcontainersConfiguration;
import com.oriah.inventory.service.StockService;
import com.oriah.inventory.service.StockTestData;

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
 * <p>Boots the entire context, drives HTTP through MockMvc, and runs
 * against a real PostgreSQL 16 container. Exercises one full lifecycle:
 * stock received into a lot -> reserve -> release -> reserve -> confirm
 * (a sale that drains the oldest lot) -> public read.
 *
 * <p>Stock only comes from a received consignment (there is no endpoint that
 * creates it or adds to it), so tests put it on the shelf through the same
 * call the event consumer uses. State is cleaned with a TRUNCATE, which the
 * append-only ledger allows, so each test starts from empty tables.
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
    private StockService stockService;

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
    }

    /** {@code onHand} units on the shelf, received into a lot. */
    private void seed(UUID variantId, int onHand) {
        StockTestData.stock(stockService, variantId, onHand);
    }

    @Test
    @DisplayName("full lifecycle: received -> reserve -> release -> reserve -> confirm (a sale) -> public read")
    void fullLifecycle() throws Exception {
        UUID variantId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        String variantPath = "/api/v1/inventory/" + variantId;
        String adminPath = "/api/v1/admin/inventory/" + variantId;

        // 1) Ten units received into a lot
        seed(variantId, 10);

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

        // 4) Confirm 2: a sale. It drains the oldest lot and records what it cost.
        mvc.perform(post(adminPath + "/confirm")
                        .with(jwt().authorities(INVENTORY_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"qty\":2,\"saleLineId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onHand").value(8))
                .andExpect(jsonPath("$.reserved").value(0))
                .andExpect(jsonPath("$.available").value(8));

        // 5) Public GET reflects the final state
        mvc.perform(get(variantPath))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.variantId").value(variantId.toString()))
                .andExpect(jsonPath("$.onHand").value(8))
                .andExpect(jsonPath("$.reserved").value(0))
                .andExpect(jsonPath("$.available").value(8));

        // ... and the sale left its mark in the ledger: 2 units, at the lot's cost.
        assertThat(jdbc.queryForObject("SELECT sum(-qty_delta) FROM stock_movement WHERE reason = 'SALE'", Integer.class)).isEqualTo(2);
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
        mvc.perform(post("/api/v1/admin/inventory/{id}/reserve", anyId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"qty\":1}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("reserve rejects qty < 1 with 400")
    void reserveRejectsZeroQty() throws Exception {
        UUID variantId = UUID.fromString("66666666-6666-6666-6666-666666666666");
        seed(variantId, 5);

        mvc.perform(post("/api/v1/admin/inventory/{id}/reserve", variantId)
                        .with(jwt().authorities(INVENTORY_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"qty\":0}"))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------
    // Not-found paths, and the three state conflicts that answered 400.
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

    @Test
    @DisplayName("reserving more than is available is 409, not 400")
    void overReserveIsAConflict() throws Exception {
        // The approved contract change. 400 said "your input is malformed, fix
        // it"; the input was valid and the correct advice is to retry later or
        // ask for fewer units. A client acting on a 400 would not retry at all.
        //
        // Admin-only endpoint with no oriah-web call site, so this is safe to
        // take now rather than coordinating a frontend release.
        UUID variantId = UUID.randomUUID();
        seed(variantId, 5);

        mvc.perform(post("/api/v1/admin/inventory/" + variantId + "/reserve")
                        .with(jwt().authorities(INVENTORY_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"qty\":99}"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(409))
                // Refined by inventory's own GlobalExceptionHandler. The STATUS
                // is inherited from the shared IllegalStateException mapping;
                // only the problem type is service-specific. That is what lets
                // the checkout saga branch on the REASON for a 409 rather than
                // on a message string.
                .andExpect(jsonPath("$.type")
                        .value("https://oriah.dev/problems/insufficient-stock"))
                .andExpect(jsonPath("$.detail").value("cannot reserve 99 units; only 5 available"));
    }

    @Test
    @DisplayName("releasing or confirming more than is reserved is 409, not 400")
    void overReleaseAndOverConfirmAreConflicts() throws Exception {
        UUID variantId = UUID.randomUUID();
        seed(variantId, 10);

        mvc.perform(post("/api/v1/admin/inventory/" + variantId + "/release")
                        .with(jwt().authorities(INVENTORY_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"qty\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type")
                        .value("https://oriah.dev/problems/insufficient-stock"))
                .andExpect(jsonPath("$.detail").value("cannot release 1 units; only 0 reserved"));

        mvc.perform(post("/api/v1/admin/inventory/" + variantId + "/confirm")
                        .with(jwt().authorities(INVENTORY_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"qty\":1,\"saleLineId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("cannot confirm 1 units; only 0 reserved"));
    }

    @Test
    @DisplayName("a non-positive qty is still 400 - only STATE conflicts moved to 409")
    void nonPositiveQtyIsStillBadRequest() throws Exception {
        // The other half of the change, and why it is not a blanket rewrite:
        // qty <= 0 genuinely IS a malformed argument, so it stays a 400.
        //
        // The type is "validation", not "bad-request", and that is worth
        // knowing rather than papering over -- this assertion started out
        // expecting bad-request and was wrong. QuantityRequest carries
        // @Min(value = 1), so Bean Validation rejects the payload BEFORE it
        // reaches the aggregate: the response is a MethodArgumentNotValid
        // problem with per-field errors, which is strictly more useful to a
        // client than the aggregate's message would have been.
        //
        // The consequence for the aggregate: Inventory.requirePositive is
        // defence-in-depth and unreachable over HTTP. It still earns its place
        // for direct callers (the Kafka consumer, future internal callers), but
        // it is not what produces this response.
        UUID variantId = UUID.randomUUID();
        seed(variantId, 10);

        mvc.perform(post("/api/v1/admin/inventory/" + variantId + "/reserve")
                        .with(jwt().authorities(INVENTORY_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"qty\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://oriah.dev/problems/validation"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("qty"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("qty must be >= 1"));
    }

    // ------------------------------------------------------------------
    // Confirm is a sale.
    // ------------------------------------------------------------------

    private String sale(int qty, UUID saleLineId) {
        return "{\"qty\":" + qty + ",\"saleLineId\":\"" + saleLineId + "\"}";
    }

    @Test
    @DisplayName("a sale is answered again when it is sent again, and takes nothing the second time")
    void aRetriedSaleTakesNothingMore() throws Exception {
        UUID variantId = UUID.randomUUID();
        UUID saleLine = UUID.randomUUID();
        seed(variantId, 10);
        mvc.perform(post("/api/v1/admin/inventory/" + variantId + "/reserve").with(jwt().authorities(INVENTORY_ADMIN))
                .contentType(MediaType.APPLICATION_JSON).content("{\"qty\":4}")).andExpect(status().isOk());

        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/v1/admin/inventory/" + variantId + "/confirm").with(jwt().authorities(INVENTORY_ADMIN))
                            .contentType(MediaType.APPLICATION_JSON).content(sale(3, saleLine)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.onHand").value(7))
                    .andExpect(jsonPath("$.reserved").value(1));
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_movement WHERE reason = 'SALE'", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("a sale line already used for a different quantity is a 409")
    void aUsedSaleLineIsAConflict() throws Exception {
        UUID variantId = UUID.randomUUID();
        UUID saleLine = UUID.randomUUID();
        seed(variantId, 10);
        mvc.perform(post("/api/v1/admin/inventory/" + variantId + "/reserve").with(jwt().authorities(INVENTORY_ADMIN))
                .contentType(MediaType.APPLICATION_JSON).content("{\"qty\":5}")).andExpect(status().isOk());
        mvc.perform(post("/api/v1/admin/inventory/" + variantId + "/confirm").with(jwt().authorities(INVENTORY_ADMIN))
                .contentType(MediaType.APPLICATION_JSON).content(sale(3, saleLine))).andExpect(status().isOk());

        mvc.perform(post("/api/v1/admin/inventory/" + variantId + "/confirm").with(jwt().authorities(INVENTORY_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content(sale(2, saleLine)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("already")));
    }

    @Test
    @DisplayName("a sale needs its sale line and a real quantity: otherwise 400 naming the field")
    void aSaleNeedsItsSaleLine() throws Exception {
        UUID variantId = UUID.randomUUID();
        seed(variantId, 10);

        mvc.perform(post("/api/v1/admin/inventory/" + variantId + "/confirm").with(jwt().authorities(INVENTORY_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"qty\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("saleLineId"));
        mvc.perform(post("/api/v1/admin/inventory/" + variantId + "/confirm").with(jwt().authorities(INVENTORY_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content(sale(0, UUID.randomUUID())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("qty"));
    }

    @Test
    @DisplayName("a sale of a variant with no stock row is 404, and a plain user is refused")
    void unknownVariantAndPlainUser() throws Exception {
        mvc.perform(post("/api/v1/admin/inventory/" + UUID.randomUUID() + "/confirm").with(jwt().authorities(INVENTORY_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content(sale(1, UUID.randomUUID())))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/admin/inventory/" + UUID.randomUUID() + "/confirm")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER")))
                        .contentType(MediaType.APPLICATION_JSON).content(sale(1, UUID.randomUUID())))
                .andExpect(status().isForbidden());
    }
}
