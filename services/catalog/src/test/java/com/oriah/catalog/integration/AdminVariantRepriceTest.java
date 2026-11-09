package com.oriah.catalog.integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oriah.catalog.TestcontainersConfiguration;
import com.oriah.catalog.repository.ProductRepository;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Changing the price of a variant that already exists.
 *
 * <h2>The gap this closes</h2>
 *
 * {@link com.oriah.catalog.model.Product#repriceVariant} and
 * {@link com.oriah.catalog.model.ProductVariant#reprice} were written, pinned
 * by ProductCoherenceTest, and unreachable. The only variant write over HTTP
 * was {@code POST /{id}/variants}, which ADDS one, and
 * {@code ProductUpdateRequest} carries no price -- so a price could be set
 * once, when the piece was created, and never corrected.
 *
 * <p>Nor could it be worked around by adding a second variant: a product with
 * no option axes refuses one as a duplicate, which is every product this shop
 * sells today. An admin who mistyped a price had to delete the piece and make
 * it again.
 *
 * <h2>Why it routes through the PRODUCT</h2>
 *
 * {@code ProductVariant.reprice} is public, so this endpoint could call it
 * directly -- and would then bypass both rules that live on the aggregate,
 * because a variant cannot see its siblings. One currency per product exists
 * so a card never shows two; and a zero price makes a variant unpurchasable
 * just as surely as deactivating it, which must not strand a published piece
 * with nothing to sell.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestChannelBinderConfiguration.class})
@TestPropertySource(properties = {"eureka.client.enabled=false"})
class AdminVariantRepriceTest {

    private static final SimpleGrantedAuthority CATALOG_ADMIN =
            new SimpleGrantedAuthority("ROLE_CATALOG_ADMIN");
    private static final SimpleGrantedAuthority PLAIN_USER =
            new SimpleGrantedAuthority("ROLE_USER");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ProductRepository products;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void clean() {
        products.deleteAll();
    }

    /**
     * Leaves nothing behind either. This class shares its application, and so
     * its database, with the event tests, and SKUs are unique catalogue-wide:
     * a variant left here as AUR-1 would make the golden test's own AUR-1 a
     * 409 if that test ran later.
     */
    @AfterEach
    void cleanUp() {
        products.deleteAll();
    }

    /** A published product with one priced variant: the ordinary case. */
    private String createSellable(String title, String sku, String price, String currency)
            throws Exception {
        MvcResult created = mvc.perform(post("/api/v1/admin/products")
                        .with(jwt().authorities(CATALOG_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\",\"tags\":[]}"))
                .andExpect(status().isCreated())
                .andReturn();
        String location = created.getResponse().getHeader("Location");
        String id = location.substring(location.lastIndexOf('/') + 1);

        String currencyField = currency == null ? "" : ",\"currency\":\"" + currency + "\"";
        mvc.perform(post("/api/v1/admin/products/" + id + "/variants")
                        .with(jwt().authorities(CATALOG_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"" + sku + "\",\"unitPrice\":" + price
                                + currencyField + "}"))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/v1/admin/products/" + id + "/publish")
                        .with(jwt().authorities(CATALOG_ADMIN)))
                .andExpect(status().isOk());
        return id;
    }

    private String firstVariantId(String productId) throws Exception {
        MvcResult res = mvc.perform(get("/api/v1/admin/products/" + productId)
                        .with(jwt().authorities(CATALOG_ADMIN)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString())
                .get("variants").get(0).get("id").asText();
    }

    private ResultActions reprice(String productId, String variantId, String body,
                                  SimpleGrantedAuthority role) throws Exception {
        return mvc.perform(patch("/api/v1/admin/products/" + productId
                        + "/variants/" + variantId)
                .with(jwt().authorities(role))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    // ------------------------------------------------------------- the point

    @Test
    @DisplayName("a reprice reaches the storefront")
    void aRepriceReachesTheStorefront() throws Exception {
        String id = createSellable("Aurelia 18k", "AUR-1", "4500.00", null);
        String variantId = firstVariantId(id);

        reprice(id, variantId, "{\"unitPrice\":4600.00}", CATALOG_ADMIN)
                .andExpect(status().isOk());

        // Asserted on the PUBLIC detail, which is what a shopper reads --
        // not on the admin projection that just answered the write.
        mvc.perform(get("/api/v1/products/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.priceFrom").value(4600.00))
                .andExpect(jsonPath("$.priceTo").value(4600.00))
                .andExpect(jsonPath("$.variants[0].unitPrice").value(4600.00));
    }

    @Test
    @DisplayName("a reprice keeps the variant's own currency when none is given")
    void aRepriceKeepsTheExistingCurrency() throws Exception {
        // The trap: ProductVariant.normaliseCurrency turns a null into ZMW, so
        // a PATCH omitting the field would silently redenominate a dollar
        // piece to Kwacha at the same number. Eleven of the live catalogue's
        // products are priced in USD. A PATCH that omits a field leaves it be.
        String id = createSellable("Dollar piece", "USD-1", "100.00", "USD");
        String variantId = firstVariantId(id);

        reprice(id, variantId, "{\"unitPrice\":120.00}", CATALOG_ADMIN)
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/products/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.priceFrom").value(120.00));
    }

    // -------------------------------------------------------------- refusals

    @Test
    @DisplayName("an unknown variant is 404, not 400")
    void anUnknownVariantIs404() throws Exception {
        // Product.requireVariant throws IllegalArgumentException, which the
        // shared advice maps to 400. "Your request was malformed" is the wrong
        // thing to tell someone who addressed a variant that is not there, so
        // the endpoint answers this one itself.
        String id = createSellable("Aurelia 18k", "AUR-2", "4500.00", null);

        reprice(id, "11111111-2222-3333-4444-555555555555",
                "{\"unitPrice\":10.00}", CATALOG_ADMIN)
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("an unknown product is 404")
    void anUnknownProductIs404() throws Exception {
        reprice("99999999-8888-7777-6666-555555555555",
                "11111111-2222-3333-4444-555555555555",
                "{\"unitPrice\":10.00}", CATALOG_ADMIN)
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("repricing the last purchasable variant to zero is refused")
    void aZeroOnTheLastPurchasableVariantIsRefused() throws Exception {
        // Zero is the "nobody has priced this yet" marker and the storefront
        // list filters on unit_price > 0, so this would drop a published piece
        // out of the grid while it still answered on its own URL. The HTTP
        // twin of the rule ProductCoherenceTest pins on the model.
        String id = createSellable("Aurelia 18k", "AUR-3", "4500.00", null);
        String variantId = firstVariantId(id);

        reprice(id, variantId, "{\"unitPrice\":0.00}", CATALOG_ADMIN)
                .andExpect(status().isConflict());

        mvc.perform(get("/api/v1/products/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.priceFrom").value(4500.00));
    }

    @Test
    @DisplayName("a negative price is refused")
    void aNegativePriceIsRefused() throws Exception {
        String id = createSellable("Aurelia 18k", "AUR-4", "4500.00", null);
        String variantId = firstVariantId(id);

        reprice(id, variantId, "{\"unitPrice\":-1.00}", CATALOG_ADMIN)
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a missing price is refused rather than read as zero")
    void aMissingPriceIsRefused() throws Exception {
        String id = createSellable("Aurelia 18k", "AUR-5", "4500.00", null);
        String variantId = firstVariantId(id);

        reprice(id, variantId, "{}", CATALOG_ADMIN)
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("repricing needs CATALOG_ADMIN")
    void repricingNeedsTheAdminRole() throws Exception {
        String id = createSellable("Aurelia 18k", "AUR-6", "4500.00", null);
        String variantId = firstVariantId(id);

        reprice(id, variantId, "{\"unitPrice\":1.00}", PLAIN_USER)
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/v1/products/" + id))
                .andExpect(jsonPath("$.priceFrom").value(4500.00));
    }

    @Test
    @DisplayName("an absurd price is refused at the admin screen")
    void anAbsurdPriceIsRefused() throws Exception {
        // A mistyped price is accepted by the column, whose only rule is
        // unit_price >= 0, and surfaces much later as a 422 from order's total
        // ceiling at a shopper's checkout -- where neither they nor the admin
        // can connect it to the typo. Refuse it at the door instead.
        String id = createSellable("Aurelia 18k", "AUR-7", "4500.00", null);
        String variantId = firstVariantId(id);

        reprice(id, variantId, "{\"unitPrice\":99999999999.00}", CATALOG_ADMIN)
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a reprice changes the variant in place rather than minting a new one")
    void aRepriceChangesTheVariantInPlace() throws Exception {
        // Catalog cannot assert anything about orders, but it can assert the
        // half it owns: nothing that recorded this variant's id is left
        // pointing at a different row, which is what makes an order's own
        // stored price the only thing that decides what was charged.
        String id = createSellable("Aurelia 18k", "AUR-8", "4500.00", null);
        String variantId = firstVariantId(id);

        reprice(id, variantId, "{\"unitPrice\":4600.00}", CATALOG_ADMIN)
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/admin/products/" + id)
                        .with(jwt().authorities(CATALOG_ADMIN)))
                .andExpect(jsonPath("$.variants.length()").value(1))
                .andExpect(jsonPath("$.variants[0].id").value(variantId));
    }
}
