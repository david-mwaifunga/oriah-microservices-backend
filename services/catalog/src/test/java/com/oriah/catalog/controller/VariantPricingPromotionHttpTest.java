package com.oriah.catalog.controller;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
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
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.oriah.catalog.StubPromotion;
import com.oriah.catalog.TestcontainersConfiguration;
import com.oriah.catalog.model.Category;
import com.oriah.catalog.model.Product;
import com.oriah.catalog.model.ProductKind;
import com.oriah.catalog.model.ProductVariant;
import com.oriah.catalog.repository.CategoryRepository;
import com.oriah.catalog.repository.ProductRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What a customer is charged per piece: the price list says one thing and a promotion may take something off EACH piece (round 5), so the
 * storefront's pricing read carries the list price, the discount per piece, the promotion that gives it and the net price. Anonymous, like
 * the product page. A promotion that cannot be asked is a 503, never the full price.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestChannelBinderConfiguration.class})
@TestPropertySource(properties = {"spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
class VariantPricingPromotionHttpTest {

    private static final String PRICING = "/api/v1/variants/pricing";

    /** A piece on sale: the product and its one variant. */
    private record Piece(UUID productId, UUID variantId) {
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ProductRepository products;

    @Autowired
    private CategoryRepository categories;

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

    private Piece published(String price, Category... in) {
        Product product = new Product("Piece " + UUID.randomUUID());
        ProductVariant variant = new ProductVariant(product, "SKU-" + UUID.randomUUID(), new BigDecimal(price), "ZMW", Map.of());
        product.addVariant(variant);
        if (in.length > 0) {
            product.assignCategories(Set.of(in), in[0]);
        }
        product.publish();
        products.saveAndFlush(product);
        return new Piece(product.id(), variant.id());
    }

    private org.springframework.test.web.servlet.ResultActions price(UUID... variantIds) throws Exception {
        String ids = java.util.Arrays.stream(variantIds).map(id -> "\"" + id + "\"").collect(java.util.stream.Collectors.joining(","));
        return mvc.perform(post(PRICING).contentType(MediaType.APPLICATION_JSON).content("{\"variantIds\":[" + ids + "]}"));
    }

    @Test
    @DisplayName("with no promotion a piece is priced at its list price: no discount, no promotion, the net price is the list price")
    void noPromotion() throws Exception {
        Piece ring = published("4500.00");

        price(ring.variantId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].status").value("PRICED"))
                .andExpect(jsonPath("$.results[0].unitPrice").value(4500.00))
                .andExpect(jsonPath("$.results[0].discountPerPiece").value(0.00))
                .andExpect(jsonPath("$.results[0].promotionId").doesNotExist())
                .andExpect(jsonPath("$.results[0].netUnitPrice").value(4500.00));
    }

    @Test
    @DisplayName("a shop-wide 10% takes K450.00 off a K4,500.00 piece: the list price stays, and the net price is K4,050.00")
    void shopWide() throws Exception {
        var promotion = StubPromotion.definition("SHOP", null, "PERCENTAGE", "10.00", "2026-11-20");
        StubPromotion.promote(promotion);
        Piece ring = published("4500.00");

        price(ring.variantId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].unitPrice").value(4500.00))
                .andExpect(jsonPath("$.results[0].discountPerPiece").value(450.00))
                .andExpect(jsonPath("$.results[0].promotionId").value(promotion.id().toString()))
                .andExpect(jsonPath("$.results[0].netUnitPrice").value(4050.00));
    }

    @Test
    @DisplayName("a category promotion reaches a piece filed in a sub-category, a product promotion only its product: each piece is discounted at its own price")
    void scopes() throws Exception {
        Category jewellery = categories.saveAndFlush(new Category("Jewellery " + UUID.randomUUID(), null, null));
        Category rings = categories.saveAndFlush(new Category("Rings " + UUID.randomUUID(), null, jewellery));
        Category watches = categories.saveAndFlush(new Category("Watches " + UUID.randomUUID(), null, null));
        Piece ring = published("200.00", rings);
        Piece watch = published("1000.00", watches);
        var onJewellery = StubPromotion.definition("CATEGORY", jewellery.id(), "PERCENTAGE", "5.00", "2026-11-20");
        var onThatWatch = StubPromotion.definition("PRODUCT", watch.productId(), "FIXED", "100.00", "2026-11-20");
        StubPromotion.promote(onJewellery);
        StubPromotion.promote(onThatWatch);

        price(ring.variantId(), watch.variantId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].discountPerPiece").value(10.00))
                .andExpect(jsonPath("$.results[0].promotionId").value(onJewellery.id().toString()))
                .andExpect(jsonPath("$.results[0].netUnitPrice").value(190.00))
                .andExpect(jsonPath("$.results[1].discountPerPiece").value(100.00))
                .andExpect(jsonPath("$.results[1].promotionId").value(onThatWatch.id().toString()))
                .andExpect(jsonPath("$.results[1].netUnitPrice").value(900.00));
    }

    @Test
    @DisplayName("a Kwacha amount off bigger than the piece makes it free, never negative")
    void neverBelowNothing() throws Exception {
        StubPromotion.promote(StubPromotion.definition("SHOP", null, "FIXED", "500.00", "2026-11-20"));
        Piece cheap = published("300.00");

        price(cheap.variantId())
                .andExpect(jsonPath("$.results[0].discountPerPiece").value(300.00))
                .andExpect(jsonPath("$.results[0].netUnitPrice").value(0.00));
    }

    @Test
    @DisplayName("the same variant twice in a bag is answered twice, the same both times, and promotion is asked once for the whole request")
    void oneQuestionForTheBag() throws Exception {
        StubPromotion.promote(StubPromotion.definition("SHOP", null, "PERCENTAGE", "10.00", "2026-11-20"));
        Piece a = published("100.00");
        Piece b = published("200.00");

        price(a.variantId(), b.variantId(), a.variantId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results.length()").value(3))
                .andExpect(jsonPath("$.results[0].netUnitPrice").value(90.00))
                .andExpect(jsonPath("$.results[1].netUnitPrice").value(180.00))
                .andExpect(jsonPath("$.results[2].netUnitPrice").value(90.00));

        assertThat(StubPromotion.calls()).hasSize(1);
        assertThat(StubPromotion.calls().getFirst().at()).as("the public read prices NOW: no instant is sent").isNull();
    }

    @Test
    @DisplayName("a piece that cannot be sold carries no price and no discount, and an id nobody knows is UNKNOWN")
    void unsellableCarriesNothing() throws Exception {
        StubPromotion.promote(StubPromotion.definition("SHOP", null, "PERCENTAGE", "10.00", "2026-11-20"));
        Product draft = new Product("Draft " + UUID.randomUUID());
        ProductVariant draftVariant = new ProductVariant(draft, "SKU-" + UUID.randomUUID(), new BigDecimal("100.00"), "ZMW", Map.of());
        draft.addVariant(draftVariant);
        products.saveAndFlush(draft);
        Piece live = published("100.00");

        price(draftVariant.id(), UUID.randomUUID(), live.variantId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].status").value("UNPUBLISHED"))
                .andExpect(jsonPath("$.results[0].unitPrice").doesNotExist())
                .andExpect(jsonPath("$.results[0].discountPerPiece").doesNotExist())
                .andExpect(jsonPath("$.results[0].netUnitPrice").doesNotExist())
                .andExpect(jsonPath("$.results[1].status").value("UNKNOWN"))
                .andExpect(jsonPath("$.results[1].netUnitPrice").doesNotExist())
                .andExpect(jsonPath("$.results[2].status").value("PRICED"))
                .andExpect(jsonPath("$.results[2].netUnitPrice").value(90.00));
    }

    @Test
    @DisplayName("packaging is never discounted: a box is answered as if it did not exist")
    void packagingIsNeverDiscounted() throws Exception {
        StubPromotion.promote(StubPromotion.definition("SHOP", null, "PERCENTAGE", "10.00", "2026-11-20"));
        Product box = new Product(UUID.randomUUID(), "Box " + UUID.randomUUID(), null, java.util.List.of(), ProductKind.PACKAGING);
        ProductVariant boxVariant = new ProductVariant(box, "BOX-" + UUID.randomUUID(), BigDecimal.ZERO, "ZMW", Map.of());
        box.addVariant(boxVariant);
        products.saveAndFlush(box);

        price(boxVariant.id())
                .andExpect(jsonPath("$.results[0].status").value("UNKNOWN"))
                .andExpect(jsonPath("$.results[0].discountPerPiece").doesNotExist());
    }

    @Test
    @DisplayName("nothing priced is no question to promotion: a bag of unknown pieces is answered even while promotion is down")
    void noQuestionWhenNothingIsPriced() throws Exception {
        StubPromotion.failWith(500);

        price(UUID.randomUUID()).andExpect(status().isOk()).andExpect(jsonPath("$.results[0].status").value("UNKNOWN"));

        assertThat(StubPromotion.calls()).isEmpty();
    }

    @Test
    @DisplayName("promotion down is a 503 with Retry-After and NO price at all: a customer is never quoted the full price during a promotion")
    void promotionOutageIsA503() throws Exception {
        Piece ring = published("4500.00");
        StubPromotion.failWith(500);

        price(ring.variantId())
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "5"))
                .andExpect(jsonPath("$.results").doesNotExist())
                .andExpect(jsonPath("$.status").value(503));
    }
}
