package com.oriah.catalog.controller;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Where every price stands against the cost: the pieces that are below it, with no cost known, or in the wrong currency. The
 * answer is computed from the stock held NOW at every read, so it needs no trigger: a dearer lot received, a cost corrected upward
 * (the expected case, since opening stock is keyed from estimates and corrected later), a dear lot sold out of, a changed default
 * box, all show up the next time anyone looks.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestChannelBinderConfiguration.class})
@TestPropertySource(properties = {"spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
class BelowCostListHttpTest {

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
        PricingFixtures.wipeCatalogue(jdbc);
        fixtures = new PricingFixtures(products);
    }

    private static RequestPostProcessor maya() {
        return jwt().jwt(j -> j.subject("kc-maya")).authorities(new SimpleGrantedAuthority("ROLE_CATALOG_ADMIN"), new SimpleGrantedAuthority("ROLE_COST_VIEWER"));
    }

    private Piece priced(String title, String price) {
        Piece piece = fixtures.ring(title);
        PricingFixtures.priceDirectly(jdbc, piece.variantId(), price);
        return piece;
    }

    @Test
    @DisplayName("below cost, biggest shortfall first: a ring priced K528.99 under its cost heads one that is K28.99 under once its box is counted")
    void belowCostBiggestShortfallFirst() throws Exception {
        Piece far = priced("Far below", "1000.00");
        StubInventory.holds(far.variantId(), lot("1528.99", 4));
        Piece boxed = priced("Boxed", "1050.00");
        Piece box = fixtures.box("Gift box");
        fixtures.defaultBox(boxed.productId(), box.variantId());
        StubInventory.holds(boxed.variantId(), lot("1000.00", 2));
        StubInventory.holds(box.variantId(), lot("78.99", 110));
        Piece fine = priced("Fine", "2000.00");
        StubInventory.holds(fine.variantId(), lot("1000.00", 2));

        mvc.perform(get(LIST).with(maya()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].variantId").value(far.variantId().toString()))
                .andExpect(jsonPath("$.items[0].shortfall").value(528.99))
                .andExpect(jsonPath("$.items[0].floor").value(1528.99))
                .andExpect(jsonPath("$.items[0].assessment").value("BELOW"))
                .andExpect(jsonPath("$.items[1].variantId").value(boxed.variantId().toString()))
                .andExpect(jsonPath("$.items[1].shortfall").value(28.99))
                .andExpect(jsonPath("$.items[1].boxHighest").value(78.99))
                .andExpect(jsonPath("$.items[1].boxBasis").value("COUNTED"))
                .andExpect(jsonPath("$.totalItems").value(2));
    }

    @Test
    @DisplayName("the counts say how many stand at each assessment whatever the filter, so a badge needs no second call")
    void counts() throws Exception {
        Piece below = priced("Below", "100.00");
        StubInventory.holds(below.variantId(), lot("500.00", 1));
        Piece fine = priced("Fine", "900.00");
        StubInventory.holds(fine.variantId(), lot("500.00", 1));
        priced("Blind", "300.00");   // priced, nothing held

        mvc.perform(get(LIST).with(maya()))
                .andExpect(jsonPath("$.counts.BELOW").value(1))
                .andExpect(jsonPath("$.counts.OK").value(1))
                .andExpect(jsonPath("$.counts.NO_COST_KNOWN").value(1))
                .andExpect(jsonPath("$.counts.CURRENCY_MISMATCH").value(0));
        mvc.perform(get(LIST).with(maya()).param("status", "NO_COST_KNOWN"))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].productTitle").value(org.hamcrest.Matchers.startsWith("Blind")))
                .andExpect(jsonPath("$.items[0].floor").value(org.hamcrest.Matchers.nullValue()));
        mvc.perform(get(LIST).with(maya()).param("status", "ALL")).andExpect(jsonPath("$.items", hasSize(3)));
        mvc.perform(get(LIST).with(maya()).param("status", "OK")).andExpect(jsonPath("$.items", hasSize(1)));
    }

    @Test
    @DisplayName("the plan's case: opening stock keyed as an estimate, priced above it, then corrected upward past the price, raises the warning")
    void anUpwardCorrectionOnAnEstimatedLotRaisesTheWarning() throws Exception {
        Piece ring = priced("Opening stock ring", "1200.00");
        StubInventory.holds(ring.variantId(), estimatedLot("1000.00", 7));
        mvc.perform(get(LIST).with(maya())).andExpect(jsonPath("$.items", hasSize(0))).andExpect(jsonPath("$.counts.OK").value(1));

        StubInventory.holds(ring.variantId(), estimatedLot("2142.86", 7));   // the courier's real bill was dearer than the book said

        mvc.perform(get(LIST).with(maya()))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].variantId").value(ring.variantId().toString()))
                .andExpect(jsonPath("$.items[0].floor").value(2142.86))
                .andExpect(jsonPath("$.items[0].shortfall").value(942.86))
                .andExpect(jsonPath("$.items[0].estimated").value(true));
    }

    @Test
    @DisplayName("it clears again when the dear lot is sold out of: the list is the cost as it is now, not the worst it ever was")
    void itClearsWhenTheDearLotIsGone() throws Exception {
        Piece ring = priced("Ring", "1200.00");
        StubInventory.holds(ring.variantId(), lot("1000.00", 3), lot("1500.00", 2));
        mvc.perform(get(LIST).with(maya())).andExpect(jsonPath("$.items", hasSize(1)));

        StubInventory.holds(ring.variantId(), lot("1000.00", 3));   // the two dear ones sold

        mvc.perform(get(LIST).with(maya())).andExpect(jsonPath("$.items", hasSize(0)));
    }

    @Test
    @DisplayName("only what is on offer is listed: an unpriced variant, an inactive one, a deleted product and packaging are not")
    void onlyWhatIsOnOffer() throws Exception {
        Piece listed = priced("Listed", "100.00");
        StubInventory.holds(listed.variantId(), lot("500.00", 1));
        Piece unpriced = fixtures.ring("Unpriced");
        StubInventory.holds(unpriced.variantId(), lot("500.00", 1));
        Piece inactive = priced("Inactive", "100.00");
        StubInventory.holds(inactive.variantId(), lot("500.00", 1));
        jdbc.update("UPDATE product_variants SET active = FALSE WHERE id = ?", inactive.variantId());
        Piece deleted = priced("Deleted", "100.00");
        StubInventory.holds(deleted.variantId(), lot("500.00", 1));
        jdbc.update("UPDATE products SET is_deleted = TRUE WHERE id = ?", deleted.productId());
        Piece box = fixtures.box("Gift box");
        StubInventory.holds(box.variantId(), lot("50.00", 1));

        mvc.perform(get(LIST).with(maya()).param("status", "ALL"))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].variantId").value(listed.variantId().toString()));
    }

    @Test
    @DisplayName("a legacy piece priced in dollars over Kwacha lots is CURRENCY_MISMATCH in the list and the view: never fine, never 'no cost known'")
    void aDollarPriceIsAMismatch() throws Exception {
        Piece watch = priced("Dollar watch", "100.00");
        jdbc.update("UPDATE product_variants SET currency = 'USD' WHERE id = ?", watch.variantId());
        // What such a piece really looks like: V6 gave every price that existed before history one MIGRATED row, in the currency it had.
        // (A price with no history row at all cannot occur: the row and the current price are written in one transaction.)
        jdbc.update("""
                INSERT INTO variant_price (id, variant_id, unit_price, currency, effective_from, recorded_at, set_by, source, assessment)
                VALUES (gen_random_uuid(), ?, 100.00, 'USD', now(), now(), 'system:migration', 'MIGRATED', 'NOT_ASSESSED')""", watch.variantId());
        StubInventory.holds(watch.variantId(), lot("500.00", 1));

        mvc.perform(get(LIST).with(maya()).param("status", "CURRENCY_MISMATCH"))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].variantId").value(watch.variantId().toString()))
                .andExpect(jsonPath("$.items[0].currency").value("USD"))
                .andExpect(jsonPath("$.items[0].floor").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.counts.CURRENCY_MISMATCH").value(1))
                .andExpect(jsonPath("$.counts.BELOW").value(0));
        mvc.perform(get("/api/v1/admin/pricing/variants/" + watch.variantId()).with(maya()))
                .andExpect(jsonPath("$.assessment").value("CURRENCY_MISMATCH"));
    }

    @Test
    @DisplayName("a draft that is priced still counts: a price is a price whether or not the piece is published yet")
    void draftsCount() throws Exception {
        Piece draft = priced("Draft", "100.00");
        StubInventory.holds(draft.variantId(), lot("500.00", 1));

        mvc.perform(get(LIST).with(maya()))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].productStatus").value("DRAFT"));
    }

    @Test
    @DisplayName("a page is asked for the way every admin list is, and one past the end is empty")
    void paging() throws Exception {
        for (int i = 0; i < 3; i++) {
            Piece piece = priced("Below " + i, "100.00");
            StubInventory.holds(piece.variantId(), lot("500.0" + i, 1));
        }

        mvc.perform(get(LIST).with(maya()).param("size", "2")).andExpect(jsonPath("$.items", hasSize(2))).andExpect(jsonPath("$.totalItems").value(3));
        mvc.perform(get(LIST).with(maya()).param("size", "2").param("page", "1")).andExpect(jsonPath("$.items", hasSize(1)));
        mvc.perform(get(LIST).with(maya()).param("size", "2").param("page", "5")).andExpect(jsonPath("$.items", hasSize(0)));
        mvc.perform(get(LIST).with(maya()).param("size", "101")).andExpect(status().isBadRequest());
        mvc.perform(get(LIST).with(maya()).param("page", "-1")).andExpect(status().isBadRequest());
        mvc.perform(get(LIST).with(maya()).param("status", "WORRYING")).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("120 priced pieces each with its own box are 240 ids, asked of inventory in calls of at most 100, and every one is judged")
    void manyPiecesAreChunked() throws Exception {
        List<Piece> rings = IntStream.range(0, 120).mapToObj(i -> priced("Bulk " + i, "100.00")).toList();
        Set<UUID> everyId = new HashSet<>();
        for (Piece ring : rings) {
            Piece box = fixtures.box("Box for " + ring.variantId());
            fixtures.defaultBox(ring.productId(), box.variantId());
            StubInventory.holds(ring.variantId(), lot("50.00", 1));
            everyId.add(ring.variantId());
            everyId.add(box.variantId());
        }

        mvc.perform(get(LIST).with(maya()).param("status", "ALL").param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(120))
                .andExpect(jsonPath("$.counts.OK").value(120));

        assertThat(StubInventory.calls()).hasSizeGreaterThanOrEqualTo(3);
        assertThat(StubInventory.calls()).allSatisfy(call -> assertThat(call.ids().size()).isLessThanOrEqualTo(100));
        Set<UUID> asked = new HashSet<>();
        StubInventory.calls().forEach(call -> asked.addAll(call.ids()));
        assertThat(asked).isEqualTo(everyId);
    }

    @Test
    @DisplayName("stock being down is 503 with Retry-After: the list is never a guess at 'nothing is below cost'")
    void stockDown() throws Exception {
        Piece ring = priced("Ring", "100.00");
        StubInventory.holds(ring.variantId(), lot("500.00", 1));
        StubInventory.failWith(500);

        mvc.perform(get(LIST).with(maya())).andExpect(status().isServiceUnavailable()).andExpect(header().exists("Retry-After"));
    }

    @Test
    @DisplayName("CATALOG_ADMIN and COST_VIEWER both, and the caller's own token goes to inventory")
    void who() throws Exception {
        Piece ring = priced("Ring", "100.00");
        StubInventory.holds(ring.variantId(), lot("500.00", 1));

        mvc.perform(get(LIST).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CATALOG_ADMIN")))).andExpect(status().isForbidden());
        mvc.perform(get(LIST).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_COST_VIEWER")))).andExpect(status().isForbidden());
        mvc.perform(get(LIST)).andExpect(status().isUnauthorized());
        assertThat(StubInventory.calls()).isEmpty();

        mvc.perform(get(LIST).with(maya())).andExpect(status().isOk());
        assertThat(StubInventory.calls().get(0).authorization()).isEqualTo("Bearer token");
    }
}
