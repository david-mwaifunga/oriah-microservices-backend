package com.oriah.promotion.integration;

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

import com.oriah.promotion.TestcontainersConfiguration;
import com.oriah.promotion.repository.PromotionRepository;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"eureka.client.enabled=false"})
class PromotionIntegrationTest {

    private static final SimpleGrantedAuthority ROLE_USER = new SimpleGrantedAuthority("ROLE_USER");
    private static final SimpleGrantedAuthority ROLE_CATALOG_ADMIN = new SimpleGrantedAuthority("ROLE_CATALOG_ADMIN");

    @Autowired
    private MockMvc mvc;
    @Autowired
    private PromotionRepository repository;
    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    @DisplayName("admin can create a promotion; user can apply it to a subtotal")
    void createAndApply() throws Exception {
        mvc.perform(post("/api/v1/admin/promotions").with(jwt().authorities(ROLE_CATALOG_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"WELCOME15\",\"type\":\"PERCENTAGE\",\"amount\":15,\"maxRedemptions\":100}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("WELCOME15"));

        mvc.perform(post("/api/v1/promotions/WELCOME15/apply").with(jwt().authorities(ROLE_USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subtotal\":200.00}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.discount").value(30.00))
                .andExpect(jsonPath("$.totalAfterDiscount").value(170.00));
    }

    @Test
    @DisplayName("unknown code returns 404 on apply")
    void unknownCodeIs404() throws Exception {
        mvc.perform(post("/api/v1/promotions/NONE/apply").with(jwt().authorities(ROLE_USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subtotal\":100}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("redeem bumps currentRedemptions; second redeem of a 1-use code returns 404")
    void redemptionCap() throws Exception {
        mvc.perform(post("/api/v1/admin/promotions").with(jwt().authorities(ROLE_CATALOG_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"FIRST10\",\"type\":\"FIXED\",\"amount\":10,\"maxRedemptions\":1}"))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/v1/promotions/FIRST10/redeem").with(jwt().authorities(ROLE_USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentRedemptions").value(1));

        // Second redeem: redeemable() guard fails -> 404
        mvc.perform(post("/api/v1/promotions/FIRST10/redeem").with(jwt().authorities(ROLE_USER)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("non-admin user cannot create promotions")
    void userCannotCreate() throws Exception {
        mvc.perform(post("/api/v1/admin/promotions").with(jwt().authorities(ROLE_USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"X\",\"type\":\"FIXED\",\"amount\":1}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("no JWT => 401 on apply")
    void requiresJwt() throws Exception {
        mvc.perform(get("/api/v1/promotions/ANY"))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------
    // Not-found paths -- and the anti-enumeration property they protect.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("GET an unknown code is 404 with a problem body")
    void getUnknownCodeIsAProblem404() throws Exception {
        mvc.perform(get("/api/v1/promotions/NOSUCHCODE").with(jwt().authorities(ROLE_USER)))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.type").value("https://oriah.dev/problems/not-found"))
                .andExpect(jsonPath("$.detail").value(
                        "Promotion not found with the given input data code : 'NOSUCHCODE'"))
                .andExpect(jsonPath("$.traceId").exists());
    }

    @Test
    @DisplayName("an exhausted code is INDISTINGUISHABLE from a code that never existed")
    void exhaustedCodeIsIndistinguishableFromUnknownCode() throws Exception {
        // The security property that made Tier 5 risky here, now asserted
        // rather than merely respected.
        //
        // promotion answers 404 for BOTH "no such code" and "this code exists
        // but you have used up its redemptions" -- deliberately, so a caller
        // cannot enumerate valid codes by watching for a different response.
        // Adding a HELPFUL detail to these 404s would have been the regression:
        // "redemption cap reached" confirms the code is real.
        //
        // So the bodies must match exactly, apart from traceId. This test is
        // what stops someone later "improving" one of the two messages.
        mvc.perform(post("/api/v1/admin/promotions").with(jwt().authorities(ROLE_CATALOG_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"ONEUSE\",\"type\":\"FIXED\",\"amount\":10,\"maxRedemptions\":1}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/promotions/ONEUSE/redeem").with(jwt().authorities(ROLE_USER)))
                .andExpect(status().isOk());

        String exhausted = bodyOf(post("/api/v1/promotions/ONEUSE/redeem"));
        String neverExisted = bodyOf(post("/api/v1/promotions/ONEUSE2/redeem"));

        org.assertj.core.api.Assertions.assertThat(stripTraceId(exhausted))
                .as("an exhausted code and a nonexistent one must be indistinguishable; "
                        + "any difference lets a caller enumerate real codes")
                .isEqualTo(stripTraceId(neverExisted).replace("ONEUSE2", "ONEUSE"));
    }

    @Test
    @DisplayName("admin disable of an unknown code is 404 with a problem body")
    void disableUnknownCodeIsAProblem404() throws Exception {
        mvc.perform(post("/api/v1/admin/promotions/GHOST/disable")
                        .with(jwt().authorities(ROLE_CATALOG_ADMIN)))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value(
                        "Promotion not found with the given input data code : 'GHOST'"));
    }

    private String bodyOf(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req)
            throws Exception {
        return mvc.perform(req.with(jwt().authorities(ROLE_USER)))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
    }

    /** traceId is per-request, so it is the one field that legitimately differs. */
    private static String stripTraceId(String json) {
        return json.replaceAll(",?\"traceId\":\"[0-9a-f]+\"", "");
    }
}
