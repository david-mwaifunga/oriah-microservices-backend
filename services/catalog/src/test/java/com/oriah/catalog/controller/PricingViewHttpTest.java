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
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.oriah.catalog.StubInventory;
import com.oriah.catalog.TestcontainersConfiguration;
import com.oriah.catalog.pricing.PricingFixtures;
import com.oriah.catalog.pricing.PricingFixtures.Piece;
import com.oriah.catalog.repository.ProductRepository;

import static com.oriah.catalog.StubInventory.estimatedLot;
import static com.oriah.catalog.StubInventory.lot;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The pricing screen's read: the price in force, its dated history, the cost it stands against (every held lot, the dearest, the
 * average, side by side) and a suggestion that is only ever a suggestion. The cost is read afresh every time, so a cost that rises
 * after a price was set shows up the next time anyone looks.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestChannelBinderConfiguration.class})
@TestPropertySource(properties = {"spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
class PricingViewHttpTest {

    private static final String PRICING = "/api/v1/admin/pricing/variants/";

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
        fixtures = new PricingFixtures(products);
    }

    private static RequestPostProcessor maya() {
        return jwt().jwt(j -> j.subject("kc-maya").claim("name", "Maya Banda"))
                .authorities(new SimpleGrantedAuthority("ROLE_CATALOG_ADMIN"), new SimpleGrantedAuthority("ROLE_COST_VIEWER"));
    }

    private void price(UUID variant, String price, UUID expected) throws Exception {
        mvc.perform(put(PRICING + variant + "/price").with(maya()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"unitPrice\":" + price + ",\"expectedPriceId\":" + (expected == null ? "null" : "\"" + expected + "\"") + "}"))
                .andExpect(status().isOk());
    }

    private UUID latestPriceId(UUID variant) {
        return jdbc.queryForObject("SELECT id FROM variant_price WHERE variant_id = ? ORDER BY effective_from DESC, seq DESC LIMIT 1", UUID.class, variant);
    }

    @Test
    @DisplayName("a variant that was never priced: no price, no history, no judgement, but the cost it will be priced against is already there")
    void anUnpricedVariant() throws Exception {
        Piece ring = fixtures.ring("Aurelia");
        StubInventory.holds(ring.variantId(), lot("1528.98", 3), lot("1528.99", 4));

        mvc.perform(get(PRICING + ring.variantId()).with(maya()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.variantId").value(ring.variantId().toString()))
                .andExpect(jsonPath("$.productId").value(ring.productId().toString()))
                .andExpect(jsonPath("$.productStatus").value("DRAFT"))
                .andExpect(jsonPath("$.currentPrice").value(nullValue()))
                .andExpect(jsonPath("$.assessment").value(nullValue()))
                .andExpect(jsonPath("$.history", hasSize(0)))
                .andExpect(jsonPath("$.cost.floor").value(1528.99))
                .andExpect(jsonPath("$.cost.goods.lots", hasSize(2)))
                .andExpect(jsonPath("$.cost.goods.averageUnitCost").value(1528.99))
                .andExpect(jsonPath("$.suggestion").value(nullValue()));
    }

    @Test
    @DisplayName("the history is dated and newest first, and says who set each price and how it stood against the cost then")
    void theHistory() throws Exception {
        Piece ring = fixtures.ring("Aurelia");
        StubInventory.holds(ring.variantId(), lot("1000.00", 3));
        price(ring.variantId(), "1500.00", null);
        price(ring.variantId(), "1800.00", latestPriceId(ring.variantId()));

        mvc.perform(get(PRICING + ring.variantId()).with(maya()))
                .andExpect(jsonPath("$.currentPrice.unitPrice").value(1800.00))
                .andExpect(jsonPath("$.history", hasSize(2)))
                .andExpect(jsonPath("$.history[0].unitPrice").value(1800.00))
                .andExpect(jsonPath("$.history[1].unitPrice").value(1500.00))
                .andExpect(jsonPath("$.history[0].setByName").value("Maya Banda"))
                .andExpect(jsonPath("$.history[0].assessment").value("OK"))
                .andExpect(jsonPath("$.history[0].effectiveFrom").exists())
                .andExpect(jsonPath("$.history[0].recordedAt").exists())
                .andExpect(jsonPath("$.history[0].costBasis.floor").value(1000.00))
                .andExpect(jsonPath("$.assessment").value("OK"));
    }

    @Test
    @DisplayName("the price in force is judged against the cost as it is NOW: a dearer lot received after the price was set makes it BELOW, with the shortfall")
    void aCostThatRoseIsSeenAtOnce() throws Exception {
        Piece ring = fixtures.ring("Aurelia");
        StubInventory.holds(ring.variantId(), lot("1000.00", 3));
        price(ring.variantId(), "1200.00", null);

        StubInventory.holds(ring.variantId(), lot("1000.00", 3), lot("1300.00", 4));   // a dearer consignment arrives

        mvc.perform(get(PRICING + ring.variantId()).with(maya()))
                .andExpect(jsonPath("$.assessment").value("BELOW"))
                .andExpect(jsonPath("$.shortfall").value(100.00))
                .andExpect(jsonPath("$.cost.floor").value(1300.00))
                .andExpect(jsonPath("$.history[0].assessment").value("OK"));
    }

    @Test
    @DisplayName("a suggestion is the landed cost plus the asked-for percentage of it, rounded up to the ngwee, and is never applied")
    void aSuggestion() throws Exception {
        Piece ring = fixtures.ring("Aurelia");
        StubInventory.holds(ring.variantId(), lot("1528.99", 4));

        mvc.perform(get(PRICING + ring.variantId()).with(maya()).param("targetProfitPercent", "40"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suggestion.targetProfitPercent").value(40))
                .andExpect(jsonPath("$.suggestion.landedCost").value(1528.99))
                .andExpect(jsonPath("$.suggestion.suggestedPrice").value(2140.59));

        assertThat(jdbc.queryForObject("SELECT unit_price FROM product_variants WHERE id = ?", java.math.BigDecimal.class, ring.variantId()))
                .as("a suggestion changes nothing").isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("the landed cost behind a suggestion includes the default box, because that is the figure the warning uses")
    void theSuggestionCountsTheBox() throws Exception {
        Piece ring = fixtures.ring("Aurelia");
        Piece box = fixtures.box("Gift box");
        fixtures.defaultBox(ring.productId(), box.variantId());
        StubInventory.holds(ring.variantId(), lot("1000.00", 1));
        StubInventory.holds(box.variantId(), lot("100.00", 5));

        mvc.perform(get(PRICING + ring.variantId()).with(maya()).param("targetProfitPercent", "10"))
                .andExpect(jsonPath("$.suggestion.landedCost").value(1100.00))
                .andExpect(jsonPath("$.suggestion.suggestedPrice").value(1210.00))
                .andExpect(jsonPath("$.defaultPackagingVariantId").value(box.variantId().toString()));
    }

    @Test
    @DisplayName("no cost known means no suggestion, even when a percentage is asked for: nothing is invented")
    void noCostNoSuggestion() throws Exception {
        Piece ring = fixtures.ring("Aurelia");

        mvc.perform(get(PRICING + ring.variantId()).with(maya()).param("targetProfitPercent", "40"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suggestion").value(nullValue()))
                .andExpect(jsonPath("$.cost.floor").value(nullValue()));
    }

    @Test
    @DisplayName("a percentage outside 0 to 1000, or with three decimals, is a 400")
    void thePercentageIsChecked() throws Exception {
        Piece ring = fixtures.ring("Aurelia");
        StubInventory.holds(ring.variantId(), lot("100.00", 1));

        for (String bad : new String[] {"-1", "1000.01", "12.345"}) {
            mvc.perform(get(PRICING + ring.variantId()).with(maya()).param("targetProfitPercent", bad)).andExpect(status().isBadRequest());
        }
    }

    @Test
    @DisplayName("an estimated lot (opening stock keyed from a book) is flagged on the cost, so the screen can say the figure is approximate")
    void estimatedIsFlagged() throws Exception {
        Piece ring = fixtures.ring("Aurelia");
        StubInventory.holds(ring.variantId(), estimatedLot("1000.00", 7));

        mvc.perform(get(PRICING + ring.variantId()).with(maya()))
                .andExpect(jsonPath("$.cost.estimated").value(true))
                .andExpect(jsonPath("$.cost.goods.lots[0].estimated").value(true));
    }

    @Test
    @DisplayName("a price from before history existed shows as MIGRATED and never assessed, not as a price somebody set against a cost")
    void aMigratedRow() throws Exception {
        Piece ring = fixtures.ring("Aurelia");
        PricingFixtures.priceDirectly(jdbc, ring.variantId(), "1500.00");
        jdbc.update("""
                INSERT INTO variant_price (id, variant_id, unit_price, currency, effective_from, recorded_at, set_by, source, assessment)
                VALUES (gen_random_uuid(), ?, 1500.00, 'ZMW', now(), now(), 'system:migration', 'MIGRATED', 'NOT_ASSESSED')""", ring.variantId());
        StubInventory.holds(ring.variantId(), lot("1000.00", 1));

        mvc.perform(get(PRICING + ring.variantId()).with(maya()))
                .andExpect(jsonPath("$.history[0].source").value("MIGRATED"))
                .andExpect(jsonPath("$.history[0].assessment").value("NOT_ASSESSED"))
                .andExpect(jsonPath("$.history[0].costBasis").value(nullValue()))
                .andExpect(jsonPath("$.assessment").value("OK"));
    }

    @Test
    @DisplayName("an unknown variant is 404, packaging is 409 (a box has no price to read), and stock being down is 503 with Retry-After")
    void theOtherAnswers() throws Exception {
        mvc.perform(get(PRICING + UUID.randomUUID()).with(maya())).andExpect(status().isNotFound());
        mvc.perform(get(PRICING + fixtures.box("Gift box").variantId()).with(maya())).andExpect(status().isConflict());

        Piece ring = fixtures.ring("Aurelia");
        StubInventory.failWith(500);
        mvc.perform(get(PRICING + ring.variantId()).with(maya())).andExpect(status().isServiceUnavailable())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().exists("Retry-After"));
    }

    @Test
    @DisplayName("the read needs CATALOG_ADMIN and COST_VIEWER both, like the write: cost is gated at the API")
    void whoMayRead() throws Exception {
        Piece ring = fixtures.ring("Aurelia");

        mvc.perform(get(PRICING + ring.variantId()).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CATALOG_ADMIN")))).andExpect(status().isForbidden());
        mvc.perform(get(PRICING + ring.variantId()).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_COST_VIEWER")))).andExpect(status().isForbidden());
        mvc.perform(get(PRICING + ring.variantId())).andExpect(status().isUnauthorized());
        assertThat(StubInventory.calls()).isEmpty();
    }
}
