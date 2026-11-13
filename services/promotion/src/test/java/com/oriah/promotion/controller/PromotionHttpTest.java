package com.oriah.promotion.controller;

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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.oriah.promotion.TestClockConfiguration;
import com.oriah.promotion.TestcontainersConfiguration;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Promotions over HTTP (Phase 7). Admin keeps them under {@code /api/v1/admin/promotions} (CATALOG_ADMIN; there is no PROMOTION_ADMIN role in the
 * realm). Catalog, which prices every piece, reads what is in force from {@code /internal/promotions/in-force}: a read-only definition list
 * that needs no token (catalog prices anonymously) and is never routed by the gateway. The code-based endpoints are gone.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestClockConfiguration.class})
@TestPropertySource(properties = {"eureka.client.enabled=false"})
class PromotionHttpTest {

    private static final String ADMIN = "/api/v1/admin/promotions";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void clock() {
        TestClockConfiguration.CLOCK.set(TestClockConfiguration.T0);
    }

    private static RequestPostProcessor catalogAdmin() {
        return jwt().jwt(j -> j.subject("kc-maya")).authorities(new SimpleGrantedAuthority("ROLE_CATALOG_ADMIN"));
    }

    private static RequestPostProcessor shopper() {
        return jwt().jwt(j -> j.subject("kc-alice")).authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }

    private static String body(String name, String scope, UUID target, String type, String amount, String from, String to) {
        return "{\"name\":\"" + name + "\",\"scopeType\":\"" + scope + "\"," + (target == null ? "" : "\"scopeId\":\"" + target + "\",")
                + "\"discountType\":\"" + type + "\",\"amount\":" + amount + ",\"startsOn\":\"" + from + "\",\"endsOn\":\"" + to + "\"}";
    }

    private String idOf(MvcResult r) throws Exception {
        return com.jayway.jsonpath.JsonPath.read(r.getResponse().getContentAsString(), "$.id");
    }

    @Test
    @DisplayName("only CATALOG_ADMIN keeps promotions: a shopper is refused and a call with no token is 401")
    void whoMay() throws Exception {
        mvc.perform(get(ADMIN).with(shopper())).andExpect(status().isForbidden());
        mvc.perform(get(ADMIN)).andExpect(status().isUnauthorized());
        mvc.perform(get(ADMIN).with(catalogAdmin())).andExpect(status().isOk());
        mvc.perform(post(ADMIN).with(shopper()).contentType(MediaType.APPLICATION_JSON).content(body("x", "SHOP", null, "PERCENTAGE", "10.00", "2026-12-01", "2026-12-31")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a promotion is created (201), listed, read, replaced (201) and stopped (200); a second stop is a 409 `already-stopped`")
    void theLifeOfAPromotion() throws Exception {
        UUID product = UUID.randomUUID();
        MvcResult made = mvc.perform(post(ADMIN).with(catalogAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content(body("Aurelia week", "PRODUCT", product, "FIXED", "100.00", "2026-11-20", "2026-11-27")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.scopeType").value("PRODUCT"))
                .andExpect(jsonPath("$.scopeId").value(product.toString()))
                .andExpect(jsonPath("$.amount").value(100.00))
                .andExpect(jsonPath("$.startsOn").value("2026-11-20"))
                .andExpect(jsonPath("$.endsOn").value("2026-11-27"))
                .andExpect(jsonPath("$.createdBy").value("user:kc-maya"))
                .andReturn();
        String id = idOf(made);

        mvc.perform(get(ADMIN + "/" + id).with(catalogAdmin())).andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Aurelia week"));
        mvc.perform(get(ADMIN).with(catalogAdmin()).param("scopeType", "PRODUCT")).andExpect(status().isOk()).andExpect(jsonPath("$[*].id", hasItem(id)));

        MvcResult replaced = mvc.perform(post(ADMIN + "/" + id + "/replace").with(catalogAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content(body("Aurelia week, deeper", "PRODUCT", product, "FIXED", "150.00", "2026-11-20", "2026-11-27")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.replacesId").value(id)).andExpect(jsonPath("$.amount").value(150.00)).andReturn();
        mvc.perform(get(ADMIN + "/" + id).with(catalogAdmin())).andExpect(jsonPath("$.stoppedAt").exists());
        mvc.perform(post(ADMIN + "/" + id + "/stop").with(catalogAdmin())).andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://oriah.dev/problems/already-stopped"));

        mvc.perform(post(ADMIN + "/" + idOf(replaced) + "/stop").with(catalogAdmin())).andExpect(status().isOk()).andExpect(jsonPath("$.stoppedBy").value("user:kc-maya"));
        mvc.perform(get(ADMIN + "/" + UUID.randomUUID()).with(catalogAdmin())).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://oriah.dev/problems/promotion-not-found"));
    }

    @Test
    @DisplayName("a promotion that makes no sense is a 400: a fraction of an ngwee, a percentage over 100, a start in the past, a shop promotion naming a target")
    void validation() throws Exception {
        for (String bad : new String[] {
                body("x", "SHOP", null, "FIXED", "10.005", "2026-12-01", "2026-12-31"),
                body("x", "SHOP", null, "PERCENTAGE", "100.01", "2026-12-01", "2026-12-31"),
                body("x", "SHOP", null, "PERCENTAGE", "10.00", "2026-11-13", "2026-12-31"),
                body("x", "SHOP", UUID.randomUUID(), "PERCENTAGE", "10.00", "2026-12-01", "2026-12-31"),
                body("", "SHOP", null, "PERCENTAGE", "10.00", "2026-12-01", "2026-12-31"),
                "{\"name\":\"x\"}"}) {
            mvc.perform(post(ADMIN).with(catalogAdmin()).contentType(MediaType.APPLICATION_JSON).content(bad)).andExpect(status().isBadRequest());
        }
    }

    @Test
    @DisplayName("what is in force is readable by catalog with no token, at any instant: the answer for a past moment is the one rows give")
    void inForceIsAnAnonymousRead() throws Exception {
        MvcResult made = mvc.perform(post(ADMIN).with(catalogAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content(body("Independence week", "SHOP", null, "PERCENTAGE", "12.50", "2026-11-20", "2026-11-27"))).andExpect(status().isCreated()).andReturn();
        String id = idOf(made);

        mvc.perform(get("/internal/promotions/in-force").param("at", "2026-11-21T10:00:00Z")).andExpect(status().isOk())
                .andExpect(jsonPath("$.at").value("2026-11-21T10:00:00Z"))
                .andExpect(jsonPath("$.promotions[*].id", hasItem(id)))
                .andExpect(jsonPath("$.promotions[?(@.id=='" + id + "')].amount").value(12.50));
        mvc.perform(get("/internal/promotions/in-force").param("at", "2026-11-28T10:00:00Z")).andExpect(status().isOk())
                .andExpect(jsonPath("$.promotions[*].id", not(hasItem(id))));
        mvc.perform(get("/internal/promotions/in-force")).andExpect(status().isOk()).andExpect(jsonPath("$.at").value("2026-11-14T08:00:00Z"));
        mvc.perform(get("/internal/promotions/in-force").param("at", "not-a-time")).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("the discount codes are gone: the old apply and redeem endpoints answer 404")
    void theOldEndpointsAreGone() throws Exception {
        mvc.perform(post("/api/v1/promotions/apply").with(shopper()).contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"WELCOME15\",\"subtotal\":100.00}"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/promotions/WELCOME15").with(shopper())).andExpect(status().isNotFound());
        mvc.perform(post(ADMIN + "/WELCOME15/disable").with(catalogAdmin())).andExpect(status().is4xxClientError());
    }
}
