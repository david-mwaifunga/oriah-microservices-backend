package com.oriah.payment.claim;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.oriah.payment.TestcontainersConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A shopper who says they paid by mobile money or transfer (Phase 7) leaves a CLAIM: order, method, reference. A claim is not money. Staff either
 * confirm money for it, or REJECT it as false, and a rejection is bound to the claim: the order, method and reference are copied from it, never
 * typed, and the proof is one the service checks itself where it can (the reference is already confirmed for another order) and one the person
 * states with evidence where it cannot (nothing at the provider). A claim whose reference already has money recorded for the same order cannot be
 * rejected. A rejection posts nothing and does not use up the reference.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"eureka.client.enabled=false"})
class ClaimsHttpTest {

    private static final String CLAIMS = "/api/v1/admin/payments/claims";
    private static final String CONFIRMATIONS = "/api/v1/admin/payments/confirmations";
    private static final String PROBLEMS = "https://oriah.dev/problems/";
    private static final String EVIDENCE = "checked the MTN agent portal: no such transaction";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ClaimService claims;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    private static RequestPostProcessor sam() {
        return jwt().jwt(j -> j.subject("kc-sam").claim("name", "Sam Phiri")).authorities(new SimpleGrantedAuthority("ROLE_SALES_AGENT"));
    }

    private static String reference() {
        return "MP" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
    }

    /** A claim as order's event leaves it. */
    private UUID claim(UUID order, String method, String reference) {
        UUID id = UUID.randomUUID();
        claims.record(id, order, method, reference, Instant.parse("2026-11-12T08:00:00Z"), Instant.parse("2026-11-13T08:00:00Z"));
        return id;
    }

    private ResultActions confirm(UUID order, String method, String reference) throws Exception {
        return mvc.perform(post(CONFIRMATIONS).with(sam()).header("Idempotency-Key", "k-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"orderId\":\"" + order + "\",\"method\":\"" + method + "\",\"reference\":\"" + reference + "\",\"gross\":500.00,\"fee\":5.00}"));
    }

    private ResultActions reject(UUID claim, String json) throws Exception {
        return mvc.perform(post(CLAIMS + "/" + claim + "/reject").with(sam()).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions rejectNotFound(UUID claim) throws Exception {
        return reject(claim, "{\"proofType\":\"NOT_FOUND_AT_PROVIDER\",\"evidence\":\"" + EVIDENCE + "\"}");
    }

    private List<Map<String, Object>> events(UUID claim) {
        return jdbc.queryForList("SELECT event_type, seq, topic, actor, occurred_at, payload::text AS payload FROM outbox WHERE aggregate_type = 'payment_claim' "
                + "AND aggregate_id = ? ORDER BY seq", claim.toString());
    }

    // ---------------------------------------------------------------- recording a claim

    @Test
    @DisplayName("a claim is recorded once however often its event arrives, its reference in the one spelling, and cash cannot be claimed")
    void recording() {
        UUID id = UUID.randomUUID();
        UUID order = UUID.randomUUID();

        claims.record(id, order, "mtn", "mp 2411 abc", Instant.parse("2026-11-12T08:00:00Z"), null);
        claims.record(id, order, "mtn", "mp 2411 abc", Instant.parse("2026-11-12T08:00:00Z"), null);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_claim WHERE id = ?", Long.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT reference FROM payment_claim WHERE id = ?", String.class, id)).isEqualTo("MP2411ABC");
        assertThatThrownBy(() -> claims.record(UUID.randomUUID(), order, "CASH", "abc123", Instant.now(), null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> claims.record(UUID.randomUUID(), order, "MTN", "#!", Instant.now(), null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a claim can be read, listed for an order, and says whether money is already recorded for it and whether its reference is used for another order")
    void reading() throws Exception {
        UUID order = UUID.randomUUID();
        String reference = reference();
        UUID id = claim(order, "MTN", reference);
        claim(UUID.randomUUID(), "MTN", reference);   // another shopper's claim, on another order, with the same reference

        mvc.perform(get(CLAIMS + "/" + id).with(sam())).andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(order.toString())).andExpect(jsonPath("$.method").value("MTN")).andExpect(jsonPath("$.reference").value(reference))
                .andExpect(jsonPath("$.rejected").value(false)).andExpect(jsonPath("$.moneyRecorded").value(false)).andExpect(jsonPath("$.usedElsewhere").value(false));

        confirm(UUID.randomUUID(), "MTN", reference).andExpect(status().isCreated());
        mvc.perform(get(CLAIMS + "/" + id).with(sam())).andExpect(jsonPath("$.moneyRecorded").value(false)).andExpect(jsonPath("$.usedElsewhere").value(true));

        confirm(order, "AIRTEL", reference).andExpect(status().isCreated());
        mvc.perform(get(CLAIMS).param("orderId", order.toString()).with(sam())).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(id.toString())).andExpect(jsonPath("$[0].moneyRecorded").value(false));
        mvc.perform(get(CLAIMS + "/" + UUID.randomUUID()).with(sam())).andExpect(status().isNotFound()).andExpect(jsonPath("$.type").value(PROBLEMS + "claim-not-found"));
    }

    // ---------------------------------------------------------------- rejecting

    @Test
    @DisplayName("a claim with nothing at the provider is rejected with the evidence: the rejection copies the claim's order, method and reference, and announces payment.rejected")
    void rejectingForWantOfProof() throws Exception {
        UUID order = UUID.randomUUID();
        String reference = reference();
        UUID id = claim(order, "MTN", reference);

        rejectNotFound(id).andExpect(status().isCreated())
                .andExpect(jsonPath("$.claimId").value(id.toString())).andExpect(jsonPath("$.orderId").value(order.toString()))
                .andExpect(jsonPath("$.method").value("MTN")).andExpect(jsonPath("$.reference").value(reference))
                .andExpect(jsonPath("$.proofType").value("NOT_FOUND_AT_PROVIDER")).andExpect(jsonPath("$.evidence").value(EVIDENCE))
                .andExpect(jsonPath("$.rejectedBy").value("user:kc-sam")).andExpect(jsonPath("$.rejectedByName").value("Sam Phiri"));

        assertThat(events(id)).hasSize(1).first().satisfies(event -> {
            assertThat(event.get("event_type")).isEqualTo("payment.rejected");
            assertThat(event.get("topic")).isEqualTo("oriah.payment");
            assertThat(event.get("actor")).isEqualTo("user:kc-sam");
            assertThat((String) event.get("payload")).contains("\"claimId\":\"" + id + "\"").contains("\"orderId\":\"" + order + "\"")
                    .contains("\"reference\":\"" + reference + "\"").contains("\"proofType\":\"NOT_FOUND_AT_PROVIDER\"");
        });
        mvc.perform(get(CLAIMS + "/" + id).with(sam())).andExpect(jsonPath("$.rejected").value(true)).andExpect(jsonPath("$.proofType").value("NOT_FOUND_AT_PROVIDER"));
    }

    @Test
    @DisplayName("'not found at the provider' needs its evidence (ten characters), a proof type must be one there is, and nothing is recorded or announced when it is refused")
    void theProofIsNeeded() throws Exception {
        UUID id = claim(UUID.randomUUID(), "MTN", reference());

        reject(id, "{\"proofType\":\"NOT_FOUND_AT_PROVIDER\"}").andExpect(status().isBadRequest());
        reject(id, "{\"proofType\":\"NOT_FOUND_AT_PROVIDER\",\"evidence\":\"short\"}").andExpect(status().isBadRequest());
        reject(id, "{\"proofType\":\"NOT_FOUND_AT_PROVIDER\",\"evidence\":\"          x          \"}").andExpect(status().isBadRequest());
        reject(id, "{\"proofType\":\"I_FEEL_LIKE_IT\",\"evidence\":\"" + EVIDENCE + "\"}").andExpect(status().isBadRequest());
        reject(id, "{\"evidence\":\"" + EVIDENCE + "\"}").andExpect(status().isBadRequest());

        assertThat(events(id)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_rejection WHERE claim_id = ?", Long.class, id)).isZero();
    }

    @Test
    @DisplayName("'reference already used' is checked by the service: it needs money confirmed with that reference for ANOTHER order, held money counting, and a voided confirmation not")
    void referenceAlreadyUsedIsVerified() throws Exception {
        String reference = reference();
        UUID claimed = claim(UUID.randomUUID(), "MTN", reference);

        reject(claimed, "{\"proofType\":\"REFERENCE_ALREADY_USED\"}").andExpect(status().isConflict()).andExpect(jsonPath("$.type").value(PROBLEMS + "reference-not-used-elsewhere"));

        String first = confirmId(confirm(UUID.randomUUID(), "MTN", reference).andExpect(status().isCreated()));
        mvc.perform(post(CONFIRMATIONS + "/" + first + "/void").with(sam()).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"keyed against the wrong order\"}")).andExpect(status().isOk());
        reject(claimed, "{\"proofType\":\"REFERENCE_ALREADY_USED\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(PROBLEMS + "reference-not-used-elsewhere"));

        String held = confirmId(confirm(UUID.randomUUID(), "MTN", reference).andExpect(status().isCreated()));
        jdbc.update("UPDATE payment_confirmation SET outcome = 'HELD', outcome_reason = 'ORDER_UNKNOWN', outcome_at = now() WHERE id = ?::uuid", held);

        reject(claimed, "{\"proofType\":\"REFERENCE_ALREADY_USED\"}").andExpect(status().isCreated()).andExpect(jsonPath("$.proofType").value("REFERENCE_ALREADY_USED"))
                .andExpect(jsonPath("$.evidence").doesNotExist());
    }

    @Test
    @DisplayName("a claim whose reference already has money recorded for the SAME order cannot be rejected, whatever the proof: the money is recorded, so the claim is not false")
    void moneyRecordedForTheClaim() throws Exception {
        UUID order = UUID.randomUUID();
        String reference = reference();
        UUID id = claim(order, "MTN", reference);
        confirm(order, "MTN", reference).andExpect(status().isCreated());

        rejectNotFound(id).andExpect(status().isConflict()).andExpect(jsonPath("$.type").value(PROBLEMS + "money-recorded-for-claim"));
        reject(id, "{\"proofType\":\"REFERENCE_ALREADY_USED\"}").andExpect(status().isConflict()).andExpect(jsonPath("$.type").value(PROBLEMS + "money-recorded-for-claim"));

        assertThat(events(id)).isEmpty();
    }

    @Test
    @DisplayName("a claim is rejected once (409 already-rejected), an unknown claim is 404, and rejecting does not use up the reference: money for it can still be confirmed")
    void onceAndNotTheKey() throws Exception {
        UUID order = UUID.randomUUID();
        String reference = reference();
        UUID id = claim(order, "MTN", reference);
        rejectNotFound(id).andExpect(status().isCreated());

        rejectNotFound(id).andExpect(status().isConflict()).andExpect(jsonPath("$.type").value(PROBLEMS + "already-rejected"));
        rejectNotFound(UUID.randomUUID()).andExpect(status().isNotFound()).andExpect(jsonPath("$.type").value(PROBLEMS + "claim-not-found"));
        confirm(order, "MTN", reference).andExpect(status().isCreated());

        assertThat(events(id)).hasSize(1);
    }

    @Test
    @DisplayName("only SALES_AGENT works with claims: a shopper and the payments admin without it are 403, an anonymous caller 401")
    void whoMay() throws Exception {
        UUID id = claim(UUID.randomUUID(), "MTN", reference());

        mvc.perform(get(CLAIMS + "/" + id)).andExpect(status().isUnauthorized());
        mvc.perform(get(CLAIMS + "/" + id).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER")))).andExpect(status().isForbidden());
        mvc.perform(post(CLAIMS + "/" + id + "/reject").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_PAYMENT_ADMIN"))).contentType(MediaType.APPLICATION_JSON)
                .content("{\"proofType\":\"REFERENCE_ALREADY_USED\"}")).andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------- helpers

    private static String confirmId(ResultActions made) throws Exception {
        return com.jayway.jsonpath.JsonPath.read(made.andReturn().getResponse().getContentAsString(), "$.id");
    }
}
