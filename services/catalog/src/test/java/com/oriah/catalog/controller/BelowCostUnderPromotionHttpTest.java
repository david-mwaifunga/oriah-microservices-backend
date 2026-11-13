package com.oriah.catalog.controller;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cloud.stream.binder.test.TestChannelBinderConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.oriah.catalog.StubInventory;
import com.oriah.catalog.StubPromotion;
import com.oriah.catalog.TestcontainersConfiguration;
import com.oriah.catalog.pricing.PricingFixtures;
import com.oriah.catalog.pricing.PricingFixtures.Piece;
import com.oriah.catalog.promotion.PromotionDefinition;
import com.oriah.catalog.repository.ProductRepository;

import static com.oriah.catalog.StubInventory.lot;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Where every price stands against the cost, judged on what a customer PAYS per piece: the list price less the promotion in force. It is also
 * how an admin sees what a promotion does: ask for a later instant to see what a promotion that has not started yet will do to the prices once
 * it starts, and narrow to one promotion to list the pieces IT discounts (Q9: a promotion that pushes a piece below cost is never refused and
 * needs no reason; it is warned about and listed). A promotion that cannot be asked is a 503, never "no promotion".
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestChannelBinderConfiguration.class})
@TestPropertySource(properties = {"spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
class BelowCostUnderPromotionHttpTest {

    private static final String LIST = "/api/v1/admin/pricing/below-cost";

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
        StubPromotion.reset();
        PricingFixtures.wipeCatalogue(jdbc);
        fixtures = new PricingFixtures(products);
    }

    @AfterEach
    void clean() {
        // The stand-in is one server for the whole run: a test that made it fail must not leave it failing for the next test class.
        StubPromotion.reset();
    }

    private static RequestPostProcessor maya() {
        return jwt().jwt(j -> j.subject("kc-maya")).authorities(new SimpleGrantedAuthority("ROLE_CATALOG_ADMIN"), new SimpleGrantedAuthority("ROLE_COST_VIEWER"));
    }

    private ResultActions list(String query) throws Exception {
        return mvc.perform(get(LIST + query).with(maya()));
    }

    /** A priced piece that holds stock at the given cost. */
    private Piece piece(String title, String price, String cost) {
        Piece piece = fixtures.ring(title);
        PricingFixtures.priceDirectly(jdbc, piece.variantId(), price);
        StubInventory.holds(piece.variantId(), lot(cost, 2));
        return piece;
    }

    private PromotionDefinition shopWide(String percent) {
        PromotionDefinition promotion = StubPromotion.definition("SHOP", null, "PERCENTAGE", percent, "2026-11-01");
        StubPromotion.promote(promotion);
        return promotion;
    }

    @Test
    @DisplayName("a price that clears the cost on the list but not on the net is listed BELOW, with the list price, the discount, the promotion and the net price beside it")
    void judgedOnTheNet() throws Exception {
        Piece below = piece("Below once discounted", "1000.00", "900.00");
        piece("Fine either way", "2000.00", "900.00");
        PromotionDefinition promotion = shopWide("20.00");

        list("")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].variantId").value(below.variantId().toString()))
                .andExpect(jsonPath("$.items[0].price").value(1000.00))
                .andExpect(jsonPath("$.items[0].discountPerPiece").value(200.00))
                .andExpect(jsonPath("$.items[0].promotionId").value(promotion.id().toString()))
                .andExpect(jsonPath("$.items[0].netPrice").value(800.00))
                .andExpect(jsonPath("$.items[0].assessment").value("BELOW"))
                .andExpect(jsonPath("$.items[0].floor").value(900.00))
                .andExpect(jsonPath("$.items[0].shortfall").value(100.00))
                .andExpect(jsonPath("$.counts.BELOW").value(1))
                .andExpect(jsonPath("$.counts.OK").value(1));
    }

    @Test
    @DisplayName("the same piece with no promotion in force stands OK: nothing is discounted, and the net price is the list price")
    void withoutAPromotion() throws Exception {
        piece("Fine", "1000.00", "900.00");

        list("?status=ALL")
                .andExpect(jsonPath("$.items[0].assessment").value("OK"))
                .andExpect(jsonPath("$.items[0].discountPerPiece").value(0.00))
                .andExpect(jsonPath("$.items[0].promotionId").value(nullValue()))
                .andExpect(jsonPath("$.items[0].netPrice").value(1000.00));
    }

    @Test
    @DisplayName("promotion is asked ONCE for the whole list, about now, however many pieces are priced")
    void oneQuestionForTheList() throws Exception {
        piece("A", "1000.00", "900.00");
        piece("B", "1000.00", "900.00");
        piece("C", "1000.00", "900.00");
        shopWide("5.00");

        list("?status=ALL").andExpect(status().isOk()).andExpect(jsonPath("$.items", hasSize(3)));

        assertThat(StubPromotion.calls()).hasSize(1);
        assertThat(StubPromotion.calls().getFirst().at()).isNull();
    }

    @Test
    @DisplayName("a later instant shows what a promotion that has not started yet will do: OK today, BELOW once it starts, and the page says which instant it applied")
    void whatAPromotionWillDo() throws Exception {
        piece("Ring", "1000.00", "900.00");
        Instant starts = Instant.now().plus(Duration.ofDays(7));
        StubPromotion.promote(StubPromotion.definition("SHOP", null, "PERCENTAGE", "20.00", "2026-11-01"), starts, starts.plus(Duration.ofDays(7)));

        list("?status=ALL").andExpect(jsonPath("$.items[0].assessment").value("OK")).andExpect(jsonPath("$.items[0].discountPerPiece").value(0.00));

        Instant during = starts.plus(Duration.ofDays(1));
        list("?status=ALL&at=" + during)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.at").value(during.toString()))
                .andExpect(jsonPath("$.items[0].assessment").value("BELOW"))
                .andExpect(jsonPath("$.items[0].netPrice").value(800.00));
        assertThat(StubPromotion.calls()).extracting(StubPromotion.Call::at).containsExactly(null, during.toString());
    }

    @Test
    @DisplayName("an instant that has passed is a 400: cost is read now, so a past promotion would be judged against today's stock and mean nothing")
    void aPastInstantIsRefused() throws Exception {
        piece("Ring", "1000.00", "900.00");

        list("?at=" + Instant.now().minus(Duration.ofDays(1))).andExpect(status().isBadRequest());
        list("?at=last-tuesday").andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("narrowed to one promotion it lists the pieces THAT promotion discounts, and the counts are about those pieces; a promotion that loses to a bigger one lists nothing")
    void narrowedToOnePromotion() throws Exception {
        Piece a = piece("A", "1000.00", "900.00");
        Piece b = piece("B", "1000.00", "900.00");
        PromotionDefinition shop = shopWide("5.00");
        PromotionDefinition onA = StubPromotion.definition("PRODUCT", a.productId(), "PERCENTAGE", "30.00", "2026-11-01");
        StubPromotion.promote(onA);

        list("?status=ALL&promotionId=" + onA.id())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].variantId").value(a.variantId().toString()))
                .andExpect(jsonPath("$.items[0].assessment").value("BELOW"))
                .andExpect(jsonPath("$.counts.BELOW").value(1))
                .andExpect(jsonPath("$.counts.OK").value(0))
                .andExpect(jsonPath("$.totalItems").value(1));
        list("?status=ALL&promotionId=" + shop.id())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].variantId").value(b.variantId().toString()))
                .andExpect(jsonPath("$.items[0].assessment").value("OK"));
        list("?status=ALL&promotionId=" + UUID.randomUUID()).andExpect(jsonPath("$.items", hasSize(0))).andExpect(jsonPath("$.totalItems").value(0));
    }

    @Test
    @DisplayName("promotion down is a 503 with Retry-After: the list is never shown judged on list prices while a promotion may be running")
    void promotionDown() throws Exception {
        piece("Ring", "1000.00", "900.00");
        StubPromotion.failWith(500);

        list("").andExpect(status().isServiceUnavailable()).andExpect(header().string("Retry-After", "5"));
    }

    @Test
    @DisplayName("nothing priced is no question to promotion: an empty catalogue lists even while promotion is down")
    void nothingPriced() throws Exception {
        StubPromotion.failWith(500);

        list("?status=ALL").andExpect(status().isOk()).andExpect(jsonPath("$.items", hasSize(0)));

        assertThat(StubPromotion.calls()).isEmpty();
    }
}
