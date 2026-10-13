package com.oriah.cart.integration;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.oriah.cart.TestcontainersConfiguration;
import com.oriah.cart.client.CatalogPricingClient;
import com.oriah.cart.repository.CartRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What the bag tells the storefront, and what it does when catalog is down.
 *
 * <h2>One read model, built the same way for all three endpoints</h2>
 *
 * GET, POST /items and PATCH /items/{id} all return a cart, so all three price
 * every line and answer identically. Giving POST a single-variant lookup and
 * leaving PATCH with none -- the obvious reading of the plan -- has a specific
 * and nasty consequence: a shopper changing a quantity would get a response in
 * which every line is UNKNOWN and {@code checkoutEnabled} is false, so the bag
 * drawer would disable checkout the moment anyone edited a quantity. A test
 * here pins the PATCH case for that reason.
 *
 * <h2>The money shown must be the money charged</h2>
 *
 * The stored {@code unit_price} is a snapshot. The response reports catalog's
 * CURRENT price and computes the subtotal from the lines it is showing, because
 * a bag whose lines do not add up to its own total is worse than either number
 * alone, and because checkout reprices at confirm -- so a bag showing anything
 * else is showing a total nobody will be charged.
 *
 * <h2>checkoutEnabled is a primitive, and that is load-bearing</h2>
 *
 * {@code CartResponse} is {@code @JsonInclude(NON_NULL)} for a documented
 * reason ({@code updatedAt} on a never-saved cart). A {@code Boolean} field
 * would therefore be OMITTED whenever it was null, and oriah-web's Zod
 * {@code .optional()} accepts {@code undefined} -- so a degraded,
 * checkout-blocked bag would read to the browser as checkout-ENABLED. The
 * primitive cannot be null, and
 * {@link #theBlockIsAlwaysPresentInTheBody} asserts on the raw body so that a
 * later change back to a wrapper type fails here.
 *
 * <h2>Both directions are asserted</h2>
 *
 * There are positive cases ({@code PRICED}, {@code checkoutEnabled: true}) as
 * well as negative ones. With only the negatives, a hardcoded
 * {@code checkoutEnabled = false} would pass every test in the file while
 * blocking checkout for every shopper in the platform.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false"
})
class CartReadModelTest {

    private static final SimpleGrantedAuthority ROLE_USER = new SimpleGrantedAuthority("ROLE_USER");
    private static final UUID RING = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CUFF = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Autowired
    private MockMvc mvc;
    @Autowired
    private CartRepository repository;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private CatalogPricingClient catalog;

    /** variantId -> {status, price, currency} as catalog would answer. */
    private final Map<UUID, CatalogPricingClient.Entry> answers = new HashMap<>();

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        answers.clear();
        when(catalog.price(any())).thenAnswer(invocation -> {
            CatalogPricingClient.PricingRequest request = invocation.getArgument(0);
            List<CatalogPricingClient.Entry> results = request.variantIds().stream()
                    .map(id -> answers.getOrDefault(id, new CatalogPricingClient.Entry(
                            id, "UNKNOWN", null, null, null, null, null)))
                    .toList();
            return new CatalogPricingClient.PricingResponse(results);
        });
    }

    private static RequestPostProcessor as(String subject) {
        return jwt().authorities(ROLE_USER).jwt(j -> j.subject(subject));
    }

    private void priced(UUID variantId, String price, String currency, String title) {
        answers.put(variantId, new CatalogPricingClient.Entry(
                variantId, "PRICED", UUID.randomUUID(), title, title,
                new BigDecimal(price), currency));
    }

    private void unpublished(UUID variantId) {
        answers.put(variantId, new CatalogPricingClient.Entry(
                variantId, "UNPUBLISHED", UUID.randomUUID(), "Withdrawn", null, null, null));
    }

    private void add(String subject, UUID variantId, int quantity) throws Exception {
        mvc.perform(post("/api/v1/carts/me/items").with(as(subject))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"variantId\":\"" + variantId + "\",\"quantity\":" + quantity + "}"))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------- positive cases

    @Test
    @DisplayName("a fully priced bag reports PRICED lines and enables checkout")
    void aPricedBagEnablesCheckout() throws Exception {
        priced(RING, "4500.00", "ZMW", "Aurelia 18k - Ring Size M");
        add("alice", RING, 2);

        mvc.perform(get("/api/v1/carts/me").with(as("alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checkoutEnabled").value(true))
                .andExpect(jsonPath("$.currency").value("ZMW"))
                .andExpect(jsonPath("$.subtotal").value(9000.00))
                .andExpect(jsonPath("$.items[0].pricingStatus").value("PRICED"))
                .andExpect(jsonPath("$.items[0].unitPrice").value(4500.00))
                .andExpect(jsonPath("$.items[0].lineTotal").value(9000.00))
                .andExpect(jsonPath("$.items[0].currency").value("ZMW"));
    }

    @Test
    @DisplayName("editing a quantity still returns a priced, checkout-enabled bag")
    void aQuantityEditStillReturnsAPricedBag() throws Exception {
        priced(RING, "100.00", "ZMW", "Aurelia");
        add("bob", RING, 1);

        // THE PATCH TRAP. With no pricing on this path the response would carry
        // UNKNOWN lines and checkoutEnabled:false, so the drawer would disable
        // checkout as soon as a shopper touched a quantity stepper.
        mvc.perform(patch("/api/v1/carts/me/items/" + RING).with(as("bob"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checkoutEnabled").value(true))
                .andExpect(jsonPath("$.items[0].pricingStatus").value("PRICED"))
                .andExpect(jsonPath("$.subtotal").value(300.00));
    }

    @Test
    @DisplayName("the response shows catalog's CURRENT price, and the subtotal matches the lines shown")
    void theSubtotalMatchesTheLinesItShows() throws Exception {
        priced(RING, "100.00", "ZMW", "Aurelia");
        add("carol", RING, 2);

        // The variant is repriced after it was added. The stored snapshot still
        // says 100.00; the bag must show 120.00, because that is what checkout
        // will charge when it reprices at confirm.
        priced(RING, "120.00", "ZMW", "Aurelia");

        mvc.perform(get("/api/v1/carts/me").with(as("carol")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].unitPrice").value(120.00))
                .andExpect(jsonPath("$.items[0].lineTotal").value(240.00))
                .andExpect(jsonPath("$.subtotal").value(240.00));
    }

    // ------------------------------------------------------- negative cases

    @Test
    @DisplayName("an unpublished line is flagged, excluded from the subtotal, and blocks checkout")
    void anUnpublishedLineDisablesCheckout() throws Exception {
        priced(RING, "100.00", "ZMW", "Aurelia");
        priced(CUFF, "250.00", "ZMW", "Lume cuff");
        add("dave", RING, 1);
        add("dave", CUFF, 1);

        // Withdrawn from sale while it sat in the bag.
        unpublished(CUFF);

        mvc.perform(get("/api/v1/carts/me").with(as("dave")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checkoutEnabled").value(false))
                // 100.00, not 350.00: a line that cannot be sold must not be
                // counted in a total the shopper is being shown.
                .andExpect(jsonPath("$.subtotal").value(100.00))
                .andExpect(jsonPath("$.items.length()").value(2));
    }

    @Test
    @DisplayName("catalog being down returns the bag with its stored prices and blocks checkout")
    void catalogDownStillReturnsTheBag() throws Exception {
        priced(RING, "100.00", "ZMW", "Aurelia");
        add("erin", RING, 2);

        doThrow(new IllegalStateException("catalog is down")).when(catalog).price(any());

        // A bag that 503s is useless to a shopper, so the read degrades: stored
        // snapshots, every line UNKNOWN, and checkout blocked because nothing
        // here can be trusted as a price to charge.
        mvc.perform(get("/api/v1/carts/me").with(as("erin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checkoutEnabled").value(false))
                .andExpect(jsonPath("$.items[0].pricingStatus").value("UNKNOWN"))
                .andExpect(jsonPath("$.items[0].unitPrice").value(100.00))
                .andExpect(jsonPath("$.subtotal").value(200.00));
    }

    @Test
    @DisplayName("checkoutEnabled is always present in the body, even when false")
    void theBlockIsAlwaysPresentInTheBody() throws Exception {
        priced(RING, "100.00", "ZMW", "Aurelia");
        add("frank", RING, 1);
        unpublished(RING);

        // Asserted on the RAW body. CartResponse is @JsonInclude(NON_NULL), so
        // a Boolean field would vanish from the payload whenever it was null,
        // and Zod's .optional() accepts undefined -- a blocked bag would read
        // as enabled. jsonPath would not catch the omission the same way.
        mvc.perform(get("/api/v1/carts/me").with(as("frank")))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .as("a primitive boolean cannot be omitted; a Boolean can")
                        .contains("\"checkoutEnabled\":false"));
    }

    @Test
    @DisplayName("a bag mixing currencies blocks checkout rather than reporting one of them")
    void aMixedCurrencyBagBlocksCheckout() throws Exception {
        // Reachable: catalog enforces one currency per PRODUCT, not across
        // products, so two variants of different products can disagree.
        // Reporting a single currency for such a bag would be a lie, and
        // letting it reach checkout would add two different units together.
        priced(RING, "4500.00", "ZMW", "Aurelia");
        priced(CUFF, "250.00", "USD", "Lume cuff");
        add("grace", RING, 1);
        add("grace", CUFF, 1);

        mvc.perform(get("/api/v1/carts/me").with(as("grace")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checkoutEnabled").value(false));
    }

    @Test
    @DisplayName("the response carries no field nobody declared")
    void theBodyHasNoAccidentalFields() throws Exception {
        priced(RING, "100.00", "ZMW", "Aurelia");
        add("ivy", RING, 1);

        // isPurchasable() is an internal helper, but Jackson treats an isX()
        // method on a record as a derived property and serialized it -- the
        // payload carried an undeclared "purchasable" field duplicating
        // pricingStatus. Found by capturing a real response from the running
        // stack; every assertion in this class names specific fields, so none
        // of them objected to an extra one.
        mvc.perform(get("/api/v1/carts/me").with(as("ivy")))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .as("an accidental derived property is an accidental public contract")
                        .doesNotContain("purchasable"));
    }

    // ------------------------------------------------------------ the empty bag

    @Test
    @DisplayName("an empty bag needs no pricing call at all")
    void anEmptyBagNeedsNoPricingCall() throws Exception {
        mvc.perform(get("/api/v1/carts/me").with(as("harry")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.subtotal").value(0))
                // Nothing to buy, so nothing to block: false here would make
                // the storefront disable a button it should simply not show.
                .andExpect(jsonPath("$.checkoutEnabled").value(false));

        // A shopper with no cart row gets an unsaved placeholder. Pricing an
        // empty list would be a pointless round trip on the most-hit endpoint
        // in the service, and catalog rejects an empty variantIds list anyway.
        verifyNoInteractions(catalog);
    }
}
