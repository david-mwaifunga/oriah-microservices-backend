package com.oriah.catalog.controller;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
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
import com.oriah.catalog.repository.ProductRepository;

import static com.oriah.catalog.StubInventory.lot;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Setting a price: the screen's one write. The warning never refuses, but it never lets a price below cost through unseen either:
 * saving below the dearest cost still held (plus the default box's) needs a typed reason, which is recorded with the person's
 * name, and the person must have been shown the figure they are accepting. A price is dated history: a save adds a row and
 * overwrites nothing.
 *
 * <p>Real Postgres and the real catalog; inventory is the shared {@link StubInventory}, a real HTTP server, so the caller's token
 * and the ids asked about are seen as inventory would see them.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestChannelBinderConfiguration.class})
@TestPropertySource(properties = {"spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
class PriceSettingHttpTest {

    private static final String PRICING = "/api/v1/admin/pricing/variants/";
    private static final String PROBLEMS = "https://oriah.dev/problems/";

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

    private ResultActions setPrice(UUID variant, String json) throws Exception {
        return mvc.perform(put(PRICING + variant + "/price").with(maya()).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions setPrice(UUID variant, String price, UUID expected) throws Exception {
        return setPrice(variant, "{\"unitPrice\":" + price + ",\"expectedPriceId\":" + (expected == null ? "null" : "\"" + expected + "\"") + "}");
    }

    private long rows(UUID variant) {
        return jdbc.queryForObject("SELECT count(*) FROM variant_price WHERE variant_id = ?", Long.class, variant);
    }

    private BigDecimal currentPrice(UUID variant) {
        return jdbc.queryForObject("SELECT unit_price FROM product_variants WHERE id = ?", BigDecimal.class, variant);
    }

    private UUID latestPriceId(UUID variant) {
        return jdbc.queryForObject("SELECT id FROM variant_price WHERE variant_id = ? ORDER BY effective_from DESC, seq DESC LIMIT 1", UUID.class, variant);
    }

    private long events(UUID aggregateId, String type) {
        return jdbc.queryForObject("SELECT count(*) FROM outbox WHERE aggregate_id = ? AND event_type = ?", Long.class, aggregateId.toString(), type);
    }

    // ---------------------------------------------------------------- the ordinary saves

    @Test
    @DisplayName("a first price for a piece that holds no stock is saved, and the answer says no cost is known: nothing to compare with, never 'fine'")
    void aFirstPriceWithNoStock() throws Exception {
        Piece ring = fixtures.ring("Aurelia");

        setPrice(ring.variantId(), "1500.00", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assessment").value("NO_COST_KNOWN"))
                .andExpect(jsonPath("$.price.unitPrice").value(1500.00))
                .andExpect(jsonPath("$.price.currency").value("ZMW"))
                .andExpect(jsonPath("$.price.source").value("SET"))
                .andExpect(jsonPath("$.price.assessment").value("NO_COST_KNOWN"))
                .andExpect(jsonPath("$.price.setByName").value("Maya Banda"))
                .andExpect(jsonPath("$.cost.floor").value(nullValue()))
                .andExpect(jsonPath("$.productStatus").value("DRAFT"))
                .andExpect(jsonPath("$.publishable").value(true));

        assertThat(currentPrice(ring.variantId())).isEqualByComparingTo("1500.00");
        assertThat(rows(ring.variantId())).isEqualTo(1);
    }

    @Test
    @DisplayName("what inventory is asked, and by whom: the person's own token, and the variant's own id")
    void inventoryIsAskedAsThePerson() throws Exception {
        Piece ring = fixtures.ring("Aurelia");

        setPrice(ring.variantId(), "1500.00", null).andExpect(status().isOk());

        assertThat(StubInventory.calls()).hasSize(1);
        assertThat(StubInventory.calls().get(0).authorization()).isEqualTo("Bearer token");
        assertThat(StubInventory.calls().get(0).ids()).containsExactly(ring.variantId());
    }

    @Test
    @DisplayName("a price that covers the dearest held lot is saved as OK, and the row keeps what the person was shown: the floor and the lots")
    void aPriceThatCoversTheCost() throws Exception {
        Piece ring = fixtures.ring("Aurelia");
        StubInventory.holds(ring.variantId(), lot("1528.98", 3), lot("1528.99", 4));

        setPrice(ring.variantId(), "1600.00", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assessment").value("OK"))
                .andExpect(jsonPath("$.cost.floor").value(1528.99))
                .andExpect(jsonPath("$.cost.goodsHighest").value(1528.99))
                .andExpect(jsonPath("$.cost.boxBasis").value("NONE"))
                .andExpect(jsonPath("$.cost.goods.lots", hasSize(2)));

        assertThat(jdbc.queryForObject("SELECT cost_basis->>'floor' FROM variant_price WHERE variant_id = ?", String.class, ring.variantId())).isEqualTo("1528.99");
        assertThat(jdbc.queryForObject("SELECT cost_basis->>'status' FROM variant_price WHERE variant_id = ?", String.class, ring.variantId())).isEqualTo("OK");
        assertThat(jdbc.queryForObject("SELECT jsonb_array_length(cost_basis->'lots') FROM variant_price WHERE variant_id = ?", Integer.class, ring.variantId())).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT cost_basis->>'evaluatedAt' FROM variant_price WHERE variant_id = ?", String.class, ring.variantId())).isNotBlank();
    }

    @Test
    @DisplayName("a price exactly at the dearest cost is fine")
    void equalityIsFine() throws Exception {
        Piece ring = fixtures.ring("Aurelia");
        StubInventory.holds(ring.variantId(), lot("1528.99", 4));

        setPrice(ring.variantId(), "1528.99", null).andExpect(status().isOk()).andExpect(jsonPath("$.assessment").value("OK"));
    }

    @Test
    @DisplayName("each save adds a row and overwrites nothing: three prices are three rows, and the one in force is the last")
    void historyGrows() throws Exception {
        Piece ring = fixtures.ring("Aurelia");

        setPrice(ring.variantId(), "1500.00", null).andExpect(status().isOk());
        setPrice(ring.variantId(), "1600.00", latestPriceId(ring.variantId())).andExpect(status().isOk());
        setPrice(ring.variantId(), "1700.00", latestPriceId(ring.variantId())).andExpect(status().isOk());

        assertThat(rows(ring.variantId())).isEqualTo(3);
        assertThat(currentPrice(ring.variantId())).isEqualByComparingTo("1700.00");
        assertThat(jdbc.queryForList("SELECT unit_price FROM variant_price WHERE variant_id = ? ORDER BY seq", BigDecimal.class, ring.variantId()))
                .extracting(BigDecimal::toPlainString).containsExactly("1500.00", "1600.00", "1700.00");
    }

    // ---------------------------------------------------------------- the warning

    @Test
    @DisplayName("below the dearest cost with no reason is a warning and nothing else: 409 with every figure, and nothing is saved or announced")
    void belowCostWithoutAReasonIsAWarning() throws Exception {
        Piece ring = fixtures.ring("Aurelia");
        StubInventory.holds(ring.variantId(), lot("1528.98", 3), lot("1528.99", 4));

        setPrice(ring.variantId(), "1528.98", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(PROBLEMS + "below-landed-cost"))
                .andExpect(jsonPath("$.assessment").value("BELOW"))
                .andExpect(jsonPath("$.floor").value(1528.99))
                .andExpect(jsonPath("$.shortfall").value(0.01))
                .andExpect(jsonPath("$.cost.goodsHighest").value(1528.99))
                .andExpect(jsonPath("$.cost.goods.averageUnitCost").value(1528.99))
                .andExpect(jsonPath("$.cost.goods.lots", hasSize(2)));

        assertThat(rows(ring.variantId())).isZero();
        assertThat(currentPrice(ring.variantId())).isEqualByComparingTo("0.00");
        assertThat(events(ring.productId(), "product.updated")).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox WHERE aggregate_type = 'variant_price' AND payload::text LIKE ?", Long.class, "%" + ring.variantId() + "%")).isZero();
    }

    @Test
    @DisplayName("below cost with a reason, for the floor the person was shown, is saved: the row and the event carry the reason, the name and the figures")
    void belowCostWithAReasonIsSavedAndRecorded() throws Exception {
        Piece ring = fixtures.ring("Aurelia");
        StubInventory.holds(ring.variantId(), lot("1528.99", 4));

        setPrice(ring.variantId(), "{\"unitPrice\":1400.00,\"expectedPriceId\":null,\"belowCostReason\":\"clearance of the old range\",\"acknowledgedFloor\":1528.99}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assessment").value("BELOW"))
                .andExpect(jsonPath("$.price.reason").value("clearance of the old range"))
                .andExpect(jsonPath("$.price.setBy").value("user:kc-maya"))
                .andExpect(jsonPath("$.price.setByName").value("Maya Banda"));

        assertThat(currentPrice(ring.variantId())).isEqualByComparingTo("1400.00");
        assertThat(jdbc.queryForObject("SELECT assessment FROM variant_price WHERE variant_id = ?", String.class, ring.variantId())).isEqualTo("BELOW");
        UUID priceId = latestPriceId(ring.variantId());
        assertThat(jdbc.queryForMap("SELECT topic, actor, payload::text AS payload, event_type FROM outbox WHERE aggregate_type = 'variant_price' AND aggregate_id = ?", priceId.toString()))
                .satisfies(event -> {
                    assertThat(event.get("topic")).isEqualTo("catalog.price");
                    assertThat(event.get("event_type")).isEqualTo("price.set");
                    assertThat(event.get("actor")).isEqualTo("user:kc-maya");
                    String payload = (String) event.get("payload");
                    assertThat(payload).contains("clearance of the old range").contains("Maya Banda").contains("BELOW")
                            .contains(ring.variantId().toString()).contains("1528.99").contains("1400.00");
                });
        assertThat(events(ring.productId(), "product.updated")).isEqualTo(1);
    }

    @Test
    @DisplayName("a reason typed against a floor that has since gone up is not accepted: the person is shown the new figures first")
    void aReasonIsNotAcceptedAgainstFiguresTheyDidNotSee() throws Exception {
        Piece ring = fixtures.ring("Aurelia");
        StubInventory.holds(ring.variantId(), lot("1600.00", 4));   // it was 1528.99 when they read it

        setPrice(ring.variantId(), "{\"unitPrice\":1400.00,\"expectedPriceId\":null,\"belowCostReason\":\"clearance of the old range\",\"acknowledgedFloor\":1528.99}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(PROBLEMS + "below-landed-cost"))
                .andExpect(jsonPath("$.floor").value(1600.00));

        assertThat(rows(ring.variantId())).isZero();
    }

    @Test
    @DisplayName("a reason with no floor acknowledged is not accepted either: what the person saw has to be said")
    void aReasonWithoutTheFloorIsNotAccepted() throws Exception {
        Piece ring = fixtures.ring("Aurelia");
        StubInventory.holds(ring.variantId(), lot("1528.99", 4));

        setPrice(ring.variantId(), "{\"unitPrice\":1400.00,\"expectedPriceId\":null,\"belowCostReason\":\"clearance of the old range\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(PROBLEMS + "below-landed-cost"));
    }

    @Test
    @DisplayName("a reason must be five to 200 characters: 'ok' and a page of text are both refused, and nothing is saved")
    void theReasonHasALength() throws Exception {
        Piece ring = fixtures.ring("Aurelia");
        StubInventory.holds(ring.variantId(), lot("1528.99", 4));

        setPrice(ring.variantId(), "{\"unitPrice\":1400.00,\"expectedPriceId\":null,\"belowCostReason\":\"ok\",\"acknowledgedFloor\":1528.99}")
                .andExpect(status().isBadRequest());
        setPrice(ring.variantId(), "{\"unitPrice\":1400.00,\"expectedPriceId\":null,\"belowCostReason\":\"" + "x".repeat(201) + "\",\"acknowledgedFloor\":1528.99}")
                .andExpect(status().isBadRequest());

        assertThat(rows(ring.variantId())).isZero();
    }

    @Test
    @DisplayName("the reason's length limits are exact: 4 characters refused, 5 and 200 accepted, 201 refused (400, nothing saved)")
    void theReasonBoundaries() throws Exception {
        Piece ring = fixtures.ring("Aurelia");
        StubInventory.holds(ring.variantId(), lot("1528.99", 4));
        String floor = "\"acknowledgedFloor\":1528.99";

        setPrice(ring.variantId(), "{\"unitPrice\":1400.00,\"expectedPriceId\":null,\"belowCostReason\":\"abcd\"," + floor + "}").andExpect(status().isBadRequest());
        setPrice(ring.variantId(), "{\"unitPrice\":1400.00,\"expectedPriceId\":null,\"belowCostReason\":\"" + "x".repeat(201) + "\"," + floor + "}").andExpect(status().isBadRequest());
        assertThat(rows(ring.variantId())).isZero();
        setPrice(ring.variantId(), "{\"unitPrice\":1400.00,\"expectedPriceId\":null,\"belowCostReason\":\"abcde\"," + floor + "}").andExpect(status().isOk());
        setPrice(ring.variantId(), "{\"unitPrice\":1300.00,\"expectedPriceId\":\"" + latestPriceId(ring.variantId()) + "\",\"belowCostReason\":\"" + "y".repeat(200) + "\"," + floor + "}")
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("two people pricing one piece at once: the one who loses the race is refused AFTER the cost was read, by the check under the lock, and nothing of theirs is saved")
    void theCheckUnderTheLockStopsTheSecondSave() throws Exception {
        Piece ring = fixtures.ring("Aurelia");
        // while inventory is being asked (no lock held), someone else saves the first price
        StubInventory.beforeAnswering(() -> jdbc.update("""
                INSERT INTO variant_price (id, variant_id, unit_price, currency, effective_from, recorded_at, set_by, source, assessment)
                VALUES (gen_random_uuid(), ?, 1111.00, 'ZMW', now(), now(), 'user:kc-other', 'SET', 'NO_COST_KNOWN')""", ring.variantId()));

        setPrice(ring.variantId(), "1500.00", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(PROBLEMS + "stale-price"));

        assertThat(rows(ring.variantId())).as("only the other person's price is there").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT set_by FROM variant_price WHERE variant_id = ?", String.class, ring.variantId())).isEqualTo("user:kc-other");
        assertThat(currentPrice(ring.variantId())).as("and the variant's price was not overwritten").isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("a legacy piece priced in dollars is not silently moved to Kwacha by a request that does not say so: omitting the currency is 400, saying ZMW moves it")
    void aLegacyDollarPieceMustSayZmw() throws Exception {
        Piece watch = fixtures.ring("Dollar watch");
        jdbc.update("UPDATE product_variants SET currency = 'USD', unit_price = 100.00 WHERE id = ?", watch.variantId());
        jdbc.update("""
                INSERT INTO variant_price (id, variant_id, unit_price, currency, effective_from, recorded_at, set_by, source, assessment)
                VALUES (gen_random_uuid(), ?, 100.00, 'USD', now() - interval '2 days', now(), 'system:migration', 'MIGRATED', 'NOT_ASSESSED')""", watch.variantId());
        UUID migrated = latestPriceId(watch.variantId());

        setPrice(watch.variantId(), "{\"unitPrice\":120.00,\"expectedPriceId\":\"" + migrated + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(PROBLEMS + "unsupported-currency"));
        assertThat(jdbc.queryForObject("SELECT currency FROM product_variants WHERE id = ?", String.class, watch.variantId())).isEqualTo("USD");

        setPrice(watch.variantId(), "{\"unitPrice\":1500.00,\"currency\":\"ZMW\",\"expectedPriceId\":\"" + migrated + "\"}").andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT currency FROM product_variants WHERE id = ?", String.class, watch.variantId())).isEqualTo("ZMW");
    }

    @Test
    @DisplayName("a legacy dollar piece can be moved to Kwacha at the SAME number: 100.00 USD to an explicit 100.00 ZMW is a change of currency, not 'nothing changed'; asking again is")
    void movingTheCurrencyAtTheSameNumberIsAChange() throws Exception {
        Piece watch = fixtures.ring("Dollar watch");
        jdbc.update("UPDATE product_variants SET currency = 'USD', unit_price = 100.00 WHERE id = ?", watch.variantId());
        jdbc.update("""
                INSERT INTO variant_price (id, variant_id, unit_price, currency, effective_from, recorded_at, set_by, source, assessment)
                VALUES (gen_random_uuid(), ?, 100.00, 'USD', now() - interval '2 days', now(), 'system:migration', 'MIGRATED', 'NOT_ASSESSED')""", watch.variantId());
        UUID migrated = latestPriceId(watch.variantId());

        setPrice(watch.variantId(), "{\"unitPrice\":100.00,\"currency\":\"ZMW\",\"expectedPriceId\":\"" + migrated + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price.currency").value("ZMW"));
        assertThat(jdbc.queryForObject("SELECT currency FROM product_variants WHERE id = ?", String.class, watch.variantId())).isEqualTo("ZMW");
        assertThat(rows(watch.variantId())).as("the migrated row and the move").isEqualTo(2);

        UUID moved = latestPriceId(watch.variantId());
        setPrice(watch.variantId(), "{\"unitPrice\":100.00,\"currency\":\"ZMW\",\"expectedPriceId\":\"" + moved + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(PROBLEMS + "nothing-changed"));
        assertThat(rows(watch.variantId())).as("a repeat of what is already so writes nothing").isEqualTo(2);
    }

    @Test
    @DisplayName("a reason padded with spaces is judged by what is left: '  abc  ' is three characters, so 400, not a database refusal reported as a conflict")
    void theReasonIsJudgedTrimmed() throws Exception {
        Piece ring = fixtures.ring("Aurelia");
        StubInventory.holds(ring.variantId(), lot("1528.99", 4));

        setPrice(ring.variantId(), "{\"unitPrice\":1400.00,\"expectedPriceId\":null,\"belowCostReason\":\"  abc  \",\"acknowledgedFloor\":1528.99}")
                .andExpect(status().isBadRequest());
        setPrice(ring.variantId(), "{\"unitPrice\":1400.00,\"expectedPriceId\":null,\"belowCostReason\":\"  clearance  \",\"acknowledgedFloor\":1528.99}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price.reason").value("clearance"));
        assertThat(rows(ring.variantId())).isEqualTo(1);
    }

    @Test
    @DisplayName("the default box is part of what a sale costs: a ring that clears its own cost but not cost plus box is warned about, and both are asked of inventory in one call")
    void theDefaultBoxIsCounted() throws Exception {
        Piece ring = fixtures.ring("Aurelia");
        Piece box = fixtures.box("Gift box");
        fixtures.defaultBox(ring.productId(), box.variantId());
        StubInventory.holds(ring.variantId(), lot("1000.00", 2));
        StubInventory.holds(box.variantId(), lot("78.98", 90), lot("78.99", 110));

        setPrice(ring.variantId(), "1050.00", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.floor").value(1078.99))
                .andExpect(jsonPath("$.shortfall").value(28.99))
                .andExpect(jsonPath("$.cost.boxBasis").value("COUNTED"))
                .andExpect(jsonPath("$.cost.boxHighest").value(78.99))
                .andExpect(jsonPath("$.cost.box.lots", hasSize(2)));

        assertThat(StubInventory.calls()).hasSize(1);
        assertThat(StubInventory.calls().get(0).ids()).containsExactlyInAnyOrder(ring.variantId(), box.variantId());
    }

    @Test
    @DisplayName("a change to the default box that lands while the cost is being read is refused as a conflict, not saved against the old box")
    void aBoxChangedDuringTheCheckIsRefused() throws Exception {
        Piece ring = fixtures.ring("Aurelia");
        Piece cheap = fixtures.box("Cheap pouch");
        Piece dear = fixtures.box("Dear box");
        fixtures.defaultBox(ring.productId(), cheap.variantId());
        StubInventory.holds(ring.variantId(), lot("1000.00", 2));
        StubInventory.holds(cheap.variantId(), lot("5.00", 10));
        StubInventory.holds(dear.variantId(), lot("300.00", 10));
        StubInventory.beforeAnswering(() ->
                jdbc.update("UPDATE products SET default_packaging_variant_id = ? WHERE id = ?", dear.variantId(), ring.productId()));

        setPrice(ring.variantId(), "1010.00", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(PROBLEMS + "concurrent-update"));

        assertThat(rows(ring.variantId())).as("it was not saved against the cheap pouch it was checked with").isZero();
    }

    // ---------------------------------------------------------------- what may be priced, and in what

    @Test
    @DisplayName("prices are in Kwacha: any other currency is refused, and an omitted one means ZMW")
    void onlyZmw() throws Exception {
        Piece ring = fixtures.ring("Aurelia");

        setPrice(ring.variantId(), "{\"unitPrice\":15.00,\"currency\":\"USD\",\"expectedPriceId\":null}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(PROBLEMS + "unsupported-currency"));
        assertThat(rows(ring.variantId())).isZero();

        setPrice(ring.variantId(), "{\"unitPrice\":1500.00,\"currency\":\"zmw\",\"expectedPriceId\":null}").andExpect(status().isOk());
    }

    @Test
    @DisplayName("a price is above zero and to the ngwee: zero, negative and three decimals are 400")
    void aPriceIsMoney() throws Exception {
        Piece ring = fixtures.ring("Aurelia");

        setPrice(ring.variantId(), "0.00", null).andExpect(status().isBadRequest());
        setPrice(ring.variantId(), "-5.00", null).andExpect(status().isBadRequest());
        setPrice(ring.variantId(), "10.005", null).andExpect(status().isBadRequest());
        setPrice(ring.variantId(), "{\"expectedPriceId\":null}").andExpect(status().isBadRequest());
        assertThat(rows(ring.variantId())).isZero();
    }

    @Test
    @DisplayName("packaging is never priced: 409, whatever the figure")
    void packagingIsNotPriced() throws Exception {
        Piece box = fixtures.box("Gift box");

        setPrice(box.variantId(), "10.00", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(PROBLEMS + "packaging-is-not-priced"));
        assertThat(rows(box.variantId())).isZero();
    }

    @Test
    @DisplayName("a variant that does not exist, or whose product was deleted, is 404")
    void notFound() throws Exception {
        setPrice(UUID.randomUUID(), "10.00", null).andExpect(status().isNotFound());

        Piece ring = fixtures.ring("Aurelia");
        jdbc.update("UPDATE products SET is_deleted = TRUE WHERE id = ?", ring.productId());
        setPrice(ring.variantId(), "10.00", null).andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------- two people at once

    @Test
    @DisplayName("the price the person saw has to still be the price: a stale token is 409, and null means 'it was unpriced'")
    void aStalePriceIsRefused() throws Exception {
        Piece ring = fixtures.ring("Aurelia");
        setPrice(ring.variantId(), "1500.00", null).andExpect(status().isOk());
        UUID first = latestPriceId(ring.variantId());

        setPrice(ring.variantId(), "1600.00", null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.type").value(PROBLEMS + "stale-price"));
        setPrice(ring.variantId(), "1600.00", UUID.randomUUID())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.type").value(PROBLEMS + "stale-price"));
        setPrice(ring.variantId(), "1600.00", first).andExpect(status().isOk());

        assertThat(rows(ring.variantId())).isEqualTo(2);
    }

    @Test
    @DisplayName("saving the price it already has is 400, so a double click or a retry adds no row")
    void nothingChanged() throws Exception {
        Piece ring = fixtures.ring("Aurelia");
        setPrice(ring.variantId(), "1500.00", null).andExpect(status().isOk());

        setPrice(ring.variantId(), "1500.00", latestPriceId(ring.variantId()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(PROBLEMS + "nothing-changed"));
        assertThat(rows(ring.variantId())).isEqualTo(1);
    }

    // ---------------------------------------------------------------- dating

    @Test
    @DisplayName("a paper tag changed this morning and keyed tonight: a price may start earlier, within 72 hours, and the row says when it was keyed")
    void aPriceMayStartEarlier() throws Exception {
        Piece ring = fixtures.ring("Aurelia");
        Instant morning = Instant.now().minus(10, ChronoUnit.HOURS);

        setPrice(ring.variantId(), "{\"unitPrice\":1500.00,\"expectedPriceId\":null,\"effectiveFrom\":\"" + morning + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price.effectiveFrom").exists());

        Map<String, Object> row = jdbc.queryForMap("SELECT effective_from, recorded_at FROM variant_price WHERE variant_id = ?", ring.variantId());
        Instant effective = ((java.sql.Timestamp) row.get("effective_from")).toInstant();
        Instant recorded = ((java.sql.Timestamp) row.get("recorded_at")).toInstant();
        assertThat(effective).isCloseTo(morning, org.assertj.core.api.Assertions.within(1, ChronoUnit.SECONDS));
        assertThat(recorded).isAfter(effective.plus(9, ChronoUnit.HOURS));
    }

    @Test
    @DisplayName("a date more than 72 hours back, in the future, or before the price it follows is refused, each by name")
    void theDatingRules() throws Exception {
        Piece ring = fixtures.ring("Aurelia");
        setPrice(ring.variantId(), "1500.00", null).andExpect(status().isOk());
        UUID first = latestPriceId(ring.variantId());

        for (String[] bad : new String[][] {
                {Instant.now().minus(100, ChronoUnit.HOURS).toString(), "effective-date-too-old"},
                {Instant.now().plus(1, ChronoUnit.HOURS).toString(), "effective-date-in-the-future"},
                {Instant.now().minus(1, ChronoUnit.HOURS).toString(), "effective-date-before-latest-price"}}) {
            setPrice(ring.variantId(), "{\"unitPrice\":1600.00,\"expectedPriceId\":\"" + first + "\",\"effectiveFrom\":\"" + bad[0] + "\"}")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value(PROBLEMS + bad[1]));
        }
        assertThat(rows(ring.variantId())).isEqualTo(1);
    }

    // ---------------------------------------------------------------- the owner's historical price (Phase 9)

    private static RequestPostProcessor owner() {
        return jwt().jwt(j -> j.subject("kc-owner").claim("name", "Owner"))
                .authorities(new SimpleGrantedAuthority("ROLE_CATALOG_ADMIN"), new SimpleGrantedAuthority("ROLE_COST_VIEWER"), new SimpleGrantedAuthority("ROLE_BACKDATE_SALE"));
    }

    private ResultActions setPriceAs(RequestPostProcessor who, UUID variant, String json) throws Exception {
        return mvc.perform(put(PRICING + variant + "/price").with(who).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    @Test
    @DisplayName("the person who may back-date a sale can add a price from BEFORE the price in force, with a reason: today's price is untouched, the row is kept with its reason, and the as-of read prices a sale of that day")
    void theOwnersHistoricalPrice() throws Exception {
        Piece ring = fixtures.ring("Aurelia");
        setPrice(ring.variantId(), "1500.00", null).andExpect(status().isOk());
        UUID first = latestPriceId(ring.variantId());
        Instant tenDaysAgo = Instant.now().minus(10, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MICROS);

        setPriceAs(owner(), ring.variantId(), "{\"unitPrice\":1300.00,\"expectedPriceId\":\"" + first + "\",\"effectiveFrom\":\"" + tenDaysAgo + "\","
                + "\"backdateReason\":\"the ring was sold on paper before it was priced here\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price.unitPrice").value(1300.00));

        assertThat(currentPrice(ring.variantId())).as("today's price is untouched").isEqualByComparingTo("1500.00");
        assertThat(latestPriceId(ring.variantId())).as("the price in force is still the first row").isEqualTo(first);
        assertThat(rows(ring.variantId())).isEqualTo(2);
        Map<String, Object> row = jdbc.queryForMap("SELECT effective_from, backdate_reason FROM variant_price WHERE variant_id = ? AND unit_price = 1300.00", ring.variantId());
        assertThat(((java.sql.Timestamp) row.get("effective_from")).toInstant()).isEqualTo(tenDaysAgo);
        assertThat(row.get("backdate_reason")).isEqualTo("the ring was sold on paper before it was priced here");
        assertThat(jdbc.queryForObject("SELECT backdate_reason FROM variant_price WHERE id = ?", String.class, first)).isNull();
        // a sale of that day is priced at that day's price (the staff as-of read), and a sale of today at today's
        RequestPostProcessor sales = jwt().jwt(j -> j.subject("kc-sam")).authorities(new SimpleGrantedAuthority("ROLE_SALES_AGENT"));
        String atTenDays = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/admin/variants/pricing-as-of").with(sales)
                .contentType(MediaType.APPLICATION_JSON).content("{\"at\":\"" + tenDaysAgo.plus(1, ChronoUnit.HOURS) + "\",\"variantIds\":[\"" + ring.variantId() + "\"]}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(atTenDays).contains("\"PRICED\"").contains("1300.00");
        String today = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/admin/variants/pricing-as-of").with(sales)
                .contentType(MediaType.APPLICATION_JSON).content("{\"at\":\"" + Instant.now() + "\",\"variantIds\":[\"" + ring.variantId() + "\"]}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(today).contains("1500.00");
    }

    @Test
    @DisplayName("a historical price is judged on the promotion of ITS day, not today's, and is kept with its reason: the response shows the reason, the price.set event carries it and names no 'previous price' (there is none to change from)")
    void aHistoricalPriceBelongsToItsOwnDay() throws Exception {
        StubPromotion.reset();
        try {
            Piece ring = fixtures.ring("Aurelia");
            setPrice(ring.variantId(), "1500.00", null).andExpect(status().isOk());
            UUID first = latestPriceId(ring.variantId());
            Instant tenDaysAgo = Instant.now().minus(10, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MICROS);
            // a half-price promotion that began an hour ago: it did not exist on the day the historical price belongs to
            StubPromotion.promote(StubPromotion.definition("SHOP", null, "PERCENTAGE", "50.00", "2026-11-01"), Instant.now().minus(1, ChronoUnit.HOURS),
                    Instant.parse("2999-01-01T00:00:00Z"));

            setPriceAs(owner(), ring.variantId(), "{\"unitPrice\":1300.00,\"expectedPriceId\":\"" + first + "\",\"effectiveFrom\":\"" + tenDaysAgo + "\","
                    + "\"backdateReason\":\"the ring was sold on paper before it was priced here\"}")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.discountPerPiece").value(0.0))
                    .andExpect(jsonPath("$.netPrice").value(1300.00))
                    .andExpect(jsonPath("$.price.backdateReason").value("the ring was sold on paper before it was priced here"));

            assertThat(StubPromotion.calls().stream().anyMatch(c -> c.at() != null && c.at().startsWith(tenDaysAgo.toString().substring(0, 16)))).as("promotion was asked about that day").isTrue();
            String priceId = jdbc.queryForObject("SELECT id::text FROM variant_price WHERE variant_id = ? AND unit_price = 1300.00", String.class, ring.variantId());
            String event = jdbc.queryForObject("SELECT payload::text FROM outbox WHERE aggregate_type = 'variant_price' AND aggregate_id = ?", String.class, priceId);
            assertThat(event).contains("\"backdateReason\":\"the ring was sold on paper before it was priced here\"").doesNotContain("\"previousUnitPrice\":1");
            String ordinary = jdbc.queryForObject("SELECT payload::text FROM outbox WHERE aggregate_type = 'variant_price' AND aggregate_id = ?", String.class, first.toString());
            assertThat(ordinary).as("an ordinary price's event is unchanged").doesNotContain("backdateReason");
        } finally {
            StubPromotion.reset();
        }
    }

    @Test
    @DisplayName("a historical price needs the capability AND a reason: without the capability the old refusals stand; with it and no reason it is 400 backdate-reason-required; nothing is saved")
    void theHistoricalPriceNeedsTheCapabilityAndAReason() throws Exception {
        Piece ring = fixtures.ring("Aurelia");
        setPrice(ring.variantId(), "1500.00", null).andExpect(status().isOk());
        UUID first = latestPriceId(ring.variantId());
        String tenDaysAgo = Instant.now().minus(10, ChronoUnit.DAYS).toString();
        String body = "{\"unitPrice\":1300.00,\"expectedPriceId\":\"" + first + "\",\"effectiveFrom\":\"" + tenDaysAgo + "\"";

        setPrice(ring.variantId(), body + ",\"backdateReason\":\"the ring was sold on paper before it was priced\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.type").value(PROBLEMS + "effective-date-too-old"));
        setPriceAs(owner(), ring.variantId(), body + "}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.type").value(PROBLEMS + "backdate-reason-required"));
        setPriceAs(owner(), ring.variantId(), body + ",\"backdateReason\":\"four\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.type").value(PROBLEMS + "backdate-reason-required"));
        assertThat(rows(ring.variantId())).isEqualTo(1);
    }

    @Test
    @DisplayName("the capability does not let anyone rewrite today's price: a date after the latest row but older than 72 hours is still too old, even for the owner")
    void theOwnerCannotRewriteTheLivePrice() throws Exception {
        Piece ring = fixtures.ring("Aurelia");
        setPrice(ring.variantId(), "1500.00", null).andExpect(status().isOk());
        // the latest row is today's; a date 100 hours back is BEFORE it, so it is historical; to be "after the latest but too old" the latest must itself be old.
        jdbc.execute("ALTER TABLE variant_price DISABLE TRIGGER USER");
        try {
            jdbc.update("UPDATE variant_price SET effective_from = now() - interval '20 days' WHERE variant_id = ?", ring.variantId());
        } finally {
            jdbc.execute("ALTER TABLE variant_price ENABLE TRIGGER USER");
        }
        UUID first = latestPriceId(ring.variantId());

        setPriceAs(owner(), ring.variantId(), "{\"unitPrice\":1300.00,\"expectedPriceId\":\"" + first + "\",\"effectiveFrom\":\"" + Instant.now().minus(10, ChronoUnit.DAYS)
                + "\",\"backdateReason\":\"the ring was sold on paper before it was priced here\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.type").value(PROBLEMS + "effective-date-too-old"));
        assertThat(rows(ring.variantId())).isEqualTo(1);
    }

    // ---------------------------------------------------------------- inventory unavailable

    @Test
    @DisplayName("inventory failing is 503 with Retry-After, and nothing is saved: a price is never saved without the cost having been looked at")
    void inventoryDown() throws Exception {
        Piece ring = fixtures.ring("Aurelia");
        StubInventory.failWith(500);

        setPrice(ring.variantId(), "1500.00", null)
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().exists("Retry-After"));

        assertThat(rows(ring.variantId())).isZero();
        assertThat(currentPrice(ring.variantId())).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("inventory refusing the token is 502, not a sign-in problem for the browser and not 'try again'")
    void inventoryRefuses() throws Exception {
        Piece ring = fixtures.ring("Aurelia");
        StubInventory.failWith(403);

        setPrice(ring.variantId(), "1500.00", null).andExpect(status().isBadGateway());
        assertThat(rows(ring.variantId())).isZero();
    }

    // ---------------------------------------------------------------- who

    @Test
    @DisplayName("setting a price needs CATALOG_ADMIN and COST_VIEWER both: either alone is refused, and nobody is 401")
    void whoMaySet() throws Exception {
        Piece ring = fixtures.ring("Aurelia");
        String body = "{\"unitPrice\":1500.00,\"expectedPriceId\":null}";

        for (String[] roles : new String[][] {{"ROLE_CATALOG_ADMIN"}, {"ROLE_COST_VIEWER"}, {"ROLE_INVENTORY_ADMIN"}, {"ROLE_USER"}}) {
            java.util.List<org.springframework.security.core.GrantedAuthority> authorities =
                    java.util.Arrays.stream(roles).<org.springframework.security.core.GrantedAuthority>map(SimpleGrantedAuthority::new).toList();
            mvc.perform(put(PRICING + ring.variantId() + "/price").with(jwt().authorities(authorities))
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        }
        mvc.perform(put(PRICING + ring.variantId() + "/price").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        assertThat(StubInventory.calls()).as("a refused caller costs inventory nothing").isEmpty();
        assertThat(rows(ring.variantId())).isZero();
    }

    @Test
    @DisplayName("the current price on the variant always equals the latest history row")
    void currentEqualsLatest() throws Exception {
        Piece ring = fixtures.ring("Aurelia");
        UUID token = null;
        for (String price : List.of("1500.00", "1450.00", "1800.00")) {
            StubInventory.holds(ring.variantId(), lot("1000.00", 1));
            setPrice(ring.variantId(), price, token).andExpect(status().isOk());
            token = latestPriceId(ring.variantId());
            assertThat(currentPrice(ring.variantId())).isEqualByComparingTo(
                    jdbc.queryForObject("SELECT unit_price FROM variant_price WHERE id = ?", BigDecimal.class, token));
        }
    }
}
