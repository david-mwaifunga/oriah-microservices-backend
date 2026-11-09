package com.oriah.catalog;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cloud.stream.binder.test.TestChannelBinderConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What an unauthenticated caller can and cannot reach on this service
 * directly.
 *
 * <h2>Why directly, and not through the gateway</h2>
 *
 * The gateway has a {@code PUBLIC_GET_PATHS} allow-list, and it is tempting to
 * treat that as the security model. It is not: catalog's own filter chain ends
 * in {@code anyRequest().authenticated()}, so a path on the gateway's list
 * that this service does not also permit gets forwarded and then refused with
 * a 401. Adding a path in one place and not the other is a silent
 * half-configuration — the kind that passes a code review because each file
 * looks right on its own.
 *
 * <p>It is also not hypothetical that this service is reachable without the
 * gateway: every one of the twelve domain services publishes its own host port
 * in the development compose file, deliberately, and the smoke test probes
 * them that way.
 *
 * <p>So these assertions bypass the gateway entirely. A full
 * {@code @SpringBootTest} rather than a slice, because the filter chain is the
 * subject and {@code @WebMvcTest} would not load it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestChannelBinderConfiguration.class})
@TestPropertySource(properties = {
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false"
})
class PublicAccessTest {

    @Autowired
    private MockMvc mvc;

    /**
     * The resource-server auto-config registers a {@code JwtDecoder} that
     * fetches JWKS from issuer-uri at startup, and there is no Keycloak here.
     * Mocking the bean keeps the context healthy; it is never invoked, because
     * every request below is either anonymous or expected to be rejected
     * before a token is parsed. Same shape as ContactInfoTest.
     */
    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    @Test
    @DisplayName("the category tree is readable anonymously")
    void categoriesArePublic() throws Exception {
        mvc.perform(get("/api/v1/categories"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("product types are readable anonymously, with their attribute definitions")
    void productTypesArePublic() throws Exception {
        mvc.perform(get("/api/v1/product-types"))
                .andExpect(status().isOk())
                // Seeded by V3 and verified against the Java registry at
                // startup, so both must be present.
                .andExpect(jsonPath("$[?(@.code == 'JEWELLERY')]").exists())
                .andExpect(jsonPath("$[?(@.code == 'WATCH')]").exists())
                // The whole reason this endpoint exists: a watch declares
                // movement, so a product detail page no longer has to assert
                // every product is solid 18k gold.
                .andExpect(jsonPath("$[?(@.code == 'WATCH')].attributes[?(@.key == 'movement')]")
                        .exists());
    }

    @Test
    @DisplayName("the product list is readable anonymously, as it already was")
    void productsArePublic() throws Exception {
        mvc.perform(get("/api/v1/products"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("variant pricing is callable anonymously, because prices are public")
    void pricingIsPublic() throws Exception {
        mvc.perform(post("/api/v1/variants/pricing")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"variantIds\":[\"11111111-1111-4111-8111-111111111111\"]}"))
                .andExpect(status().isOk())
                // An id nobody knows comes back UNKNOWN rather than as a 404
                // or an omission, so a bag can tell "withdrawn" from "bogus".
                .andExpect(jsonPath("$.results[0].status").value("UNKNOWN"));
    }

    @Test
    @DisplayName("contact-info STILL requires a token, because declaration order matters")
    void contactInfoIsStillProtected() throws Exception {
        // The regression guard for this whole change. This matcher is declared
        // BEFORE the broad /api/v1/products/** permitAll, and Spring Security
        // matches in declaration order, first match wins. Inserting the new
        // permits in the wrong place would quietly expose on-call phone
        // numbers to anonymous callers.
        mvc.perform(get("/api/v1/products/contact-info"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("admin writes still require a token")
    void adminWritesAreStillProtected() throws Exception {
        mvc.perform(post("/api/v1/admin/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Anonymous Product\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a pricing request with no ids is a 400, not an empty 200")
    void emptyPricingRequestRejected() throws Exception {
        mvc.perform(post("/api/v1/variants/pricing")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"variantIds\":[]}"))
                .andExpect(status().isBadRequest());
    }
}
