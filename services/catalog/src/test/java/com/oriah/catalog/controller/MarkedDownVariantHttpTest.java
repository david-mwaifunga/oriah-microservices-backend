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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oriah.catalog.PricedCatalogue;
import com.oriah.catalog.StubInventory;
import com.oriah.catalog.StubPromotion;
import com.oriah.catalog.TestcontainersConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A returned piece that is sold again at a lower price gets a listing of its own (Phase 12): a MARKED_DOWN variant of the same product, made
 * for that one piece. It copies the options of the variant the piece was sold as, starts unpriced, and is priced through the ordinary
 * pricing endpoint against the cost of its OWN one-piece lot. It is kept apart from the product's ordinary variants: it never takes a
 * promotion, it is never the product's "from" price, and it never keeps a product on the shop by itself.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestChannelBinderConfiguration.class})
@TestPropertySource(properties = {"spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
class MarkedDownVariantHttpTest {

    private static final String PRODUCTS = "/api/v1/admin/products/";
    private static final String PRICING = "/api/v1/admin/pricing/variants/";
    private static final String PROBLEMS = "https://oriah.dev/problems/";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    /** A product with one ordinary variant. */
    private record Ring(UUID product, UUID variant, String sku) {
    }

    @BeforeEach
    void fresh() {
        StubInventory.reset();
        StubPromotion.reset();
    }

    /** The two stubs are shared by every test class of the run: a promotion left switched on here would discount another class's prices. */
    @org.junit.jupiter.api.AfterEach
    void leaveNothingBehind() {
        StubInventory.reset();
        StubPromotion.reset();
    }

    private static RequestPostProcessor admin() {
        return jwt().jwt(j -> j.subject("kc-maya").claim("name", "Maya Banda"))
                .authorities(new SimpleGrantedAuthority("ROLE_CATALOG_ADMIN"), new SimpleGrantedAuthority("ROLE_COST_VIEWER"));
    }

    private Ring ring(String productJson, String variantJson) throws Exception {
        JsonNode product = JSON.readTree(mvc.perform(post("/api/v1/admin/products").with(admin()).contentType(MediaType.APPLICATION_JSON).content(productJson))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        String id = product.get("id").asText();
        JsonNode added = JSON.readTree(mvc.perform(post(PRODUCTS + id + "/variants").with(admin()).contentType(MediaType.APPLICATION_JSON).content(variantJson))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        JsonNode variant = added.get("variants").get(0);
        return new Ring(UUID.fromString(id), UUID.fromString(variant.get("id").asText()), variant.get("sku").asText());
    }

    private Ring ring() throws Exception {
        return ring("{\"title\":\"Aurelia Ring " + UUID.randomUUID() + "\"}", "{\"sku\":\"AUR-" + UUID.randomUUID().toString().substring(0, 8) + "\"}");
    }

    private ResultActions markDown(UUID product, UUID variant, UUID source, UUID piece) throws Exception {
        return mvc.perform(post(PRODUCTS + product + "/variants/marked-down").with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"variantId\":\"%s\",\"sourceVariantId\":\"%s\",\"pieceId\":\"%s\"}".formatted(variant, source, piece)));
    }

    /** A marked-down variant of the ring, made; its id. */
    private UUID markedDown(Ring ring) throws Exception {
        UUID child = UUID.randomUUID();
        markDown(ring.product(), child, ring.variant(), UUID.randomUUID()).andExpect(status().isCreated());
        return child;
    }

    private ResultActions setPrice(UUID variant, String price) throws Exception {
        return mvc.perform(put(PRICING + variant + "/price").with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"unitPrice\":" + price + ",\"expectedPriceId\":null}"));
    }

    private void publish(Ring ring) throws Exception {
        mvc.perform(post(PRODUCTS + ring.product() + "/publish").with(admin())).andExpect(status().isOk());
    }

    // ---------------------------------------------------------------- making one

    @Test
    @DisplayName("a product that varies on nothing takes a marked-down variant: its own id, unpriced, pointing at the variant it came from")
    void aProductWithNoAxesTakesOne() throws Exception {
        Ring ring = ring();
        UUID child = UUID.randomUUID();
        UUID piece = UUID.randomUUID();

        markDown(ring.product(), child, ring.variant(), piece)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.variants", hasSize(2)))
                .andExpect(jsonPath("$.variants[0].condition").value("NEW"))
                .andExpect(jsonPath("$.variants[0].sourceVariantId").doesNotExist())
                .andExpect(jsonPath("$.variants[1].id").value(child.toString()))
                .andExpect(jsonPath("$.variants[1].condition").value("MARKED_DOWN"))
                .andExpect(jsonPath("$.variants[1].sourceVariantId").value(ring.variant().toString()))
                .andExpect(jsonPath("$.variants[1].unitPrice").value(0.00))
                .andExpect(jsonPath("$.variants[1].sku").value(ring.sku() + "-R-" + piece.toString().substring(0, 8)));

        assertThat(jdbc.queryForObject("SELECT markdown_piece_id FROM product_variants WHERE id = ?", UUID.class, child)).isEqualTo(piece);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM variant_price WHERE variant_id = ?", Long.class, child)).as("created unpriced").isZero();
    }

    @Test
    @DisplayName("it copies the options of the variant it came from, and a second piece of the same variant gets a listing of its own")
    void itCopiesItsSourcesOptions() throws Exception {
        Ring ring = ring("{\"title\":\"Sized Ring " + UUID.randomUUID() + "\",\"optionNames\":[\"Ring Size\"]}",
                "{\"sku\":\"SZ-" + UUID.randomUUID().toString().substring(0, 8) + "\",\"options\":{\"Ring Size\":\"M\"}}");

        markDown(ring.product(), UUID.randomUUID(), ring.variant(), UUID.randomUUID())
                .andExpect(status().isCreated()).andExpect(jsonPath("$.variants[1].options['Ring Size']").value("M"));
        markDown(ring.product(), UUID.randomUUID(), ring.variant(), UUID.randomUUID())
                .andExpect(status().isCreated()).andExpect(jsonPath("$.variants", hasSize(3))).andExpect(jsonPath("$.variants[2].options['Ring Size']").value("M"));

        // An ordinary variant may still not repeat an option combination.
        mvc.perform(post(PRODUCTS + ring.product() + "/variants").with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"SZ-" + UUID.randomUUID().toString().substring(0, 8) + "\",\"options\":{\"Ring Size\":\"M\"}}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("one listing per returned piece: the same request again answers 200 and makes nothing; another request for that piece is 409 markdown-piece-used")
    void onePerPiece() throws Exception {
        Ring ring = ring();
        UUID child = UUID.randomUUID();
        UUID piece = UUID.randomUUID();
        markDown(ring.product(), child, ring.variant(), piece).andExpect(status().isCreated());

        markDown(ring.product(), child, ring.variant(), piece).andExpect(status().isOk()).andExpect(jsonPath("$.variants", hasSize(2)));
        markDown(ring.product(), UUID.randomUUID(), ring.variant(), piece)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.type").value(PROBLEMS + "markdown-piece-used"));
        markDown(ring.product(), child, ring.variant(), UUID.randomUUID())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.type").value(PROBLEMS + "markdown-piece-used"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM product_variants WHERE product_id = ?", Long.class, ring.product())).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox WHERE aggregate_id = ? AND event_type = 'product.updated'", Long.class,
                ring.product().toString())).as("the first add and the one markdown: a repeat announces nothing").isEqualTo(2);
    }

    @Test
    @DisplayName("only from an ordinary variant of the same product that is for sale: anything else is refused by name and nothing is made")
    void onlyFromANewVariantOfTheSameSellableProduct() throws Exception {
        Ring ring = ring();
        Ring other = ring();
        UUID child = markedDown(ring);

        markDown(ring.product(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.type").value(PROBLEMS + "variant-not-found"));
        markDown(ring.product(), UUID.randomUUID(), other.variant(), UUID.randomUUID())
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.type").value(PROBLEMS + "variant-not-found"));
        markDown(ring.product(), UUID.randomUUID(), child, UUID.randomUUID())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.type").value(PROBLEMS + "already-marked-down"));
        markDown(UUID.randomUUID(), UUID.randomUUID(), ring.variant(), UUID.randomUUID()).andExpect(status().isNotFound());

        Ring box = ring("{\"title\":\"Gift box " + UUID.randomUUID() + "\",\"kind\":\"PACKAGING\"}",
                "{\"sku\":\"BOX-" + UUID.randomUUID().toString().substring(0, 8) + "\"}");
        markDown(box.product(), UUID.randomUUID(), box.variant(), UUID.randomUUID())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.type").value(PROBLEMS + "packaging-is-not-priced"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM product_variants WHERE product_id IN (?, ?, ?)", Long.class, ring.product(), other.product(),
                box.product())).isEqualTo(4);
    }

    @Test
    @DisplayName("its SKU is the source's, cut to fit, then -R- and the piece's first eight characters; a SKU already taken is 409 sku-unavailable")
    void theSkuIsDerivedAndChecked() throws Exception {
        String longSku = "L" + "0123456789".repeat(6) + UUID.randomUUID().toString().substring(0, 3);   // 64 characters
        Ring ring = ring("{\"title\":\"Long SKU Ring " + UUID.randomUUID() + "\"}", "{\"sku\":\"" + longSku + "\"}");
        UUID piece = UUID.randomUUID();
        String derived = longSku.substring(0, 53) + "-R-" + piece.toString().substring(0, 8);
        assertThat(derived).hasSize(64);

        // Somebody already uses that exact SKU.
        Ring taken = ring("{\"title\":\"Other Ring " + UUID.randomUUID() + "\"}", "{\"sku\":\"" + derived + "\"}");
        markDown(ring.product(), UUID.randomUUID(), ring.variant(), piece)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.type").value(PROBLEMS + "sku-unavailable"));
        assertThat(taken.sku()).isEqualTo(derived);

        UUID another = UUID.randomUUID();
        markDown(ring.product(), UUID.randomUUID(), ring.variant(), another)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.variants[1].sku").value(longSku.substring(0, 53) + "-R-" + another.toString().substring(0, 8)));
    }

    @Test
    @DisplayName("its title says what it is, wherever a variant's title is read: the pricing view and the product's snapshot for inventory")
    void itsTitleSaysSoEverywhere() throws Exception {
        Ring ring = ring();
        UUID child = markedDown(ring);

        mvc.perform(get(PRICING + child).with(admin())).andExpect(status().isOk())
                .andExpect(jsonPath("$.variantTitle", endsWith(" - returned, marked down")));
        mvc.perform(get(PRICING + ring.variant()).with(admin())).andExpect(status().isOk())
                .andExpect(jsonPath("$.variantTitle", org.hamcrest.Matchers.not(containsString("marked down"))));
        String snapshot = jdbc.queryForObject("""
                SELECT payload::text FROM outbox WHERE aggregate_id = ? AND event_type = 'product.updated' ORDER BY outbox_id DESC LIMIT 1""", String.class,
                ring.product().toString());
        assertThat(snapshot).contains("returned, marked down").doesNotContain("MARKED_DOWN").doesNotContain("sourceVariantId");
    }

    @Test
    @DisplayName("only the people who manage the catalogue may make one")
    void whoMayMarkDown() throws Exception {
        Ring ring = ring();
        String body = "{\"variantId\":\"%s\",\"sourceVariantId\":\"%s\",\"pieceId\":\"%s\"}".formatted(UUID.randomUUID(), ring.variant(), UUID.randomUUID());

        mvc.perform(post(PRODUCTS + ring.product() + "/variants/marked-down").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        for (String role : new String[] {"ROLE_USER", "ROLE_COST_VIEWER", "ROLE_ORDER_ADMIN", "ROLE_SALES_AGENT"}) {
            mvc.perform(post(PRODUCTS + ring.product() + "/variants/marked-down").with(jwt().authorities(new SimpleGrantedAuthority(role)))
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        }
        mvc.perform(post(PRODUCTS + ring.product() + "/variants/marked-down").with(admin()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------- pricing one

    @Test
    @DisplayName("it is priced against its own lot, not its source's: K900.00 held for it, K1,000.00 is fine and K800.00 is below cost")
    void aMarkdownIsJudgedAgainstItsOwnLot() throws Exception {
        Ring ring = ring();
        UUID child = markedDown(ring);
        StubInventory.holds(ring.variant(), StubInventory.lot("1500.00", 4));
        StubInventory.holds(child, StubInventory.lot("900.00", 1));

        setPrice(child, "800.00").andExpect(status().isConflict()).andExpect(jsonPath("$.type").value(PROBLEMS + "below-landed-cost"))
                .andExpect(jsonPath("$.floor").value(900.00));
        setPrice(child, "1000.00").andExpect(status().isOk()).andExpect(jsonPath("$.assessment").value("OK"));
    }

    @Test
    @DisplayName("it cannot be priced before its piece's cost is known (the piece is not in its own lot yet, or it is sold): 409 markdown-cost-not-ready, nothing saved")
    void aMarkdownWithNoCostYetIsNotPriced() throws Exception {
        Ring ring = ring();
        UUID child = markedDown(ring);

        setPrice(child, "1000.00").andExpect(status().isConflict()).andExpect(jsonPath("$.type").value(PROBLEMS + "markdown-cost-not-ready"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM variant_price WHERE variant_id = ?", Long.class, child)).isZero();
        // An ordinary piece with no stock is still priced, as before: nothing to compare with.
        setPrice(ring.variant(), "1500.00").andExpect(status().isOk()).andExpect(jsonPath("$.assessment").value("NO_COST_KNOWN"));
    }

    // ---------------------------------------------------------------- kept apart from the ordinary variants

    @Test
    @DisplayName("a promotion on the whole shop takes nothing off a marked-down piece, and still discounts the ordinary one")
    void aMarkdownTakesNoPromotion() throws Exception {
        Ring ring = ring();
        UUID child = markedDown(ring);
        PricedCatalogue.priceDirectly(jdbc, ring.variant(), "1500.00");
        PricedCatalogue.priceDirectly(jdbc, child, "1000.00");
        publish(ring);
        StubPromotion.promote(StubPromotion.definition("SHOP", null, "PERCENTAGE", "10.00", "2026-11-01"));

        mvc.perform(post("/api/v1/variants/pricing").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"variantIds\":[\"" + ring.variant() + "\",\"" + child + "\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].discountPerPiece").value(150.00))
                .andExpect(jsonPath("$.results[0].netUnitPrice").value(1350.00))
                .andExpect(jsonPath("$.results[1].status").value("PRICED"))
                .andExpect(jsonPath("$.results[1].variantTitle", endsWith(" - returned, marked down")))
                .andExpect(jsonPath("$.results[1].discountPerPiece").value(0.00))
                .andExpect(jsonPath("$.results[1].promotionId").doesNotExist())
                .andExpect(jsonPath("$.results[1].netUnitPrice").value(1000.00));
    }

    @Test
    @DisplayName("it is never the product's 'from' price, in the product's own answer or in the shop's price filter")
    void aMarkdownIsNeverTheFromPrice() throws Exception {
        Ring ring = ring();
        UUID child = markedDown(ring);
        PricedCatalogue.priceDirectly(jdbc, ring.variant(), "1500.00");
        PricedCatalogue.priceDirectly(jdbc, child, "900.00");
        publish(ring);

        mvc.perform(get(PRODUCTS + ring.product()).with(admin())).andExpect(status().isOk())
                .andExpect(jsonPath("$.priceFrom").value(1500.00)).andExpect(jsonPath("$.priceTo").value(1500.00));
        String cheap = mvc.perform(get("/api/v1/products").param("maxPrice", "1000.00").param("size", "100")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(cheap).as("a shopper filtering up to K1,000.00 is not shown a ring whose ordinary price is K1,500.00").doesNotContain(ring.product().toString());
    }

    @Test
    @DisplayName("it never keeps a product on the shop by itself: a product whose only priced variant is a marked-down piece cannot be published")
    void aMarkdownAloneDoesNotKeepAProductListed() throws Exception {
        Ring ring = ring();
        UUID child = markedDown(ring);
        PricedCatalogue.priceDirectly(jdbc, child, "900.00");

        mvc.perform(post(PRODUCTS + ring.product() + "/publish").with(admin())).andExpect(status().isConflict());

    }

    @Test
    @DisplayName("priced below its own cost on purpose, it is on the below-cost list like any other piece, judged on its own price with no promotion")
    void aMarkdownBelowCostIsListedLikeAnyOther() throws Exception {
        com.oriah.catalog.pricing.PricingFixtures.wipeCatalogue(jdbc);
        Ring ring = ring();
        UUID child = markedDown(ring);
        PricedCatalogue.priceDirectly(jdbc, ring.variant(), "1500.00");
        PricedCatalogue.priceDirectly(jdbc, child, "800.00");
        StubInventory.holds(ring.variant(), StubInventory.lot("1000.00", 4));
        StubInventory.holds(child, StubInventory.lot("900.00", 1));
        StubPromotion.promote(StubPromotion.definition("SHOP", null, "PERCENTAGE", "50.00", "2026-11-01"));

        mvc.perform(get("/api/v1/admin/pricing/below-cost").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                // The ordinary ring is K750.00 under the promotion, K250.00 below its K1,000.00 cost: the bigger shortfall, so first.
                .andExpect(jsonPath("$.items[0].variantId").value(ring.variant().toString()))
                .andExpect(jsonPath("$.items[0].shortfall").value(250.00))
                .andExpect(jsonPath("$.items[1].variantId").value(child.toString()))
                .andExpect(jsonPath("$.items[1].variantTitle", endsWith(" - returned, marked down")))
                .andExpect(jsonPath("$.items[1].floor").value(900.00))
                .andExpect(jsonPath("$.items[1].shortfall").value(100.00))
                .andExpect(jsonPath("$.items[1].discountPerPiece").value(0.00));
    }

    @Test
    @DisplayName("the database holds the rules too: a marked-down variant is whole, one per piece, never its own source; ordinary variants stay one per combination")
    void theDatabaseHoldsTheRules() throws Exception {
        Ring ring = ring();
        UUID child = markedDown(ring);
        UUID piece = jdbc.queryForObject("SELECT markdown_piece_id FROM product_variants WHERE id = ?", UUID.class, child);
        String insert = """
                INSERT INTO product_variants (id, product_id, sku, unit_price, currency, options, option_digest, position, active,
                                              condition, source_variant_id, markdown_piece_id)
                SELECT ?, product_id, ?, 0.00, 'ZMW', options, option_digest, 9, true, ?, ?, ? FROM product_variants WHERE id = ?""";

        // An ordinary variant repeating a combination.
        refused(insert, UUID.randomUUID(), "DB-" + UUID.randomUUID(), "NEW", null, null, ring.variant());
        // Marked down, with no source; with no piece; an ordinary one that names a source.
        refused(insert, UUID.randomUUID(), "DB-" + UUID.randomUUID(), "MARKED_DOWN", null, UUID.randomUUID(), ring.variant());
        refused(insert, UUID.randomUUID(), "DB-" + UUID.randomUUID(), "MARKED_DOWN", ring.variant(), null, ring.variant());
        refused(insert, UUID.randomUUID(), "DB-" + UUID.randomUUID(), "NEW", ring.variant(), UUID.randomUUID(), ring.variant());
        // A second listing for the same piece; a variant that is its own source; a condition nobody knows.
        refused(insert, UUID.randomUUID(), "DB-" + UUID.randomUUID(), "MARKED_DOWN", ring.variant(), piece, ring.variant());
        UUID self = UUID.randomUUID();
        refused(insert, self, "DB-" + UUID.randomUUID(), "MARKED_DOWN", self, UUID.randomUUID(), ring.variant());
        refused(insert, UUID.randomUUID(), "DB-" + UUID.randomUUID(), "USED", ring.variant(), UUID.randomUUID(), ring.variant());

        // And the shape that is allowed goes in.
        assertThat(jdbc.update(insert, UUID.randomUUID(), "DB-" + UUID.randomUUID(), "MARKED_DOWN", ring.variant(), UUID.randomUUID(), ring.variant())).isEqualTo(1);
    }

    private void refused(String sql, Object... args) {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(sql, args))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
}
