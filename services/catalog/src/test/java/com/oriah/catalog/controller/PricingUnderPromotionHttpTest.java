package com.oriah.catalog.controller;

import java.math.BigDecimal;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oriah.catalog.StubInventory;
import com.oriah.catalog.StubPromotion;
import com.oriah.catalog.TestcontainersConfiguration;
import com.oriah.catalog.pricing.PricingFixtures;
import com.oriah.catalog.pricing.PricingFixtures.Piece;
import com.oriah.catalog.promotion.PromotionDefinition;
import com.oriah.catalog.repository.PriceHistoryRepository;
import com.oriah.catalog.repository.PriceHistoryRepository.NewEntry;
import com.oriah.catalog.repository.ProductRepository;

import static com.oriah.catalog.StubInventory.lot;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A price is judged on what a customer PAYS per piece: the list price less the promotion in force now (round 5: promotions are per piece and
 * apply by themselves). So a price that clears the cost on the list but not once the promotion has taken its discount off is warned about,
 * saved only with a reason, and recorded with every figure (the list price, the discount, the promotion, the net price) on the price row and on
 * the price.set event. A promotion that cannot be asked is a 503 and nothing is saved, like stock that cannot be asked.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestChannelBinderConfiguration.class})
@TestPropertySource(properties = {"spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
class PricingUnderPromotionHttpTest {

    private static final String PRICING = "/api/v1/admin/pricing/variants/";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ProductRepository products;

    @Autowired
    private PriceHistoryRepository history;

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
        fixtures = new PricingFixtures(products);
    }

    @AfterEach
    void clean() {
        // The stand-in is one server for the whole run: a test that made it fail must not leave it failing for the next test class.
        StubPromotion.reset();
    }

    private static RequestPostProcessor maya() {
        return jwt().jwt(j -> j.subject("kc-maya").claim("name", "Maya Banda"))
                .authorities(new SimpleGrantedAuthority("ROLE_CATALOG_ADMIN"), new SimpleGrantedAuthority("ROLE_COST_VIEWER"));
    }

    private ResultActions setPrice(UUID variant, String json) throws Exception {
        return mvc.perform(put(PRICING + variant + "/price").with(maya()).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions view(UUID variant) throws Exception {
        return mvc.perform(get(PRICING + variant).with(maya()));
    }

    private long rows(UUID variant) {
        return jdbc.queryForObject("SELECT count(*) FROM variant_price WHERE variant_id = ?", Long.class, variant);
    }

    private String basis(UUID variant, String key) {
        return jdbc.queryForObject("SELECT cost_basis->>'" + key + "' FROM variant_price WHERE variant_id = ?", String.class, variant);
    }

    private PromotionDefinition shopWide(String percent) {
        PromotionDefinition promotion = StubPromotion.definition("SHOP", null, "PERCENTAGE", percent, "2026-11-01");
        StubPromotion.promote(promotion);
        return promotion;
    }

    /** A piece that has a price in force (a history row and the variant's current price), as one priced by an earlier save. */
    private Piece pricedAlready(String price) {
        Piece ring = fixtures.ring("Aurelia");
        history.append(new NewEntry(UUID.randomUUID(), ring.variantId(), new BigDecimal(price), "ZMW", Instant.parse("2026-03-01T08:00:00Z"),
                Instant.parse("2026-03-01T08:00:00Z"), "user:kc-maya", "Maya", "OK", null, null));
        PricingFixtures.priceDirectly(jdbc, ring.variantId(), price);
        return ring;
    }

    // ---------------------------------------------------------------- saving

    @Test
    @DisplayName("a price that clears the cost on the list but not once the promotion has taken its discount off is a warning: the NET price is judged, with every figure shown")
    void theNetPriceIsJudged() throws Exception {
        Piece ring = fixtures.ring("Aurelia");
        StubInventory.holds(ring.variantId(), lot("900.00", 2));
        PromotionDefinition promotion = shopWide("20.00");

        setPrice(ring.variantId(), "{\"unitPrice\":1000.00,\"expectedPriceId\":null}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://oriah.dev/problems/below-landed-cost"))
                .andExpect(jsonPath("$.assessment").value("BELOW"))
                .andExpect(jsonPath("$.floor").value(900.00))
                .andExpect(jsonPath("$.shortfall").value(100.00))
                .andExpect(jsonPath("$.price").value(1000.00))
                .andExpect(jsonPath("$.discountPerPiece").value(200.00))
                .andExpect(jsonPath("$.promotionId").value(promotion.id().toString()))
                .andExpect(jsonPath("$.netPrice").value(800.00));

        assertThat(rows(ring.variantId())).as("a warning saves nothing").isZero();
    }

    @Test
    @DisplayName("saved below cost on the net price with a reason: the answer, the price row and the price.set event all carry the list price, the discount, the promotion and the net price")
    void savedWithEveryFigureRecorded() throws Exception {
        Piece ring = fixtures.ring("Aurelia");
        StubInventory.holds(ring.variantId(), lot("900.00", 2));
        PromotionDefinition promotion = shopWide("20.00");

        setPrice(ring.variantId(), "{\"unitPrice\":1000.00,\"expectedPriceId\":null,\"belowCostReason\":\"launch week\",\"acknowledgedFloor\":900.00}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assessment").value("BELOW"))
                .andExpect(jsonPath("$.price.unitPrice").value(1000.00))
                .andExpect(jsonPath("$.discountPerPiece").value(200.00))
                .andExpect(jsonPath("$.promotionId").value(promotion.id().toString()))
                .andExpect(jsonPath("$.netPrice").value(800.00));

        assertThat(jdbc.queryForObject("SELECT assessment FROM variant_price WHERE variant_id = ?", String.class, ring.variantId())).isEqualTo("BELOW");
        assertThat(basis(ring.variantId(), "schema")).as("the basis grew new fields: its schema moved on").isEqualTo("2");
        assertThat(new BigDecimal(basis(ring.variantId(), "price"))).as("price is the LIST price in schema 2").isEqualByComparingTo("1000.00");
        assertThat(new BigDecimal(basis(ring.variantId(), "listPrice"))).isEqualByComparingTo("1000.00");
        assertThat(new BigDecimal(basis(ring.variantId(), "discountPerPiece"))).isEqualByComparingTo("200.00");
        assertThat(basis(ring.variantId(), "promotionId")).isEqualTo(promotion.id().toString());
        assertThat(new BigDecimal(basis(ring.variantId(), "netPrice"))).isEqualByComparingTo("800.00");
        assertThat(basis(ring.variantId(), "status")).isEqualTo("BELOW");

        String priceId = jdbc.queryForObject("SELECT id::text FROM variant_price WHERE variant_id = ?", String.class, ring.variantId());
        String payload = jdbc.queryForObject("SELECT payload::text FROM outbox WHERE aggregate_type = 'variant_price' AND aggregate_id = ?", String.class, priceId);
        JsonNode event = JSON.readTree(payload);
        assertThat(event.get("unitPrice").decimalValue()).isEqualByComparingTo("1000.00");
        assertThat(event.get("discountPerPiece").decimalValue()).isEqualByComparingTo("200.00");
        assertThat(event.get("promotionId").asText()).isEqualTo(promotion.id().toString());
        assertThat(event.get("netPrice").decimalValue()).isEqualByComparingTo("800.00");
        assertThat(event.get("assessment").asText()).isEqualTo("BELOW");
    }

    @Test
    @DisplayName("a price that clears the cost even after the discount is saved as OK with no reason, and the promotion is still recorded")
    void clearsTheCostAfterTheDiscount() throws Exception {
        Piece ring = fixtures.ring("Aurelia");
        StubInventory.holds(ring.variantId(), lot("900.00", 2));
        PromotionDefinition promotion = shopWide("20.00");

        setPrice(ring.variantId(), "{\"unitPrice\":1200.00,\"expectedPriceId\":null}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assessment").value("OK"))
                .andExpect(jsonPath("$.discountPerPiece").value(240.00))
                .andExpect(jsonPath("$.promotionId").value(promotion.id().toString()))
                .andExpect(jsonPath("$.netPrice").value(960.00));

        assertThat(basis(ring.variantId(), "status")).isEqualTo("OK");
    }

    @Test
    @DisplayName("with no promotion on the piece the net price is the list price: 0.00 off, no promotion, and the event says so")
    void noPromotionOnThisPiece() throws Exception {
        Piece ring = fixtures.ring("Aurelia");
        StubInventory.holds(ring.variantId(), lot("900.00", 2));
        StubPromotion.promote(StubPromotion.definition("PRODUCT", UUID.randomUUID(), "PERCENTAGE", "50.00", "2026-11-01"));

        setPrice(ring.variantId(), "{\"unitPrice\":1000.00,\"expectedPriceId\":null}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assessment").value("OK"))
                .andExpect(jsonPath("$.discountPerPiece").value(0.00))
                .andExpect(jsonPath("$.promotionId").value(nullValue()))
                .andExpect(jsonPath("$.netPrice").value(1000.00));

        String priceId = jdbc.queryForObject("SELECT id::text FROM variant_price WHERE variant_id = ?", String.class, ring.variantId());
        JsonNode event = JSON.readTree(jdbc.queryForObject("SELECT payload::text FROM outbox WHERE aggregate_id = ?", String.class, priceId));
        assertThat(event.get("discountPerPiece").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(event.get("promotionId").isNull()).isTrue();
        assertThat(event.get("netPrice").decimalValue()).isEqualByComparingTo("1000.00");
    }

    @Test
    @DisplayName("promotion is asked once per save, about now: the price is judged against what applies today")
    void oneQuestionAboutNow() throws Exception {
        Piece ring = fixtures.ring("Aurelia");
        StubInventory.holds(ring.variantId(), lot("900.00", 2));
        shopWide("5.00");

        setPrice(ring.variantId(), "{\"unitPrice\":1000.00,\"expectedPriceId\":null}").andExpect(status().isOk());

        assertThat(StubPromotion.calls()).hasSize(1);
        assertThat(StubPromotion.calls().getFirst().at()).isNull();
    }

    @Test
    @DisplayName("promotion down is a 503 with Retry-After and nothing is saved: a price is never saved without knowing what a customer would pay")
    void promotionDown() throws Exception {
        Piece ring = fixtures.ring("Aurelia");
        StubInventory.holds(ring.variantId(), lot("900.00", 2));
        StubPromotion.failWith(500);

        setPrice(ring.variantId(), "{\"unitPrice\":1000.00,\"expectedPriceId\":null}")
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "5"));

        assertThat(rows(ring.variantId())).isZero();
    }

    // ---------------------------------------------------------------- viewing

    @Test
    @DisplayName("a price in force is viewed on its net: it clears the cost on the list and stands BELOW once a promotion applies, with the figures beside it")
    void theViewIsJudgedOnTheNet() throws Exception {
        Piece ring = pricedAlready("1000.00");
        StubInventory.holds(ring.variantId(), lot("900.00", 2));
        PromotionDefinition promotion = shopWide("20.00");

        view(ring.variantId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assessment").value("BELOW"))
                .andExpect(jsonPath("$.shortfall").value(100.00))
                .andExpect(jsonPath("$.discountPerPiece").value(200.00))
                .andExpect(jsonPath("$.promotionId").value(promotion.id().toString()))
                .andExpect(jsonPath("$.netPrice").value(800.00))
                .andExpect(jsonPath("$.currentPrice.unitPrice").value(1000.00));
    }

    @Test
    @DisplayName("viewed with no promotion in force the standing is the list price's: OK, 0.00 off, net equals list")
    void theViewWithNoPromotion() throws Exception {
        Piece ring = pricedAlready("1000.00");
        StubInventory.holds(ring.variantId(), lot("900.00", 2));

        view(ring.variantId())
                .andExpect(jsonPath("$.assessment").value("OK"))
                .andExpect(jsonPath("$.discountPerPiece").value(0.00))
                .andExpect(jsonPath("$.promotionId").value(nullValue()))
                .andExpect(jsonPath("$.netPrice").value(1000.00));
    }

    @Test
    @DisplayName("an unpriced piece viewed has no standing and no net price, and promotion is not asked about it")
    void theViewOfAnUnpricedPiece() throws Exception {
        Piece ring = fixtures.ring("Aurelia");
        StubInventory.holds(ring.variantId(), lot("900.00", 2));
        StubPromotion.failWith(500);

        view(ring.variantId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assessment").value(nullValue()))
                .andExpect(jsonPath("$.netPrice").value(nullValue()))
                .andExpect(jsonPath("$.discountPerPiece").value(nullValue()));

        assertThat(StubPromotion.calls()).isEmpty();
    }
}
