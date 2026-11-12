package com.oriah.catalog.controller;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cloud.stream.binder.test.TestChannelBinderConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.oriah.catalog.TestcontainersConfiguration;
import com.oriah.catalog.storage.TestImages;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Each place a shopper can reach catalog checks "sellable" for itself, and does
 * not lean only on "a packaging entry is never ACTIVE".
 *
 * <p>That second rule is held twice already -- {@code Product.publish()} refuses
 * packaging and a CHECK in V5 refuses an ACTIVE packaging row. This test is for
 * the day someone loosens one of them by mistake, or edits a row by hand: if a
 * packaging entry were ACTIVE, with an active variant priced above zero (every
 * OTHER condition that makes a product visible and buyable), the public list,
 * the public read, the anonymous image route and the price the cart, checkout and
 * order trust would still have to leave it alone: those four are every anonymous
 * read of catalog that starts from "is the product ACTIVE".
 *
 * <p>How: the test drops the CHECK and writes the forbidden row with SQL, inside
 * a transaction the test rolls back. PostgreSQL's DDL is transactional, so the
 * constraint comes back with the rollback; and the requests run on the same
 * thread, so they read the same uncommitted rows. A SELLABLE twin built the same
 * way is the control: it IS visible and buyable, which proves the packaging
 * entry is hidden by its kind and not by some other flaw in the fixture.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import({TestcontainersConfiguration.class, TestChannelBinderConfiguration.class})
@TestPropertySource(properties = {"eureka.client.enabled=false"})
class PackagingStorefrontBoundaryTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    private record Listed(UUID productId, UUID variantId) {
    }

    private Listed insertActiveWithPricedVariant(String kind, String typeCode, String slug) {
        UUID productId = UUID.randomUUID();
        UUID variantId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO products (id, title, slug, product_type_code, status, kind)
                VALUES (?, ?, ?, ?, 'ACTIVE', ?)""", productId, "Boundary " + slug, slug, typeCode, kind);
        jdbc.update("""
                INSERT INTO product_variants (id, product_id, sku, unit_price, currency, option_digest, active)
                VALUES (?, ?, ?, 25.00, 'ZMW', ?, TRUE)""", variantId, productId, "SKU-" + slug, "digest-" + slug);
        return new Listed(productId, variantId);
    }

    private Listed[] forbiddenAndControl() {
        // The forbidden state is only reachable with the CHECK gone; rolled back with the test.
        jdbc.execute("ALTER TABLE products DROP CONSTRAINT products_packaging_never_listed");
        String tag = UUID.randomUUID().toString().substring(0, 8);
        return new Listed[] {
                insertActiveWithPricedVariant("PACKAGING", "PACKAGING", "boundary-box-" + tag),
                insertActiveWithPricedVariant("SELLABLE", "JEWELLERY", "boundary-ring-" + tag)
        };
    }

    @Test
    @DisplayName("the public list leaves out an ACTIVE packaging entry and still lists the sellable twin")
    void publicListLeavesPackagingOut() throws Exception {
        Listed[] both = forbiddenAndControl();

        mvc.perform(get("/api/v1/products?size=100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id", hasItem(both[1].productId().toString())))
                .andExpect(jsonPath("$.content[*].id", not(hasItem(both[0].productId().toString()))));
    }

    @Test
    @DisplayName("the public product read 404s an ACTIVE packaging entry and serves the sellable twin")
    void publicReadHidesPackaging() throws Exception {
        Listed[] both = forbiddenAndControl();

        mvc.perform(get("/api/v1/products/" + both[1].productId())).andExpect(status().isOk());
        mvc.perform(get("/api/v1/products/" + both[0].productId())).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("the anonymous image route 404s a photograph of an ACTIVE packaging entry and serves the sellable twin's")
    void publicImageReadHidesPackaging() throws Exception {
        Listed[] both = forbiddenAndControl();
        String boxImage = upload(both[0].productId());
        String ringImage = upload(both[1].productId());

        mvc.perform(get("/api/v1/product-images/" + ringImage)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/product-images/" + boxImage)).andExpect(status().isNotFound());
    }

    /** Uploads through the real admin endpoint, as an operator would, and returns the image id. */
    private String upload(UUID productId) throws Exception {
        var file = new MockMultipartFile("file", "photo.jpg", "image/jpeg", TestImages.jpeg(600, 400));
        String body = mvc.perform(multipart("/api/v1/admin/products/{id}/images", productId)
                        .file(file)
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CATALOG_ADMIN"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return body.replaceAll(".*\"id\"\\s*:\\s*\"([^\"]+)\".*", "$1");
    }

    @Test
    @DisplayName("the price the cart trusts is withheld for an ACTIVE packaging variant, and given for the sellable twin")
    void pricingWithholdsPackaging() throws Exception {
        Listed[] both = forbiddenAndControl();

        mvc.perform(post("/api/v1/variants/pricing")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"variantIds\":[\"" + both[1].variantId() + "\",\"" + both[0].variantId() + "\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].status").value("PRICED"))
                .andExpect(jsonPath("$.results[1].status").value("UNKNOWN"))
                .andExpect(jsonPath("$.results[1].unitPrice").doesNotExist())
                .andExpect(jsonPath("$.results[1].productTitle").doesNotExist());
    }
}
