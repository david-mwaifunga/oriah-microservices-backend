package com.oriah.catalog.controller;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.oriah.catalog.StubPromotion;
import com.oriah.catalog.TestcontainersConfiguration;
import com.oriah.catalog.model.Product;
import com.oriah.catalog.model.ProductKind;
import com.oriah.catalog.model.ProductVariant;
import com.oriah.catalog.repository.PriceHistoryRepository;
import com.oriah.catalog.repository.PriceHistoryRepository.NewEntry;
import com.oriah.catalog.repository.ProductRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A paper sale keyed after the day it was made is priced at its true date (Phase 7, phone and paper sales). The staff twin of the
 * storefront's read: it prices at an instant that has passed, uses the price in force THEN and the promotion in force THEN, and ignores whether
 * the piece is on sale today. It is never zero and never today's price when there was no price yet.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestChannelBinderConfiguration.class})
@TestPropertySource(properties = {"spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
class AsOfPricingHttpTest {

    private static final String AS_OF = "/api/v1/admin/variants/pricing-as-of";

    private record Piece(UUID productId, UUID variantId) {
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ProductRepository products;

    @Autowired
    private PriceHistoryRepository history;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    @BeforeEach
    @org.junit.jupiter.api.AfterEach
    void fresh() {
        // After as well as before: the stand-in is one server for the whole run, and a test that made it fail must not leave it failing
        // for the next test class, which may never have heard of promotion.
        StubPromotion.reset();
    }

    private static RequestPostProcessor sales() {
        return jwt().jwt(j -> j.subject("kc-sam")).authorities(new SimpleGrantedAuthority("ROLE_SALES_AGENT"));
    }

    /** A product with one variant; {@code prices} are "instant=price" rows of its history. {@code publish}: whether it is on sale today. */
    private Piece piece(boolean publish, String... prices) {
        Product product = new Product("Piece " + UUID.randomUUID());
        ProductVariant variant = new ProductVariant(product, "SKU-" + UUID.randomUUID(), new BigDecimal("999.00"), "ZMW", Map.of());
        product.addVariant(variant);
        if (publish) {
            product.publish();
        }
        products.saveAndFlush(product);
        for (String row : prices) {
            String[] parts = row.split("=");
            history.append(new NewEntry(UUID.randomUUID(), variant.id(), new BigDecimal(parts[1]), "ZMW", Instant.parse(parts[0]),
                    Instant.parse(parts[0]), "user:kc-maya", "Maya", "OK", null, null));
        }
        return new Piece(product.id(), variant.id());
    }

    private ResultActions asOf(String at, UUID... variantIds) throws Exception {
        String ids = java.util.Arrays.stream(variantIds).map(id -> "\"" + id + "\"").collect(java.util.stream.Collectors.joining(","));
        return mvc.perform(post(AS_OF).with(sales()).contentType(MediaType.APPLICATION_JSON)
                .content("{" + (at == null ? "" : "\"at\":\"" + at + "\",") + "\"variantIds\":[" + ids + "]}"));
    }

    // ---------------------------------------------------------------- who may

    @Test
    @DisplayName("only SALES_AGENT may price a sale at a past date: a shopper, a catalogue admin without it and an anonymous caller may not")
    void whoMay() throws Exception {
        Piece ring = piece(true, "2026-03-01T08:00:00Z=100.00");
        String body = "{\"at\":\"2026-03-05T08:00:00Z\",\"variantIds\":[\"" + ring.variantId() + "\"]}";

        mvc.perform(post(AS_OF).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        mvc.perform(post(AS_OF).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"))).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(post(AS_OF).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CATALOG_ADMIN"))).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(post(AS_OF).with(sales()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
    }

    // ---------------------------------------------------------------- the price at the instant

    @Test
    @DisplayName("the price is the one in force at that instant, not today's: from a row's own instant it applies, a moment before it does not")
    void thePriceAtTheInstant() throws Exception {
        Piece ring = piece(true, "2026-03-01T08:00:00Z=100.00", "2026-03-10T08:00:00Z=150.00");

        asOf("2026-03-05T12:00:00Z", ring.variantId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.at").value("2026-03-05T12:00:00Z"))
                .andExpect(jsonPath("$.results[0].status").value("PRICED"))
                .andExpect(jsonPath("$.results[0].unitPrice").value(100.00))
                .andExpect(jsonPath("$.results[0].currency").value("ZMW"))
                .andExpect(jsonPath("$.results[0].priceId").exists())
                .andExpect(jsonPath("$.results[0].discountPerPiece").value(0.00))
                .andExpect(jsonPath("$.results[0].promotionId").doesNotExist())
                .andExpect(jsonPath("$.results[0].netUnitPrice").value(100.00));
        asOf("2026-03-10T08:00:00Z", ring.variantId()).andExpect(jsonPath("$.results[0].unitPrice").value(150.00));
        asOf("2026-03-10T07:59:59Z", ring.variantId()).andExpect(jsonPath("$.results[0].unitPrice").value(100.00));
        asOf("2026-04-01T00:00:00Z", ring.variantId()).andExpect(jsonPath("$.results[0].unitPrice").value(150.00));
    }

    @Test
    @DisplayName("before a piece was first priced it has NO price at that date: never zero and never today's price")
    void noPriceYet() throws Exception {
        Piece ring = piece(true, "2026-03-10T08:00:00Z=150.00");

        asOf("2026-03-09T23:00:00Z", ring.variantId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].status").value("NO_PRICE_AT_DATE"))
                .andExpect(jsonPath("$.results[0].productId").value(ring.productId().toString()))
                .andExpect(jsonPath("$.results[0].unitPrice").doesNotExist())
                .andExpect(jsonPath("$.results[0].netUnitPrice").doesNotExist())
                .andExpect(jsonPath("$.results[0].priceId").doesNotExist());
    }

    @Test
    @DisplayName("a piece that is not on sale today (a draft) is still priced at its date: a sale made when it was on sale must be recordable")
    void todaysPublicationStateIsIgnored() throws Exception {
        Piece withdrawn = piece(false, "2026-03-01T08:00:00Z=100.00");

        asOf("2026-03-05T12:00:00Z", withdrawn.variantId()).andExpect(jsonPath("$.results[0].status").value("PRICED"))
                .andExpect(jsonPath("$.results[0].unitPrice").value(100.00));
    }

    @Test
    @DisplayName("packaging and an id nobody knows are UNKNOWN, and every asked id is answered in order, duplicates included")
    void packagingAndUnknown() throws Exception {
        Product box = new Product(UUID.randomUUID(), "Box " + UUID.randomUUID(), null, java.util.List.of(), ProductKind.PACKAGING);
        ProductVariant boxVariant = new ProductVariant(box, "BOX-" + UUID.randomUUID(), BigDecimal.ZERO, "ZMW", Map.of());
        box.addVariant(boxVariant);
        products.saveAndFlush(box);
        Piece ring = piece(true, "2026-03-01T08:00:00Z=100.00");

        asOf("2026-03-05T12:00:00Z", boxVariant.id(), UUID.randomUUID(), ring.variantId(), ring.variantId())
                .andExpect(jsonPath("$.results.length()").value(4))
                .andExpect(jsonPath("$.results[0].status").value("UNKNOWN"))
                .andExpect(jsonPath("$.results[1].status").value("UNKNOWN"))
                .andExpect(jsonPath("$.results[2].status").value("PRICED"))
                .andExpect(jsonPath("$.results[3].status").value("PRICED"));
    }

    // ---------------------------------------------------------------- the promotion at the instant

    @Test
    @DisplayName("the promotion in force THEN applies, worked out on THAT day's price: a sale during last week's promotion is discounted, one after it ended is not")
    void thePromotionAtTheInstant() throws Exception {
        Piece ring = piece(true, "2026-03-01T08:00:00Z=1000.00", "2026-03-06T08:00:00Z=2000.00");
        var promotion = StubPromotion.definition("SHOP", null, "PERCENTAGE", "10.00", "2026-03-03");
        StubPromotion.promote(promotion, Instant.parse("2026-03-03T00:00:00Z"), Instant.parse("2026-03-08T00:00:00Z"));

        asOf("2026-03-04T12:00:00Z", ring.variantId())
                .andExpect(jsonPath("$.results[0].unitPrice").value(1000.00))
                .andExpect(jsonPath("$.results[0].discountPerPiece").value(100.00))
                .andExpect(jsonPath("$.results[0].promotionId").value(promotion.id().toString()))
                .andExpect(jsonPath("$.results[0].netUnitPrice").value(900.00));
        asOf("2026-03-07T12:00:00Z", ring.variantId())
                .andExpect(jsonPath("$.results[0].unitPrice").value(2000.00))
                .andExpect(jsonPath("$.results[0].discountPerPiece").value(200.00))
                .andExpect(jsonPath("$.results[0].netUnitPrice").value(1800.00));
        asOf("2026-03-09T12:00:00Z", ring.variantId())
                .andExpect(jsonPath("$.results[0].discountPerPiece").value(0.00))
                .andExpect(jsonPath("$.results[0].promotionId").doesNotExist())
                .andExpect(jsonPath("$.results[0].netUnitPrice").value(2000.00));

        assertThat(StubPromotion.calls()).extracting(StubPromotion.Call::at)
                .containsExactly("2026-03-04T12:00:00Z", "2026-03-07T12:00:00Z", "2026-03-09T12:00:00Z");
    }

    @Test
    @DisplayName("a piece with no price at that date is not discounted, and nothing priced is no question to promotion")
    void noPriceNoDiscountNoQuestion() throws Exception {
        Piece ring = piece(true, "2026-03-10T08:00:00Z=150.00");
        StubPromotion.promote(StubPromotion.definition("SHOP", null, "PERCENTAGE", "10.00", "2026-03-01"));
        StubPromotion.failWith(500);

        asOf("2026-03-05T12:00:00Z", ring.variantId()).andExpect(status().isOk()).andExpect(jsonPath("$.results[0].status").value("NO_PRICE_AT_DATE"));

        assertThat(StubPromotion.calls()).isEmpty();
    }

    @Test
    @DisplayName("promotion down is a 503 with Retry-After and no prices: a sale is never priced without knowing what applied")
    void promotionOutageIsA503() throws Exception {
        Piece ring = piece(true, "2026-03-01T08:00:00Z=100.00");
        StubPromotion.failWith(500);

        asOf("2026-03-05T12:00:00Z", ring.variantId())
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "5"))
                .andExpect(jsonPath("$.results").doesNotExist());
    }

    // ---------------------------------------------------------------- the request

    @Test
    @DisplayName("an instant that has not happened yet, no instant, no ids and too many ids are all a 400")
    void badRequests() throws Exception {
        Piece ring = piece(true, "2026-03-01T08:00:00Z=100.00");

        asOf(Instant.now().plusSeconds(3600).toString(), ring.variantId()).andExpect(status().isBadRequest());
        asOf(null, ring.variantId()).andExpect(status().isBadRequest());
        asOf("2026-03-05T12:00:00Z").andExpect(status().isBadRequest());
        UUID[] tooMany = new UUID[101];
        java.util.Arrays.setAll(tooMany, i -> UUID.randomUUID());
        asOf("2026-03-05T12:00:00Z", tooMany).andExpect(status().isBadRequest());
        asOf("last tuesday", ring.variantId()).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("an instant a few seconds ahead (two services' clocks never agree exactly) is accepted; a minute ahead is not")
    void clockSkewIsTolerated() throws Exception {
        Piece ring = piece(true, "2026-03-01T08:00:00Z=100.00");

        asOf(Instant.now().plusSeconds(5).toString(), ring.variantId()).andExpect(status().isOk());
        asOf(Instant.now().plusSeconds(90).toString(), ring.variantId()).andExpect(status().isBadRequest());
    }
}
