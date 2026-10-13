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

import com.oriah.cart.TestcontainersConfiguration;
import com.oriah.cart.client.CatalogPricingClient;
import com.oriah.cart.repository.CartRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"eureka.client.enabled=false"})
class CartIntegrationTest {

    private static final SimpleGrantedAuthority ROLE_USER = new SimpleGrantedAuthority("ROLE_USER");

    @Autowired
    private MockMvc mvc;
    @Autowired
    private CartRepository repository;
    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    /**
     * Cart now resolves every price from catalog, so these tests have to say
     * what catalog would answer. The price moved from the request body to this
     * stub, which is the entire behaviour change: the arithmetic the
     * assertions below check is unchanged, but the authority for it is not.
     */
    @MockitoBean
    private CatalogPricingClient catalog;

    /** variantId -> what catalog says it costs. Programmed by {@link #addItem}. */
    private final Map<UUID, BigDecimal> catalogPrices = new HashMap<>();
    private final Map<UUID, String> catalogTitles = new HashMap<>();

    private static final UUID V1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID V2 = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @BeforeEach
    void clean() {
        repository.deleteAll();
        catalogPrices.clear();
        catalogTitles.clear();
        // Answers from the map rather than with a fixed entry, so a test can
        // price two variants differently and so an id nobody registered comes
        // back UNKNOWN -- which is what catalog really does for a variant that
        // does not exist, and what the refusal paths need.
        when(catalog.price(any())).thenAnswer(invocation -> {
            CatalogPricingClient.PricingRequest request = invocation.getArgument(0);
            List<CatalogPricingClient.Entry> entries = request.variantIds().stream()
                    .map(id -> {
                        BigDecimal price = catalogPrices.get(id);
                        if (price == null) {
                            return new CatalogPricingClient.Entry(
                                    id, "UNKNOWN", null, null, null, null, null);
                        }
                        return new CatalogPricingClient.Entry(
                                id, "PRICED", UUID.randomUUID(),
                                catalogTitles.get(id),
                                catalogTitles.get(id), price, "ZMW");
                    })
                    .toList();
            return new CatalogPricingClient.PricingResponse(entries);
        });
    }

    @Test
    @DisplayName("GET on empty cart returns subtotal=0 and no items")
    void emptyCart() throws Exception {
        mvc.perform(get("/api/v1/carts/me").with(jwtForUser("alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.subtotal").value(0));
    }

    @Test
    @DisplayName("add item, adding same variant again bumps quantity")
    void addBumpsQuantity() throws Exception {
        addItem("alice", V1, 1, "100.00", "Aurelia");
        addItem("alice", V1, 2, "100.00", "Aurelia");

        mvc.perform(get("/api/v1/carts/me").with(jwtForUser("alice")))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].quantity").value(3))
                .andExpect(jsonPath("$.subtotal").value(300.00));
    }

    @Test
    @DisplayName("PATCH quantity overwrites; DELETE item removes")
    void patchAndRemove() throws Exception {
        addItem("bob", V1, 2, "50.00", "Lume");

        mvc.perform(patch("/api/v1/carts/me/items/" + V1).with(jwtForUser("bob"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].quantity").value(5))
                .andExpect(jsonPath("$.subtotal").value(250.00));

        mvc.perform(delete("/api/v1/carts/me/items/" + V1).with(jwtForUser("bob")))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/carts/me").with(jwtForUser("bob")))
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    @DisplayName("DELETE on whole cart clears all items")
    void clearAll() throws Exception {
        addItem("carol", V1, 1, "10.00", "A");
        addItem("carol", V2, 2, "20.00", "B");
        mvc.perform(delete("/api/v1/carts/me").with(jwtForUser("carol")))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/carts/me").with(jwtForUser("carol")))
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    @DisplayName("each user sees only their own cart (sub isolation)")
    void perSubIsolation() throws Exception {
        addItem("alice", V1, 1, "100.00", "Aurelia");
        addItem("bob", V2, 3, "20.00", "Lume");

        mvc.perform(get("/api/v1/carts/me").with(jwtForUser("alice")))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].variantId").value(V1.toString()));

        mvc.perform(get("/api/v1/carts/me").with(jwtForUser("bob")))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].variantId").value(V2.toString()));
    }

    @Test
    @DisplayName("/me without JWT is 401")
    void requiresJwt() throws Exception {
        mvc.perform(get("/api/v1/carts/me")).andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------
    // Not-found paths. Every one of these answered with an EMPTY BODY before
    // Tier 5, and the first one answered 200.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("PATCH a variant that is not in the cart is 404 - it used to answer 200")
    void patchUnknownVariantIsNotFound() throws Exception {
        // THE BUG. CartService.updateItemQuantity read:
        //
        //     return repository.findByKeycloakId(keycloakId).map(cart -> {
        //         Optional<CartItem> updated = cart.updateQuantity(variantId, qty);
        //         if (updated.isPresent()) { log.info(...); }
        //         return cart;                 // <-- regardless
        //     });
        //
        // The empty Optional from Cart.updateQuantity was used ONLY to gate a
        // log line, then the cart was returned anyway. So the controller saw a
        // present Optional<Cart> and answered 200 with the UNCHANGED cart: the
        // caller was told the update succeeded when nothing had changed.
        //
        // Invisible from the storefront, which is why it survived -- the UI
        // re-renders from the returned cart, so a quantity edit against a
        // variant that had just been removed in another tab silently snapped
        // back to the old number with no error. No test covered this path.
        addItem("dave", V1, 1, "100.00", "Aurelia");

        mvc.perform(patch("/api/v1/carts/me/items/" + V2).with(jwtForUser("dave"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":5}"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value(
                        "CartItem not found with the given input data variantId : '" + V2 + "'"));
    }

    @Test
    @DisplayName("PATCH is 404 with a body when the caller has no cart at all")
    void patchWithNoCartIsNotFound() throws Exception {
        // Distinguished from the case above by the DETAIL, not the status:
        // "you have no cart" and "that item is not in your cart" are different
        // conditions and a client fixing its state needs to know which.
        mvc.perform(patch("/api/v1/carts/me/items/" + V1).with(jwtForUser("erin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":2}"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value(
                        "Cart not found with the given input data customer : 'me'"));
    }

    @Test
    @DisplayName("DELETE a variant that is not in the cart is 404 with a body")
    void deleteUnknownVariantIsNotFound() throws Exception {
        addItem("frank", V1, 1, "100.00", "Aurelia");

        mvc.perform(delete("/api/v1/carts/me/items/" + V2).with(jwtForUser("frank")))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value(
                        "CartItem not found with the given input data variantId : '" + V2 + "'"));
    }

    @Test
    @DisplayName("DELETE the whole cart when there is none is 404 with a body")
    void clearWithNoCartIsNotFound() throws Exception {
        // smoke-test.ps1 deliberately tolerates this 404 -- "already empty" is
        // the desired end state after a successful confirm. The status is
        // unchanged here; only the body appears, so that tolerance still holds.
        mvc.perform(delete("/api/v1/carts/me").with(jwtForUser("grace")))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value(
                        "Cart not found with the given input data customer : 'me'"));
    }

    /**
     * Adds a line. The {@code price} and {@code title} arguments now program
     * what CATALOG answers for that variant rather than being sent in the
     * request -- the request body carries only the variant and the quantity,
     * because those are the only two things a shopper is allowed to choose.
     */
    private void addItem(String sub, UUID variantId, int qty, String price, String title) throws Exception {
        catalogPrices.put(variantId, new BigDecimal(price));
        catalogTitles.put(variantId, title);
        String body = """
                {"variantId":"%s","quantity":%d}
                """.formatted(variantId, qty);
        mvc.perform(post("/api/v1/carts/me/items").with(jwtForUser(sub))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor jwtForUser(String sub) {
        return jwt().authorities(ROLE_USER).jwt(j -> j.subject(sub));
    }

    @Test
    @DisplayName("a body still sending unitPrice gets the CATALOG price, not the one it sent")
    void aStaleClientGetsTheCatalogPrice() throws Exception {
        // NOT the proof that the lookup works -- see AddItemRequest's javadoc.
        // Cart ignores unknown properties, so this would pass the moment the
        // field left the record even if no lookup existed.
        // AddItemPricingTest is what pins the lookup; this pins the end-to-end
        // behaviour a stale storefront actually gets, which is worth knowing
        // because it is SILENT: no 400, no warning, just the right price.
        catalogPrices.put(V1, new BigDecimal("4500.00"));
        catalogTitles.put(V1, "Aurelia 18k - Ring Size M");

        mvc.perform(post("/api/v1/carts/me/items").with(jwtForUser("alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"variantId":"%s","quantity":2,
                                 "unitPrice":0.01,"title":"Free ring please"}
                                """.formatted(V1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subtotal").value(9000.00))
                .andExpect(jsonPath("$.items[0].unitPrice").value(4500.00))
                .andExpect(jsonPath("$.items[0].title").value("Aurelia 18k - Ring Size M"));
    }

    @Test
    @DisplayName("a quantity above the cap is a 400 with fieldErrors, not a 409 or a 500")
    void aQuantityAboveTheCapIsRejectedByValidation() throws Exception {
        catalogPrices.put(V1, new BigDecimal("100.00"));
        catalogTitles.put(V1, "Aurelia");

        mvc.perform(post("/api/v1/carts/me/items").with(jwtForUser("alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"variantId":"%s","quantity":100000}
                                """.formatted(V1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors").exists());

        // And the same on the PATCH path, or the cap is one request away from
        // being useless.
        addItem("alice", V1, 1, "100.00", "Aurelia");
        mvc.perform(patch("/api/v1/carts/me/items/" + V1).with(jwtForUser("alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":100000}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors").exists());
    }

    @Test
    @DisplayName("an unreachable catalog is a 503 and leaves the bag exactly as it was")
    void anUnreachableCatalogLeavesTheBagUntouched() throws Exception {
        addItem("alice", V1, 1, "100.00", "Aurelia");
        String before = mvc.perform(get("/api/v1/carts/me").with(jwtForUser("alice")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // doThrow, not when(...).thenThrow: price() is already stubbed with an
        // Answer, and when(mock.price(any())) would CALL it -- with a null
        // argument -- while setting up the new stub.
        doThrow(new IllegalStateException("catalog is down")).when(catalog).price(any());

        mvc.perform(post("/api/v1/carts/me/items").with(jwtForUser("alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"variantId":"%s","quantity":1}
                                """.formatted(V2)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        // The bag must be byte-identical: a failed add that half-wrote a cart
        // row would show up here.
        assertThat(mvc.perform(get("/api/v1/carts/me").with(jwtForUser("alice")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString())
                .isEqualTo(before);
    }

    @Test
    @DisplayName("an empty cart omits updatedAt rather than serializing it as null")
    void emptyCartDoesNotEmitANullUpdatedAt() throws Exception {
        // A user with no cart row gets an UNSAVED placeholder from
        // CartService.getOrEmpty, so JPA auditing never ran and updatedAt is
        // null on the entity. Jackson serializes that as an explicit
        // "updatedAt": null -- which the published OpenAPI contract does not
        // allow: CartResponse.updatedAt is declared a non-nullable,
        // non-required string.
        //
        // That mismatch is not academic. oriah-web parses this response with
        // Zod, whose .optional() accepts undefined and REJECTS null, so a
        // shopper who had never added anything to their bag got
        //   "We couldn't load your bag."
        //   {"expected":"string","code":"invalid_type","path":["updatedAt"]}
        // instead of an empty bag. Omitting the field is what the contract
        // already promises, and it is what "this cart has never been saved"
        // actually means.
        mvc.perform(get("/api/v1/carts/me")
                        .with(jwt().authorities(ROLE_USER)
                                .jwt(j -> j.subject(UUID.randomUUID().toString()))))
                .andExpect(status().isOk())
                // Asserted on the RAW BODY on purpose. jsonPath(...)
                // .doesNotExist() treats an explicit JSON null as absent, so
                // it passes against the very payload this test exists to
                // reject -- a gate that cannot fail. The string check is ugly
                // and it actually discriminates.
                .andExpect(result -> org.assertj.core.api.Assertions
                        .assertThat(result.getResponse().getContentAsString())
                        .as("an empty cart must omit updatedAt, not send it as null")
                        .doesNotContain("updatedAt"));
    }

    @Test
    @DisplayName("a saved cart still reports its updatedAt")
    void savedCartStillReportsUpdatedAt() throws Exception {
        // The fix must omit only what is absent. Suppressing the field
        // unconditionally would be the same bug wearing a different hat:
        // clients would stop seeing a timestamp that genuinely exists.
        String sub = UUID.randomUUID().toString();
        // This test writes its own request body rather than using the helper,
        // so it has to program the catalog stub itself.
        catalogPrices.put(V1, new BigDecimal("100.00"));
        catalogTitles.put(V1, "Aurelia 18k");
        mvc.perform(post("/api/v1/carts/me/items")
                        .with(jwt().authorities(ROLE_USER)
                                .jwt(j -> j.subject(sub)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"variantId":"11111111-1111-1111-1111-111111111111",
                                 "quantity":1}
                                """))
                .andExpect(status().is2xxSuccessful());

        mvc.perform(get("/api/v1/carts/me")
                        .with(jwt().authorities(ROLE_USER)
                                .jwt(j -> j.subject(sub))))
                .andExpect(status().isOk())
                .andExpect(result -> org.assertj.core.api.Assertions
                        .assertThat(result.getResponse().getContentAsString())
                        .as("a persisted cart still carries its timestamp")
                        .contains("updatedAt"));
    }
}
