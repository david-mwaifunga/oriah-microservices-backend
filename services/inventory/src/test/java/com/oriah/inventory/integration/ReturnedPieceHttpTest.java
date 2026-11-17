package com.oriah.inventory.integration;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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

import com.oriah.inventory.TestClockConfiguration;
import com.oriah.inventory.TestcontainersConfiguration;
import com.oriah.inventory.service.BoxCommitmentService;
import com.oriah.inventory.service.BoxCommitmentService.ProductSnapshot;
import com.oriah.inventory.service.ReservationService;
import com.oriah.inventory.service.ReservationService.Line;
import com.oriah.inventory.service.SaleReversalService;
import com.oriah.inventory.service.StockService;
import com.oriah.inventory.service.StockTestData;

import static com.oriah.inventory.service.StockTestData.ACTOR;
import static com.oriah.inventory.service.StockTestData.box;
import static com.oriah.inventory.service.StockTestData.context;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Deciding what becomes of a returned piece, over HTTP (Phase 12): the order system's call, never a person's. The order system decides who
 * may assess a return; inventory only moves the piece. The reply carries no cost.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestClockConfiguration.class})
@TestPropertySource(properties = {"eureka.client.enabled=false"})
class ReturnedPieceHttpTest {

    private static final String PIECES = "/api/v1/admin/inventory/returned-pieces/";
    private static final String PROBLEMS = "https://oriah.dev/problems/";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private StockService stock;
    @Autowired
    private BoxCommitmentService commitments;
    @Autowired
    private ReservationService reservations;
    @Autowired
    private SaleReversalService reversals;
    @Autowired
    private JdbcTemplate jdbc;
    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    private UUID ring;
    private UUID piece;

    @BeforeEach
    void aRingCameBack() {
        TestClockConfiguration.CLOCK.set(TestClockConfiguration.T0);
        StockTestData.wipe(jdbc);
        jdbc.execute("DELETE FROM box_commitment");
        jdbc.execute("DELETE FROM product_state");
        ring = UUID.randomUUID();
        UUID boxVariant = UUID.randomUUID();
        StockTestData.stock(stock, ring, 5);
        stock.applyReceipt(box(boxVariant, "2026-11-09T08:00:00.123456Z"), context(3));
        UUID product = UUID.randomUUID();
        commitments.applyProduct(new ProductSnapshot(product, "SELLABLE", "ACTIVE", boxVariant, 0, false), UUID.randomUUID(), "system:test");
        Line sale = new Line(UUID.randomUUID(), ring, product, 1, UUID.randomUUID(), null);
        UUID order = UUID.randomUUID();
        UUID reservation = UUID.randomUUID();
        reservations.reserve(reservation, order, List.of(sale), null, ACTOR);
        reservations.consume(reservation, java.time.Instant.parse("2026-11-10T10:00:00Z"), ACTOR);
        piece = UUID.randomUUID();
        reversals.reverse(new SaleReversalService.Request(UUID.randomUUID(), "RETURN", order, null, java.time.Instant.parse("2026-11-12T09:00:00Z"), List.of(),
                List.of(), List.of(new SaleReversalService.Piece(piece, sale.saleLineId(), ring))), ACTOR);
    }

    private static RequestPostProcessor machine() {
        return jwt().jwt(j -> j.subject("sa-1").claim("preferred_username", "service-account-oriah-admin"))
                .authorities(new SimpleGrantedAuthority("ROLE_INVENTORY_ADMIN"));
    }

    private static RequestPostProcessor person() {
        return jwt().jwt(j -> j.subject("kc-maya").claim("preferred_username", "maya").claim("azp", "oriah-admin"))
                .authorities(new SimpleGrantedAuthority("ROLE_INVENTORY_ADMIN"), new SimpleGrantedAuthority("ROLE_STOCK_WRITE_OFF"));
    }

    private ResultActions decide(UUID id, String json, RequestPostProcessor who) throws Exception {
        return mvc.perform(post(PIECES + id + "/outcome").with(who).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    @Test
    @DisplayName("the order system writes a returned piece off: the reply says what was decided and when; the same call again is a repeat; no cost anywhere")
    void decided() throws Exception {
        String body = "{\"outcome\":\"WRITTEN_OFF\",\"occurredAt\":\"2026-11-12T09:30:00Z\"}";

        decide(piece, body, machine())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pieceId").value(piece.toString()))
                .andExpect(jsonPath("$.outcome").value("WRITTEN_OFF"))
                .andExpect(jsonPath("$.variantId").value(ring.toString()))
                .andExpect(jsonPath("$.occurredAt").value("2026-11-12T09:30:00Z"))
                .andExpect(jsonPath("$.replay").value(false))
                .andExpect(content().string(not(containsString("cost"))));
        decide(piece, body, machine()).andExpect(status().isOk()).andExpect(jsonPath("$.replay").value(true));
        decide(piece, "{\"outcome\":\"RELEASED\"}", machine())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.type").value(PROBLEMS + "piece-outcome-recorded"));

        assertThat(jdbc.queryForObject("SELECT on_hand FROM inventory WHERE variant_id = ?", Integer.class, ring)).isEqualTo(4);
    }

    @Test
    @DisplayName("the refusals keep their names: an unknown piece is 404, a listing inventory has not heard of 409, an answer nobody knows 400")
    void theRefusals() throws Exception {
        decide(UUID.randomUUID(), "{\"outcome\":\"RELEASED\"}", machine())
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.type").value(PROBLEMS + "returned-piece-not-found"));
        decide(piece, "{\"outcome\":\"RELISTED\",\"childVariantId\":\"" + UUID.randomUUID() + "\"}", machine())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.type").value(PROBLEMS + "markdown-variant-not-known-yet"));
        decide(piece, "{\"outcome\":\"MELTED\"}", machine()).andExpect(status().isBadRequest());
        decide(piece, "{}", machine()).andExpect(status().isBadRequest());
        decide(piece, "{\"outcome\":\"RELISTED\"}", machine()).andExpect(status().isBadRequest());
        decide(piece, "{\"outcome\":\"RELEASED\",\"occurredAt\":\"2026-11-12T08:59:59Z\"}", machine())
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.type").value(PROBLEMS + "invalid-occurred-at"));

        assertThat(jdbc.queryForObject("SELECT quarantined FROM inventory WHERE variant_id = ?", Integer.class, ring)).as("the piece still waits").isEqualTo(1);
    }

    @Test
    @DisplayName("a person is refused, whatever they may do elsewhere, and so is a call with no token")
    void onlyTheOrderSystem() throws Exception {
        decide(piece, "{\"outcome\":\"RELEASED\"}", person()).andExpect(status().isForbidden());
        mvc.perform(post(PIECES + piece + "/outcome").contentType(MediaType.APPLICATION_JSON).content("{\"outcome\":\"RELEASED\"}"))
                .andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT outcome FROM returned_piece WHERE piece_id = ?", String.class, piece)).isNull();
    }
}
