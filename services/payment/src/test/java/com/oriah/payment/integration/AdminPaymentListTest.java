package com.oriah.payment.integration;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oriah.payment.TestcontainersConfiguration;
import com.oriah.payment.model.PaymentIntent;
import com.oriah.payment.repository.PaymentIntentRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /api/v1/admin/payments} — the endpoint the admin Payments page
 * needs and the only one of the three stub pages with no backend at all.
 *
 * <p>It is also the first use of {@code PAYMENT_ADMIN}, a role the Keycloak
 * realm has always declared and nothing has ever guarded on — which
 * {@code scripts/validate_roles.py} reports as declared-but-unused.
 *
 * <h2>What each test is defending</h2>
 *
 * <p><strong>Authorization is asserted at the service, not only at the
 * gateway.</strong> The gateway enforces admin roles centrally, which is the
 * platform's deliberate design, but a service that trusts the edge alone is one
 * routing mistake away from being open on the Docker network. The admin
 * promotion controller already carries a service-level {@code @PreAuthorize};
 * this keeps payments consistent with it rather than leaving a new endpoint as
 * the one exception.
 *
 * <p><strong>The response key set is asserted exactly.</strong> Payment DTOs are
 * where provider payloads, client secrets and card data leak, and they leak by
 * someone adding a convenient field later. An exact key-set assertion fails when
 * that happens; an "assertThat(body).doesNotContain(\"card\")" would not.
 *
 * <p><strong>{@code keycloakId} is deliberately absent.</strong> It is a stable
 * cross-system subject identifier that helps no admin read a payment list. An
 * operator who needs a human resolves it through the customer admin endpoint.
 *
 * <p><strong>Paging rejects rather than clamps.</strong> Spring's
 * {@code PageableHandlerMethodArgumentResolver} silently caps an oversized
 * {@code size}, so a client asking for 1000 gets 100 and believes it got
 * everything. Same for {@code sort}: this endpoint is fixed to newest-first, and
 * quietly ignoring a {@code sort} parameter teaches callers it works.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"eureka.client.enabled=false"})
class AdminPaymentListTest {

    private static final SimpleGrantedAuthority ROLE_USER =
            new SimpleGrantedAuthority("ROLE_USER");
    private static final SimpleGrantedAuthority ROLE_PAYMENT_ADMIN =
            new SimpleGrantedAuthority("ROLE_PAYMENT_ADMIN");

    private static final String PATH = "/api/v1/admin/payments";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private PaymentIntentRepository repository;
    @Autowired
    private ObjectMapper objectMapper;
    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void reset() {
        repository.deleteAll();
    }

    /** A PENDING intent for a fresh order. */
    private PaymentIntent pending() {
        return repository.saveAndFlush(new PaymentIntent(
                UUID.randomUUID(), "kc-" + UUID.randomUUID(),
                new BigDecimal("100.00"), "USD"));
    }

    private PaymentIntent succeeded() {
        PaymentIntent intent = new PaymentIntent(
                UUID.randomUUID(), "kc-" + UUID.randomUUID(),
                new BigDecimal("100.00"), "USD");
        // A paid intent has been charged: through CHARGING, with the gateway's
        // reference and time, as every real one is.
        intent.startCharging(UUID.randomUUID());
        intent.markSucceeded("fake_ch_" + UUID.randomUUID(), Instant.now());
        return repository.saveAndFlush(intent);
    }

    private PaymentIntent softDeleted() {
        PaymentIntent intent = new PaymentIntent(
                UUID.randomUUID(), "kc-" + UUID.randomUUID(),
                new BigDecimal("100.00"), "USD");
        // markDeleted(), NOT repository.delete(): the latter hard-deletes by
        // design, so using it here would prove nothing about the filter.
        intent.markDeleted();
        return repository.saveAndFlush(intent);
    }

    // ------------------------------------------------------------------
    // Authorization, at this service and not merely at the edge
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a plain USER is refused by the service itself, not just the gateway")
    void userIsForbidden() throws Exception {
        mvc.perform(get(PATH).with(jwt().authorities(ROLE_USER)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("no token at all is 401")
    void anonymousIsUnauthorized() throws Exception {
        mvc.perform(get(PATH))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("PAYMENT_ADMIN gets a page — the realm role that nothing used until now")
    void paymentAdminGetsAPage() throws Exception {
        pending();

        mvc.perform(get(PATH).with(jwt().authorities(ROLE_PAYMENT_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    // ------------------------------------------------------------------
    // What the body may and may not contain
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the response carries exactly the allowlisted fields and no subject id")
    void responseKeySetIsExact() throws Exception {
        succeeded();

        String json = mvc.perform(get(PATH).with(jwt().authorities(ROLE_PAYMENT_ADMIN)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode row = objectMapper.readTree(json).get("content").get(0);
        Set<String> keys = new java.util.HashSet<>();
        row.fieldNames().forEachRemaining(keys::add);

        assertThat(keys)
                .as("an exact key set is what makes a future field addition fail "
                        + "loudly instead of leaking quietly")
                .containsExactlyInAnyOrder(
                        "id", "orderId", "amount", "currency", "status",
                        "failureReason", "createdAt");

        assertThat(keys)
                .as("keycloakId is a stable cross-system subject identifier and "
                        + "helps nobody reading a payment list")
                .doesNotContain("keycloakId");
    }

    // ------------------------------------------------------------------
    // Ordering, filtering, soft delete
    // ------------------------------------------------------------------

    @Test
    @DisplayName("newest first")
    void newestFirst() throws Exception {
        PaymentIntent older = pending();
        Thread.sleep(10);
        PaymentIntent newer = pending();

        mvc.perform(get(PATH).with(jwt().authorities(ROLE_PAYMENT_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(newer.id().toString()))
                .andExpect(jsonPath("$.content[1].id").value(older.id().toString()));
    }

    @Test
    @DisplayName("status filter narrows the page")
    void statusFilter() throws Exception {
        pending();
        succeeded();

        mvc.perform(get(PATH).param("status", "SUCCEEDED")
                        .with(jwt().authorities(ROLE_PAYMENT_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].status").value("SUCCEEDED"));
    }

    @Test
    @DisplayName("status=CHARGING lists the intents whose outcome is not known yet")
    void chargingIsAFilter() throws Exception {
        // The list staff use to find a payment stuck while its outcome is
        // settled: it must be reachable by its own status.
        pending();
        succeeded();
        PaymentIntent inFlight = new PaymentIntent(
                UUID.randomUUID(), "kc-" + UUID.randomUUID(), new BigDecimal("100.00"), "USD");
        inFlight.startCharging(UUID.randomUUID());
        repository.saveAndFlush(inFlight);

        mvc.perform(get(PATH).param("status", "CHARGING")
                        .with(jwt().authorities(ROLE_PAYMENT_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(inFlight.id().toString()))
                .andExpect(jsonPath("$.content[0].status").value("CHARGING"));
    }

    @Test
    @DisplayName("an unknown status is 400, not an empty page")
    void unknownStatusIsBadRequest() throws Exception {
        // An empty page would read as "no such payments" when the truth is
        // "you asked a question I do not understand".
        mvc.perform(get(PATH).param("status", "NOT_A_STATUS")
                        .with(jwt().authorities(ROLE_PAYMENT_ADMIN)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("soft-deleted rows are excluded")
    void softDeletedExcluded() throws Exception {
        pending();
        // Flipped via markDeleted(), NOT repository.delete() -- the latter
        // hard-deletes by design, so it would prove nothing about the filter.
        softDeleted();

        mvc.perform(get(PATH).with(jwt().authorities(ROLE_PAYMENT_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    // ------------------------------------------------------------------
    // Paging contract: reject, never silently clamp or ignore
    // ------------------------------------------------------------------

    @Test
    @DisplayName("size above the cap is rejected, not silently clamped")
    void oversizedPageIsRejected() throws Exception {
        mvc.perform(get(PATH).param("size", "101")
                        .with(jwt().authorities(ROLE_PAYMENT_ADMIN)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a sort parameter is rejected, because this endpoint has one order")
    void sortIsRejected() throws Exception {
        // Both forms: a nonsense value AND a plausible one. Only testing the
        // nonsense value would pass against a binder that quietly accepted the
        // plausible one and then ignored it.
        mvc.perform(get(PATH).param("sort", "amount,desc")
                        .with(jwt().authorities(ROLE_PAYMENT_ADMIN)))
                .andExpect(status().isBadRequest());

        mvc.perform(get(PATH).param("sort", "createdAt,asc")
                        .with(jwt().authorities(ROLE_PAYMENT_ADMIN)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("negative page and zero size are rejected")
    void invalidPagingIsRejected() throws Exception {
        mvc.perform(get(PATH).param("page", "-1")
                        .with(jwt().authorities(ROLE_PAYMENT_ADMIN)))
                .andExpect(status().isBadRequest());

        mvc.perform(get(PATH).param("size", "0")
                        .with(jwt().authorities(ROLE_PAYMENT_ADMIN)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("defaults apply when no paging params are given")
    void defaultsApply() throws Exception {
        pending();

        mvc.perform(get(PATH).with(jwt().authorities(ROLE_PAYMENT_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.number").value(0))
                .andExpect(jsonPath("$.size").value(20));
    }
}
