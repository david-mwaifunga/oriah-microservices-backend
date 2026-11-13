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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oriah.catalog.PricedCatalogue;
import com.oriah.catalog.TestcontainersConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The admin catalogue list: the one place an operator can see what the public
 * list deliberately hides -- drafts, and packaging, which is never published.
 *
 * <p>It is also the picker Phase 4 needs: a consignment line names an existing
 * variant, and a piece is often created as a draft just before the shipment
 * that brings it in is keyed, so the picker must find drafts and packaging and
 * must find them by SKU as well as by name.
 *
 * <p>Every test makes its own products under a token unique to the test and
 * lists with {@code q=<token>}, so the rows other tests leave in the shared
 * database cannot change what is asserted here.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestChannelBinderConfiguration.class})
@TestPropertySource(properties = {"eureka.client.enabled=false"})
class AdminProductListTest {

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;


    private static final SimpleGrantedAuthority CATALOG_ADMIN =
            new SimpleGrantedAuthority("ROLE_CATALOG_ADMIN");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper mapper;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    private static RequestPostProcessor admin() {
        return jwt().jwt(token -> token.subject("list-admin")).authorities(CATALOG_ADMIN);
    }

    // ------------------------------------------------------------- access

    @Test
    @DisplayName("the admin list needs a token, and a token that holds CATALOG_ADMIN")
    void needsCatalogAdmin() throws Exception {
        mvc.perform(get("/api/v1/admin/products")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/products").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------- what it shows

    @Test
    @DisplayName("it lists drafts and packaging that the public list hides, and the published one too")
    void listsWhatThePublicListHides() throws Exception {
        String token = token();
        String draft = create(token + " draft ring", null);
        String box = create(token + " gift box", "PACKAGING");
        String live = create(token + " live ring", null);
        publishWithPrice(live, "RING-" + token);

        JsonNode page = list("q=" + token);

        assertThat(ids(page)).containsExactlyInAnyOrder(draft, box, live);
        assertThat(page.get("totalElements").asLong()).isEqualTo(3);
        mvc.perform(get("/api/v1/products?size=100"))
                .andExpect(jsonPath("$.content[?(@.id=='" + draft + "')]").isEmpty())
                .andExpect(jsonPath("$.content[?(@.id=='" + box + "')]").isEmpty());
    }

    @Test
    @DisplayName("each row says its kind, status and variants, so it can serve as the consignment picker")
    void rowsCarryWhatAPickerNeeds() throws Exception {
        String token = token();
        String box = create(token + " pouch", "PACKAGING");
        addVariant(box, "POUCH-S-" + token, "0.00");

        JsonNode row = list("q=" + token).get("content").get(0);

        assertThat(row.get("id").asText()).isEqualTo(box);
        assertThat(row.get("kind").asText()).isEqualTo("PACKAGING");
        assertThat(row.get("status").asText()).isEqualTo("DRAFT");
        assertThat(row.get("variants")).hasSize(1);
        assertThat(row.get("variants").get(0).get("id").asText()).isNotBlank();
        assertThat(row.get("variants").get(0).get("sku").asText()).isEqualTo("POUCH-S-" + token);
        assertThat(row.get("variants").get(0).get("active").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("a deleted product is not listed")
    void deletedProductsAreNotListed() throws Exception {
        String token = token();
        String gone = create(token + " gone", null);
        String kept = create(token + " kept", null);
        mvc.perform(delete("/api/v1/admin/products/" + gone).with(admin())).andExpect(status().isNoContent());

        assertThat(ids(list("q=" + token))).containsExactly(kept);
    }

    // -------------------------------------------------------------- filters

    @Test
    @DisplayName("kind filters to packaging or to what is for sale")
    void filtersByKind() throws Exception {
        String token = token();
        String ring = create(token + " ring", null);
        String box = create(token + " box", "PACKAGING");

        assertThat(ids(list("q=" + token + "&kind=PACKAGING"))).containsExactly(box);
        assertThat(ids(list("q=" + token + "&kind=SELLABLE"))).containsExactly(ring);
    }

    @Test
    @DisplayName("status filters to drafts or to what is published")
    void filtersByStatus() throws Exception {
        String token = token();
        String draft = create(token + " draft", null);
        String box = create(token + " box", "PACKAGING");
        String live = create(token + " live", null);
        publishWithPrice(live, "LIVE-" + token);

        assertThat(ids(list("q=" + token + "&status=DRAFT"))).containsExactlyInAnyOrder(draft, box);
        assertThat(ids(list("q=" + token + "&status=ACTIVE"))).containsExactly(live);
        assertThat(ids(list("q=" + token + "&status=ARCHIVED"))).isEmpty();
    }

    @Test
    @DisplayName("q finds a product by part of its title, in any case")
    void searchesTitle() throws Exception {
        String token = token();
        String ring = create("Zircon Halo " + token, null);

        assertThat(ids(list("q=zIrCoN%20halo%20" + token.toUpperCase()))).containsExactly(ring);
        assertThat(ids(list("q=halo%20" + token))).containsExactly(ring);
    }

    @Test
    @DisplayName("q finds a product by a variant's SKU, which is how the consignment picker is used")
    void searchesSku() throws Exception {
        String token = token();
        String ring = create("Plain band " + UUID.randomUUID(), null);
        addVariant(ring, "ZQX-" + token, "0.00");

        assertThat(ids(list("q=zqx-" + token))).containsExactly(ring);
    }

    @Test
    @DisplayName("q is text, not a pattern: a percent sign or an underscore matches only itself")
    void searchTextIsNotAPattern() throws Exception {
        String token = token();
        create(token + " plain", null);
        String literal = create(token + " 100% pure", null);

        // As a LIKE pattern these two would match "<token> plain"; as text they match nothing.
        assertThat(ids(list("q=" + token + "%20p%25n"))).isEmpty();
        assertThat(ids(list("q=" + token + "%20pl_in"))).isEmpty();
        // And a percent sign that really is in a title is found.
        assertThat(ids(list("q=" + token + "%20100%25%20pure"))).containsExactly(literal);
    }

    @Test
    @DisplayName("a kind or a status that does not exist is a 400")
    void unknownFilterValuesAreRefused() throws Exception {
        mvc.perform(listRequest("kind=SERVICE")).andExpect(status().isBadRequest());
        mvc.perform(listRequest("status=PENDING")).andExpect(status().isBadRequest());
    }

    // --------------------------------------------------- order and paging

    @Test
    @DisplayName("newest first, in pages, with the totals a table needs")
    void newestFirstAndPaged() throws Exception {
        String token = token();
        String first = create(token + " one", null);
        Thread.sleep(25);
        String second = create(token + " two", null);
        Thread.sleep(25);
        String third = create(token + " three", null);

        JsonNode page0 = list("q=" + token + "&size=2&page=0");
        JsonNode page1 = list("q=" + token + "&size=2&page=1");

        assertThat(ids(page0)).containsExactly(third, second);
        assertThat(ids(page1)).containsExactly(first);
        assertThat(page0.get("totalElements").asLong()).isEqualTo(3);
        assertThat(page0.get("totalPages").asInt()).isEqualTo(2);
        assertThat(page0.get("size").asInt()).isEqualTo(2);
        assertThat(page1.get("page").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("a page past the end is empty, and still reports how many there are")
    void pagePastTheEndIsEmpty() throws Exception {
        String token = token();
        create(token + " only", null);

        JsonNode page = list("q=" + token + "&page=99&size=20");

        assertThat(page.get("content")).isEmpty();
        assertThat(page.get("totalElements").asLong()).isEqualTo(1);
    }

    @Test
    @DisplayName("a negative page and a zero size are corrected to page 0 and size 1, not an error")
    void badPagingNumbersAreClamped() throws Exception {
        String token = token();
        create(token + " one", null);
        create(token + " two", null);

        JsonNode page = list("q=" + token + "&page=-1&size=0");

        assertThat(page.get("page").asInt()).isZero();
        assertThat(page.get("size").asInt()).isEqualTo(1);
        assertThat(page.get("content")).hasSize(1);
        assertThat(page.get("totalElements").asLong()).isEqualTo(2);
    }

    @Test
    @DisplayName("a page or size that is not a number is a 400")
    void unparseablePagingIsRefused() throws Exception {
        mvc.perform(listRequest("page=abc")).andExpect(status().isBadRequest());
        mvc.perform(listRequest("size=many")).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a blank q filters nothing")
    void blankTextIsIgnored() throws Exception {
        String token = token();
        create(token + " blank", null);

        long all = list("size=1").get("totalElements").asLong();

        assertThat(list("q=%20%20&size=1").get("totalElements").asLong()).isEqualTo(all);
        assertThat(all).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("a q holding a character the database cannot store is a 400, not a 409 about 'existing data'")
    void nulInTextIsRefused() throws Exception {
        mvc.perform(listRequest("q=a%00b")).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a page is at most 100 rows, however many are asked for")
    void pageSizeIsCapped() throws Exception {
        assertThat(list("size=1000").get("size").asInt()).isEqualTo(100);
    }

    // -------------------------------------------------------------- helpers

    private static String token() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private JsonNode list(String query) throws Exception {
        MvcResult result = mvc.perform(listRequest(query)).andExpect(status().isOk()).andReturn();
        return mapper.readTree(result.getResponse().getContentAsString());
    }

    /**
     * A query string as the browser would send it ({@code %20}, {@code %25}),
     * handed to MockMvc as decoded parameters. MockMvc encodes a URL template
     * itself, so putting {@code %25} in one would send the three characters
     * "%25" instead of a percent sign.
     */
    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder listRequest(String query) {
        var request = get("/api/v1/admin/products").with(admin());
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            request.param(java.net.URLDecoder.decode(kv[0], java.nio.charset.StandardCharsets.UTF_8),
                    java.net.URLDecoder.decode(kv.length > 1 ? kv[1] : "", java.nio.charset.StandardCharsets.UTF_8));
        }
        return request;
    }

    private static java.util.List<String> ids(JsonNode page) {
        var ids = new java.util.ArrayList<String>();
        page.get("content").forEach(row -> ids.add(row.get("id").asText()));
        return ids;
    }

    private String create(String title, String kind) throws Exception {
        String body = kind == null
                ? "{\"title\":\"" + title + "\"}"
                : "{\"title\":\"" + title + "\",\"kind\":\"" + kind + "\"}";
        MvcResult created = mvc.perform(post("/api/v1/admin/products").with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return mapper.readTree(created.getResponse().getContentAsString()).get("id").asText();
    }

    /** Adds a variant (unpriced, as every variant now starts) and, if a price is wanted, gives it one directly: this test is about lists, not pricing. */
    private ResultActions addVariant(String productId, String sku, String price) throws Exception {
        ResultActions added = mvc.perform(post("/api/v1/admin/products/" + productId + "/variants").with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"" + sku + "\"}"))
                .andExpect(status().isCreated());
        if (new java.math.BigDecimal(price).signum() > 0) {
            PricedCatalogue.priceLastAdded(jdbc, added.andReturn().getResponse().getContentAsString(), price);
        }
        return added;
    }

    private void publishWithPrice(String productId, String sku) throws Exception {
        addVariant(productId, sku, "1500.00");
        mvc.perform(post("/api/v1/admin/products/" + productId + "/publish").with(admin()))
                .andExpect(status().isOk());
    }
}
