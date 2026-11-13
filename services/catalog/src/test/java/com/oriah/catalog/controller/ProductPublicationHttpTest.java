package com.oriah.catalog.controller;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cloud.stream.binder.test.TestChannelBinderConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.oriah.catalog.StubInventory;
import com.oriah.catalog.TestcontainersConfiguration;
import com.oriah.catalog.pricing.PricingFixtures;
import com.oriah.catalog.pricing.PricingFixtures.Piece;
import com.oriah.catalog.repository.ProductRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 6: publishing is its own step after pricing, and a product can be taken off sale again, which is counted (inventory lets
 * go of the boxes it set aside for a product when the count goes up). A product also has a default box it goes out in. Both ride on
 * the product's snapshot, which now ends with {@code defaultPackagingVariantId} and {@code unpublishCount}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestChannelBinderConfiguration.class})
@TestPropertySource(properties = {"spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
class ProductPublicationHttpTest {

    private static final String PRODUCTS = "/api/v1/admin/products/";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ProductRepository products;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    private PricingFixtures fixtures;

    @BeforeEach
    void fresh() {
        StubInventory.reset();
        fixtures = new PricingFixtures(products);
    }

    private static RequestPostProcessor admin() {
        return jwt().jwt(j -> j.subject("kc-maya")).authorities(new SimpleGrantedAuthority("ROLE_CATALOG_ADMIN"));
    }

    private Piece publishedRing() throws Exception {
        Piece ring = fixtures.ring("Aurelia");
        PricingFixtures.priceDirectly(jdbc, ring.variantId(), "1500.00");
        mvc.perform(post(PRODUCTS + ring.productId() + "/publish").with(admin())).andExpect(status().isOk());
        return ring;
    }

    private String lastSnapshot(UUID productId) {
        return jdbc.queryForObject("SELECT payload::text FROM outbox WHERE aggregate_type = 'product' AND aggregate_id = ? AND event_type = 'product.updated' "
                + "ORDER BY seq DESC LIMIT 1", String.class, productId.toString());
    }

    // ---------------------------------------------------------------- unpublish

    @Test
    @DisplayName("a published product can be taken off sale: it goes back to DRAFT, the shop stops showing it, and the snapshot carries the count")
    void unpublish() throws Exception {
        Piece ring = publishedRing();
        mvc.perform(get("/api/v1/products/" + ring.productId())).andExpect(status().isOk());

        mvc.perform(post(PRODUCTS + ring.productId() + "/unpublish").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"));

        mvc.perform(get("/api/v1/products/" + ring.productId())).andExpect(status().isNotFound());
        assertThat(lastSnapshot(ring.productId())).contains("\"status\":\"DRAFT\"").contains("\"unpublishCount\":1");
    }

    @Test
    @DisplayName("it can be published and taken off again, and the count only ever goes up")
    void theCountGrows() throws Exception {
        Piece ring = publishedRing();

        mvc.perform(post(PRODUCTS + ring.productId() + "/unpublish").with(admin())).andExpect(status().isOk());
        mvc.perform(post(PRODUCTS + ring.productId() + "/publish").with(admin())).andExpect(status().isOk());
        assertThat(lastSnapshot(ring.productId())).contains("\"status\":\"ACTIVE\"").contains("\"unpublishCount\":1");
        mvc.perform(post(PRODUCTS + ring.productId() + "/unpublish").with(admin())).andExpect(status().isOk());

        assertThat(lastSnapshot(ring.productId())).contains("\"unpublishCount\":2");
    }

    @Test
    @DisplayName("a product that is not published is 409, and a refused unpublish writes no event and counts nothing")
    void onlyAPublishedProductCanBeUnpublished() throws Exception {
        Piece draft = fixtures.ring("Draft");
        long before = jdbc.queryForObject("SELECT count(*) FROM outbox WHERE aggregate_id = ?", Long.class, draft.productId().toString());

        mvc.perform(post(PRODUCTS + draft.productId() + "/unpublish").with(admin())).andExpect(status().isConflict());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox WHERE aggregate_id = ?", Long.class, draft.productId().toString())).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT unpublish_count FROM products WHERE id = ?", Integer.class, draft.productId())).isZero();
    }

    @Test
    @DisplayName("an unknown or deleted product is 404, and CATALOG_ADMIN is needed")
    void unpublishNotFoundAndWho() throws Exception {
        mvc.perform(post(PRODUCTS + UUID.randomUUID() + "/unpublish").with(admin())).andExpect(status().isNotFound());

        Piece ring = publishedRing();
        mvc.perform(post(PRODUCTS + ring.productId() + "/unpublish").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER")))).andExpect(status().isForbidden());
        mvc.perform(post(PRODUCTS + ring.productId() + "/unpublish")).andExpect(status().isUnauthorized());

        jdbc.update("UPDATE products SET is_deleted = TRUE WHERE id = ?", ring.productId());
        mvc.perform(post(PRODUCTS + ring.productId() + "/unpublish").with(admin())).andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------- the default box

    private String chooseBox(UUID productId, UUID boxVariantId) {
        return "{\"packagingVariantId\":" + (boxVariantId == null ? "null" : "\"" + boxVariantId + "\"") + "}";
    }

    @Test
    @DisplayName("a product's default box can be chosen, changed and cleared, and every change is in its snapshot")
    void chooseChangeClear() throws Exception {
        Piece ring = fixtures.ring("Aurelia");
        Piece small = fixtures.box("Small pouch");
        Piece large = fixtures.box("Large box");

        mvc.perform(put(PRODUCTS + ring.productId() + "/default-packaging").with(admin()).contentType(MediaType.APPLICATION_JSON).content(chooseBox(ring.productId(), small.variantId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.defaultPackagingVariantId").value(small.variantId().toString()));
        assertThat(lastSnapshot(ring.productId())).contains("\"defaultPackagingVariantId\":\"" + small.variantId() + "\"");

        mvc.perform(put(PRODUCTS + ring.productId() + "/default-packaging").with(admin()).contentType(MediaType.APPLICATION_JSON).content(chooseBox(ring.productId(), large.variantId())))
                .andExpect(jsonPath("$.defaultPackagingVariantId").value(large.variantId().toString()));
        assertThat(lastSnapshot(ring.productId())).contains("\"defaultPackagingVariantId\":\"" + large.variantId() + "\"");

        mvc.perform(put(PRODUCTS + ring.productId() + "/default-packaging").with(admin()).contentType(MediaType.APPLICATION_JSON).content(chooseBox(ring.productId(), null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.defaultPackagingVariantId").doesNotExist());
        assertThat(lastSnapshot(ring.productId())).contains("\"defaultPackagingVariantId\":null");
    }

    @Test
    @DisplayName("the admin detail read shows the product's default box so an edit screen can reload the choice; the shop's read never does")
    void theAdminDetailShowsTheBoxAndTheShopDoesNot() throws Exception {
        Piece ring = publishedRing();
        Piece box = fixtures.box("Gift box");
        mvc.perform(put(PRODUCTS + ring.productId() + "/default-packaging").with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content(chooseBox(ring.productId(), box.variantId()))).andExpect(status().isOk());

        mvc.perform(get(PRODUCTS + ring.productId()).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.defaultPackagingVariantId").value(box.variantId().toString()));
        mvc.perform(get("/api/v1/products/" + ring.productId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.defaultPackagingVariantId").doesNotExist());
    }

    @Test
    @DisplayName("choosing the box it already has changes nothing and writes no event")
    void choosingTheSameBoxIsQuiet() throws Exception {
        Piece ring = fixtures.ring("Aurelia");
        Piece box = fixtures.box("Gift box");
        mvc.perform(put(PRODUCTS + ring.productId() + "/default-packaging").with(admin()).contentType(MediaType.APPLICATION_JSON).content(chooseBox(ring.productId(), box.variantId())))
                .andExpect(status().isOk());
        long events = jdbc.queryForObject("SELECT count(*) FROM outbox WHERE aggregate_id = ?", Long.class, ring.productId().toString());

        mvc.perform(put(PRODUCTS + ring.productId() + "/default-packaging").with(admin()).contentType(MediaType.APPLICATION_JSON).content(chooseBox(ring.productId(), box.variantId())))
                .andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox WHERE aggregate_id = ?", Long.class, ring.productId().toString())).isEqualTo(events);
    }

    @Test
    @DisplayName("the box must be an active variant of a packaging entry that exists: another piece, an unknown id and a withdrawn box are 400")
    void theBoxMustBeAUsableBox() throws Exception {
        Piece ring = fixtures.ring("Aurelia");
        Piece anotherRing = fixtures.ring("Another ring");
        Piece withdrawn = fixtures.box("Withdrawn box");
        jdbc.update("UPDATE product_variants SET active = FALSE WHERE id = ?", withdrawn.variantId());
        Piece gone = fixtures.box("Deleted box");
        jdbc.update("UPDATE products SET is_deleted = TRUE WHERE id = ?", gone.productId());

        for (UUID notABox : new UUID[] {anotherRing.variantId(), UUID.randomUUID(), withdrawn.variantId(), gone.variantId()}) {
            mvc.perform(put(PRODUCTS + ring.productId() + "/default-packaging").with(admin()).contentType(MediaType.APPLICATION_JSON).content(chooseBox(ring.productId(), notABox)))
                    .andExpect(status().isBadRequest());
        }
        assertThat(jdbc.queryForObject("SELECT default_packaging_variant_id FROM products WHERE id = ?", UUID.class, ring.productId())).isNull();
    }

    @Test
    @DisplayName("packaging has no default box (a box does not go out in a box): 409; an unknown product is 404; CATALOG_ADMIN is needed")
    void theOtherAnswers() throws Exception {
        Piece box = fixtures.box("Gift box");
        Piece other = fixtures.box("Other box");

        mvc.perform(put(PRODUCTS + box.productId() + "/default-packaging").with(admin()).contentType(MediaType.APPLICATION_JSON).content(chooseBox(box.productId(), other.variantId())))
                .andExpect(status().isConflict());
        mvc.perform(put(PRODUCTS + UUID.randomUUID() + "/default-packaging").with(admin()).contentType(MediaType.APPLICATION_JSON).content(chooseBox(box.productId(), other.variantId())))
                .andExpect(status().isNotFound());
        mvc.perform(put(PRODUCTS + box.productId() + "/default-packaging").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"))).contentType(MediaType.APPLICATION_JSON)
                .content(chooseBox(box.productId(), other.variantId()))).andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------- a box that is in use

    private void use(Piece product, UUID boxVariantId) throws Exception {
        mvc.perform(put(PRODUCTS + product.productId() + "/default-packaging").with(admin()).contentType(MediaType.APPLICATION_JSON).content(chooseBox(product.productId(), boxVariantId)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a box that a product goes out in cannot be deleted: 409 packaging-in-use naming the products, nothing deleted or announced; once none uses it, it can be")
    void aBoxInUseCannotBeDeleted() throws Exception {
        Piece ring = fixtures.ring("Aurelia");
        Piece other = fixtures.ring("Another ring");
        Piece box = fixtures.box("Gift box");
        Piece spare = fixtures.box("Spare box");
        use(ring, box.variantId());
        use(other, box.variantId());

        mvc.perform(delete(PRODUCTS + box.productId()).with(admin()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://oriah.dev/problems/packaging-in-use"))
                .andExpect(jsonPath("$.products.length()").value(2))
                .andExpect(jsonPath("$.products[*].id", containsInAnyOrder(ring.productId().toString(), other.productId().toString())));
        assertThat(jdbc.queryForObject("SELECT is_deleted FROM products WHERE id = ?", Boolean.class, box.productId())).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox WHERE aggregate_id = ? AND event_type = 'product.deleted'", Long.class, box.productId().toString())).isZero();

        use(ring, spare.variantId());   // one product moves to another box: the other still holds this one
        mvc.perform(delete(PRODUCTS + box.productId()).with(admin()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.products.length()").value(1))
                .andExpect(jsonPath("$.products[0].id").value(other.productId().toString()));

        use(other, null);                // and now nobody does
        mvc.perform(delete(PRODUCTS + box.productId()).with(admin())).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT is_deleted FROM products WHERE id = ?", Boolean.class, box.productId())).isTrue();
    }

    @Test
    @DisplayName("a product that has itself been deleted no longer holds its box, and a box that nobody chose is deleted as ever")
    void aDeletedProductDoesNotHoldItsBox() throws Exception {
        Piece ring = fixtures.ring("Aurelia");
        Piece box = fixtures.box("Gift box");
        Piece unused = fixtures.box("Never chosen");
        use(ring, box.variantId());
        mvc.perform(delete(PRODUCTS + ring.productId()).with(admin())).andExpect(status().isNoContent());

        mvc.perform(delete(PRODUCTS + box.productId()).with(admin())).andExpect(status().isNoContent());
        mvc.perform(delete(PRODUCTS + unused.productId()).with(admin())).andExpect(status().isNoContent());
    }

    // ---------------------------------------------------------------- retired

    @Test
    @DisplayName("the old way of changing a price, PATCH on the variant, is gone: it answered with a price saved unseen, with nothing compared with the cost")
    void theOldRepriceEndpointIsGone() throws Exception {
        Piece ring = fixtures.ring("Aurelia");

        mvc.perform(patch(PRODUCTS + ring.productId() + "/variants/" + ring.variantId()).with(admin()).contentType(MediaType.APPLICATION_JSON).content("{\"unitPrice\":1.00}"))
                .andExpect(status().is4xxClientError());

        assertThat(jdbc.queryForObject("SELECT unit_price FROM product_variants WHERE id = ?", java.math.BigDecimal.class, ring.variantId())).isEqualByComparingTo("0.00");
    }
}
