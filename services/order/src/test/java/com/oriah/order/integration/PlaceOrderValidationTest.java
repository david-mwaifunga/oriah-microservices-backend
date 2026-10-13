package com.oriah.order.integration;

import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.oriah.order.TestcontainersConfiguration;
import com.oriah.order.model.Order;
import com.oriah.order.repository.OrderRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What {@code POST /api/v1/orders} accepts, and what it must refuse.
 *
 * <h2>The database was the only line of defence, and it answers in 500s</h2>
 *
 * {@code V1__create_orders.sql} carries {@code CHECK (quantity > 0)} and
 * {@code title VARCHAR(255)}, and {@code PlaceOrderRequest} carried no bounds
 * at all beyond {@code @NotNull}. So every malformed line reached Postgres,
 * violated a constraint or overflowed a column, and came back as
 *
 * <pre>500 An unexpected error occurred. Use the traceId to correlate...</pre>
 *
 * Verified against the running stack: {@code quantity: -5}, {@code quantity: 0},
 * {@code quantity: 2000000000} and a 5,000-character title were all 500s.
 *
 * <p>That is wrong twice over. The caller is told nothing it can act on, and
 * an ordinary bad request is recorded as the server fault an operator pages
 * on. Bounds belong in the DTO, where they produce a 400 with fieldErrors; the
 * CHECK constraint then goes back to being a backstop that never fires, and a
 * 500 from it becomes the honest answer that validation has a hole.
 *
 * <h2>The caps match cart's, deliberately</h2>
 *
 * {@code Cart.MAX_LINES} is 100 and {@code Cart.MAX_QUANTITY_PER_LINE} is 99,
 * and cart's own comment explains why the line cap is 100: catalog's pricing
 * endpoint enforces {@code @Size(max = 100)} on a batch, so a bag that cannot
 * be priced in one request must not be constructible. An order is the record
 * of a bag, so an order that could not have been a bag should not be
 * constructible either -- and once order prices from catalog itself, the same
 * batch limit applies to it directly.
 *
 * <h2>And the last hardcoded USD in the platform</h2>
 *
 * {@code Order}'s constructor read
 *
 * <pre>this.currency = currency == null || currency.isBlank() ? "USD" : currency;</pre>
 *
 * on a platform whose prices are in Kwacha. cart and catalog both hold
 * {@code DEFAULT_CURRENCY = "ZMW"}; shipping's is configuration; payment's
 * identical default was removed in the same pass. This was the last one in
 * live code.
 *
 * <p>It is replaced with a refusal rather than with ZMW. An order is the
 * record of a sale, so its currency should be the one the sale was priced in
 * -- and a default, even the right one, produces an order whose currency
 * nobody chose. Verified live before the fix: a request omitting currency
 * returned 201 with {@code "currency": "USD"}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestChannelBinderConfiguration.class})
@TestPropertySource(properties = {
        "eureka.client.enabled=false",
        "spring.cloud.stream.bindings.orderEvents-out-0.destination=oriah.order"
})
class PlaceOrderValidationTest {

    private static final SimpleGrantedAuthority ROLE_USER =
            new SimpleGrantedAuthority("ROLE_USER");

    private static final String ADDRESS = "11111111-2222-3333-4444-555555555555";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private OrderRepository repository;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void reset() {
        repository.deleteAll();
    }

    private static RequestPostProcessor alice() {
        return jwt().authorities(ROLE_USER).jwt(j -> j.subject("kc-alice"));
    }

    /** One line, with whatever quantity the caller asked for. */
    private static String bodyWithQuantity(Object quantity) {
        return "{\"shippingAddressId\":\"" + ADDRESS + "\",\"currency\":\"ZMW\","
                + "\"items\":[{\"variantId\":\"" + UUID.randomUUID() + "\","
                + "\"title\":\"Aurelia 18k\",\"quantity\":" + quantity + ","
                + "\"unitPrice\":100.00}]}";
    }

    private void refused(String body, String field) throws Exception {
        long before = repository.count();
        mvc.perform(post("/api/v1/orders")
                        .with(alice())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field").value(
                        org.hamcrest.Matchers.hasItem(
                                org.hamcrest.Matchers.containsString(field))));
        // A 400 that still wrote a row would be the worst of both answers.
        assertThat(repository.count())
                .as("a refused order must not be persisted")
                .isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, -5})
    @DisplayName("a non-positive quantity is a 400, not a 500 from CHECK (quantity > 0)")
    void nonPositiveQuantityIsRefused(int quantity) throws Exception {
        refused(bodyWithQuantity(quantity), "quantity");
    }

    @Test
    @DisplayName("a quantity beyond the per-line cap is a 400, not a numeric overflow")
    void absurdQuantityIsRefused() throws Exception {
        // 2,000,000,000 x 100.00 overflows NUMERIC(12,2) when the line total is
        // computed, which was a 500. The cap makes it unreachable.
        refused(bodyWithQuantity(2_000_000_000), "quantity");
    }

    @Test
    @DisplayName("the per-line cap is inclusive at 99, matching cart")
    void theQuantityCapIsInclusive() throws Exception {
        // The boundary itself, so the cap is pinned to cart's constant rather
        // than to a number someone liked.
        mvc.perform(post("/api/v1/orders")
                        .with(alice())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyWithQuantity(99)))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("more lines than catalog can price in one batch is a 400")
    void tooManyLinesIsRefused() throws Exception {
        // 101 lines. catalog's pricing endpoint takes at most 100 ids, which is
        // why cart caps a bag at 100 -- an order that could not have been a bag
        // should not be constructible either.
        String lines = IntStream.range(0, 101)
                .mapToObj(i -> "{\"variantId\":\"" + UUID.randomUUID() + "\","
                        + "\"title\":\"L" + i + "\",\"quantity\":1,\"unitPrice\":1.00}")
                .collect(Collectors.joining(","));
        refused("{\"shippingAddressId\":\"" + ADDRESS + "\",\"currency\":\"ZMW\","
                + "\"items\":[" + lines + "]}", "items");
    }

    @Test
    @DisplayName("a title longer than the column is a 400, not a 500 from the column")
    void overlongTitleIsRefused() throws Exception {
        // VARCHAR(255). cart hit this same wall and removed the field; order
        // still carries it, so until it does the same the bound has to be here.
        String title = "A".repeat(256);
        refused("{\"shippingAddressId\":\"" + ADDRESS + "\",\"currency\":\"ZMW\","
                + "\"items\":[{\"variantId\":\"" + UUID.randomUUID() + "\","
                + "\"title\":\"" + title + "\",\"quantity\":1,\"unitPrice\":100.00}]}",
                "title");
    }

    @Test
    @DisplayName("a negative unit price is a 400, not a 500 from CHECK (unit_price >= 0)")
    void negativeUnitPriceIsRefused() throws Exception {
        refused("{\"shippingAddressId\":\"" + ADDRESS + "\",\"currency\":\"ZMW\","
                + "\"items\":[{\"variantId\":\"" + UUID.randomUUID() + "\","
                + "\"title\":\"t\",\"quantity\":1,\"unitPrice\":-100.00}]}",
                "unitPrice");
    }

    @Test
    @DisplayName("a missing currency is refused rather than defaulted to USD")
    void missingCurrencyIsRefused() throws Exception {
        refused("{\"shippingAddressId\":\"" + ADDRESS + "\","
                + "\"items\":[{\"variantId\":\"" + UUID.randomUUID() + "\","
                + "\"title\":\"t\",\"quantity\":1,\"unitPrice\":100.00}]}",
                "currency");
    }

    @ParameterizedTest
    @ValueSource(strings = {"zmw", "Zmw", "Z-W", "12 ", "   "})
    @DisplayName("a currency that is not three upper-case letters is refused")
    void malformedCurrencyIsRefused(String currency) throws Exception {
        // Upper-case specifically: PaymentIntentVerifier compares this order's
        // currency to the intent's with equals(), so a lower-case order could
        // never be settled by an upper-case intent.
        refused("{\"shippingAddressId\":\"" + ADDRESS + "\",\"currency\":\"" + currency + "\","
                + "\"items\":[{\"variantId\":\"" + UUID.randomUUID() + "\","
                + "\"title\":\"t\",\"quantity\":1,\"unitPrice\":100.00}]}",
                "currency");
    }

    @Test
    @DisplayName("the Order aggregate refuses to invent a currency")
    void theAggregateRefusesABlankCurrency() {
        // The backstop behind the DTO. Whoever ends up supplying the currency
        // -- the caller today, catalog once order prices orders itself -- an
        // order that does not know what unit its money is in is not an order.
        assertThatThrownBy(() -> new Order("kc-alice", UUID.randomUUID(), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Order("kc-alice", UUID.randomUUID(), "  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a well-formed order is still placed, in the currency it named")
    void theHappyPathIsUntouched() throws Exception {
        mvc.perform(post("/api/v1/orders")
                        .with(alice())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyWithQuantity(2)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.currency").value("ZMW"))
                .andExpect(jsonPath("$.subtotal").value(200.00))
                .andExpect(jsonPath("$.status").value("CREATED"));
    }
}
