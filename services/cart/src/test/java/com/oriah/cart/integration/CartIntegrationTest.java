package com.oriah.cart.integration;

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
import com.oriah.cart.repository.CartRepository;

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

    private static final UUID V1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID V2 = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @BeforeEach
    void clean() {
        repository.deleteAll();
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

    private void addItem(String sub, UUID variantId, int qty, String price, String title) throws Exception {
        String body = """
                {"variantId":"%s","quantity":%d,"unitPrice":%s,"title":"%s"}
                """.formatted(variantId, qty, price, title);
        mvc.perform(post("/api/v1/carts/me/items").with(jwtForUser(sub))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor jwtForUser(String sub) {
        return jwt().authorities(ROLE_USER).jwt(j -> j.subject(sub));
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
        mvc.perform(post("/api/v1/carts/me/items")
                        .with(jwt().authorities(ROLE_USER)
                                .jwt(j -> j.subject(sub)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"variantId":"11111111-1111-1111-1111-111111111111",
                                 "quantity":1,"unitPrice":100.00,"title":"Aurelia 18k"}
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
