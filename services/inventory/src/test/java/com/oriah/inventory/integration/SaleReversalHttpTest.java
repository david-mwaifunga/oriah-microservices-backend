package com.oriah.inventory.integration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

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
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.oriah.inventory.TestClockConfiguration;
import com.oriah.inventory.TestcontainersConfiguration;
import com.oriah.inventory.service.BoxCommitmentService;
import com.oriah.inventory.service.BoxCommitmentService.ProductSnapshot;
import com.oriah.inventory.service.ReservationService;
import com.oriah.inventory.service.ReservationService.Line;
import com.oriah.inventory.service.StockService;
import com.oriah.inventory.service.StockTestData;

import static com.oriah.inventory.service.StockTestData.ACTOR;
import static com.oriah.inventory.service.StockTestData.box;
import static com.oriah.inventory.service.StockTestData.context;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Putting a cancelled or returned sale back into stock, over HTTP (Phase 12): the order system's call, never a person's (the same two walls
 * as a reservation). The reply says what moved and carries no cost. Reading a reversal by its id is how the order system finds out, after
 * a reply it never got, whether anything moved: "there is none" is a 404 under its own name, and the read waits for an attempt in flight.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestClockConfiguration.class})
@TestPropertySource(properties = {"eureka.client.enabled=false"})
class SaleReversalHttpTest {

    private static final String REVERSALS = "/api/v1/admin/inventory/sale-reversals";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private StockService stock;
    @Autowired
    private BoxCommitmentService commitments;
    @Autowired
    private ReservationService reservations;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private DataSource dataSource;
    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    private UUID ring;
    private UUID boxVariant;
    private UUID product;
    private UUID order;
    private UUID reservation;
    private Line sale;

    @BeforeEach
    void aPaidSale() {
        TestClockConfiguration.CLOCK.set(TestClockConfiguration.T0);
        StockTestData.wipe(jdbc);
        jdbc.execute("DELETE FROM box_commitment");
        jdbc.execute("DELETE FROM product_state");
        ring = UUID.randomUUID();
        boxVariant = UUID.randomUUID();
        StockTestData.stock(stock, ring, 5);
        stock.applyReceipt(box(boxVariant, "2026-11-09T08:00:00.123456Z"), context(3));
        product = UUID.randomUUID();
        commitments.applyProduct(new ProductSnapshot(product, "SELLABLE", "ACTIVE", boxVariant, 0, false), UUID.randomUUID(), "system:test");
        sale = new Line(UUID.randomUUID(), ring, product, 2, UUID.randomUUID(), null);
        order = UUID.randomUUID();
        reservation = UUID.randomUUID();
        reservations.reserve(reservation, order, List.of(sale), null, ACTOR);
        reservations.consume(reservation, java.time.Instant.parse("2026-11-10T10:00:00Z"), ACTOR);
    }

    private static RequestPostProcessor machine() {
        return jwt().jwt(j -> j.subject("sa-1").claim("preferred_username", "service-account-oriah-admin"))
                .authorities(new SimpleGrantedAuthority("ROLE_INVENTORY_ADMIN"));
    }

    private static RequestPostProcessor person() {
        return jwt().jwt(j -> j.subject("kc-maya").claim("preferred_username", "maya").claim("azp", "oriah-admin"))
                .authorities(new SimpleGrantedAuthority("ROLE_INVENTORY_ADMIN"));
    }

    private String cancellation(UUID id) {
        return """
                {"reversalId":"%s","kind":"CANCELLATION","orderId":"%s","reservationId":"%s","occurredAt":"2026-11-12T09:00:00Z",
                 "goods":[{"saleLineId":"%s","variantId":"%s","quantity":2}],
                 "boxes":[{"boxLineId":"%s","boxVariantId":"%s","productId":"%s","quantity":2}],"pieces":[]}"""
                .formatted(id, order, reservation, sale.saleLineId(), ring, sale.boxLineId(), boxVariant, product);
    }

    private String returnOf(UUID id, UUID piece) {
        return """
                {"reversalId":"%s","kind":"RETURN","orderId":"%s","occurredAt":"2026-11-12T09:00:00Z","goods":[],"boxes":[],
                 "pieces":[{"pieceId":"%s","saleLineId":"%s","variantId":"%s"}]}""".formatted(id, order, piece, sale.saleLineId(), ring);
    }

    @Test
    @DisplayName("the order system cancels a paid sale: the reply says where the boxes went; the same call again is a repeat; no cost anywhere")
    void aCancellation() throws Exception {
        UUID id = UUID.randomUUID();

        mvc.perform(post(REVERSALS).with(machine()).contentType(MediaType.APPLICATION_JSON).content(cancellation(id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reversalId").value(id.toString()))
                .andExpect(jsonPath("$.kind").value("CANCELLATION"))
                .andExpect(jsonPath("$.occurredAt").value("2026-11-12T09:00:00Z"))
                .andExpect(jsonPath("$.replay").value(false))
                .andExpect(jsonPath("$.boxes[0].boxLineId").value(sale.boxLineId().toString()))
                .andExpect(jsonPath("$.boxes[0].toSetAside").value(0))
                .andExpect(jsonPath("$.boxes[0].toFree").value(2))
                .andExpect(content().string(not(containsString("cost"))));
        mvc.perform(post(REVERSALS).with(machine()).contentType(MediaType.APPLICATION_JSON).content(cancellation(id)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.replay").value(true)).andExpect(jsonPath("$.boxes[0].toFree").value(2));
        assertThat(jdbc.queryForObject("SELECT on_hand FROM inventory WHERE variant_id = ?", Integer.class, ring)).isEqualTo(5);
    }

    @Test
    @DisplayName("a returned piece is put back and can be read by the reversal's id; an id nothing was done under is a 404 named reversal-not-found")
    void readById() throws Exception {
        UUID id = UUID.randomUUID();
        mvc.perform(post(REVERSALS).with(machine()).contentType(MediaType.APPLICATION_JSON).content(returnOf(id, UUID.randomUUID())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.kind").value("RETURN")).andExpect(jsonPath("$.boxes").isEmpty());

        mvc.perform(get(REVERSALS + "/" + id).with(machine()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reversalId").value(id.toString()))
                .andExpect(jsonPath("$.orderId").value(order.toString()))
                .andExpect(jsonPath("$.replay").value(false));
        mvc.perform(get(REVERSALS + "/" + UUID.randomUUID()).with(machine()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.type").value("https://oriah.dev/problems/reversal-not-found"));
    }

    @Test
    @DisplayName("the refusals keep their names: a piece too many is 409 more-than-outstanding, a moment before the sale 400 invalid-occurred-at, a body with no kind 400")
    void theRefusals() throws Exception {
        mvc.perform(post(REVERSALS).with(machine()).contentType(MediaType.APPLICATION_JSON).content(cancellation(UUID.randomUUID())))
                .andExpect(status().isOk());
        mvc.perform(post(REVERSALS).with(machine()).contentType(MediaType.APPLICATION_JSON).content(returnOf(UUID.randomUUID(), UUID.randomUUID())))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.type").value("https://oriah.dev/problems/more-than-outstanding"));
        mvc.perform(post(REVERSALS).with(machine()).contentType(MediaType.APPLICATION_JSON)
                .content(returnOf(UUID.randomUUID(), UUID.randomUUID()).replace("2026-11-12T09:00:00Z", "2026-11-10T09:59:59Z")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.type").value("https://oriah.dev/problems/invalid-occurred-at"));
        mvc.perform(post(REVERSALS).with(machine()).contentType(MediaType.APPLICATION_JSON)
                .content(returnOf(UUID.randomUUID(), UUID.randomUUID()).replace("\"kind\":\"RETURN\",", "")))
                .andExpect(status().isBadRequest());
        mvc.perform(post(REVERSALS).with(machine()).contentType(MediaType.APPLICATION_JSON)
                .content(returnOf(UUID.randomUUID(), UUID.randomUUID()).replace("\"kind\":\"RETURN\"", "\"kind\":\"REFUND\"")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("the read waits for an attempt in flight under the same id, so 'there is none' proves nothing moved")
    void readWaitsForAPostInFlight() throws Exception {
        UUID id = UUID.randomUUID();
        try (Connection inFlight = dataSource.getConnection()) {
            inFlight.setAutoCommit(false);
            // What an attempt holds from its first statement to its commit.
            try (PreparedStatement lock = inFlight.prepareStatement("SELECT pg_advisory_xact_lock(?, hashtext(?))")) {
                lock.setInt(1, 0x53524556);
                lock.setString(2, id.toString());
                lock.execute();
            }
            CompletableFuture<Integer> read = CompletableFuture.supplyAsync(() -> {
                try {
                    return mvc.perform(get(REVERSALS + "/" + id).with(machine())).andReturn().getResponse().getStatus();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            Thread.sleep(700);
            assertThat(read).as("the read is waiting for the attempt").isNotDone();

            inFlight.rollback();
            assertThat(read.get(10, TimeUnit.SECONDS)).isEqualTo(404);
        }
    }

    @Test
    @DisplayName("a reversal id the order system gave up is never applied afterwards: the abandon is remembered, a late request under that id is 409 reversal-abandoned and moves nothing, and the read still says there is none")
    void anAbandonedIdIsNeverApplied() throws Exception {
        UUID id = UUID.randomUUID();
        int onHand = jdbc.queryForObject("SELECT on_hand FROM inventory WHERE variant_id = ?", Integer.class, ring);

        mvc.perform(post(REVERSALS + "/" + id + "/abandon").with(machine()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.reversalId").value(id.toString())).andExpect(jsonPath("$.applied").value(false));
        // The request that was on its way arrives after all: it is refused by name, and nothing moves.
        mvc.perform(post(REVERSALS).with(machine()).contentType(MediaType.APPLICATION_JSON).content(cancellation(id)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.type").value("https://oriah.dev/problems/reversal-abandoned"));
        mvc.perform(post(REVERSALS).with(machine()).contentType(MediaType.APPLICATION_JSON).content(returnOf(id, UUID.randomUUID())))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.type").value("https://oriah.dev/problems/reversal-abandoned"));

        assertThat(jdbc.queryForObject("SELECT on_hand FROM inventory WHERE variant_id = ?", Integer.class, ring)).isEqualTo(onHand);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sale_reversal WHERE id = ?", Integer.class, id)).isZero();
        mvc.perform(get(REVERSALS + "/" + id).with(machine()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.type").value("https://oriah.dev/problems/reversal-not-found"));
        // Asked again, the answer is the same, and it is still one record.
        mvc.perform(post(REVERSALS + "/" + id + "/abandon").with(machine())).andExpect(status().isOk()).andExpect(jsonPath("$.applied").value(false));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sale_reversal_abandoned WHERE id = ?", Integer.class, id)).isEqualTo(1);
        // The sale itself can still be reversed under a new id.
        mvc.perform(post(REVERSALS).with(machine()).contentType(MediaType.APPLICATION_JSON).content(cancellation(UUID.randomUUID()))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("abandoning a reversal that WAS applied changes nothing and says so (applied: true), so the order system finishes its correction instead; a person is refused")
    void abandoningOneThatWasAppliedSaysSo() throws Exception {
        UUID id = UUID.randomUUID();
        mvc.perform(post(REVERSALS).with(machine()).contentType(MediaType.APPLICATION_JSON).content(cancellation(id))).andExpect(status().isOk());

        mvc.perform(post(REVERSALS + "/" + id + "/abandon").with(machine()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.reversalId").value(id.toString())).andExpect(jsonPath("$.applied").value(true));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM sale_reversal_abandoned WHERE id = ?", Integer.class, id)).isZero();
        mvc.perform(post(REVERSALS).with(machine()).contentType(MediaType.APPLICATION_JSON).content(cancellation(id)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.replay").value(true));
        mvc.perform(post(REVERSALS + "/" + UUID.randomUUID() + "/abandon").with(person())).andExpect(status().isForbidden());
        mvc.perform(post(REVERSALS + "/" + UUID.randomUUID() + "/abandon")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a returned piece that is waiting to be looked at is in stock but not for sale: the staff stock list counts it as held, not as available")
    void theStockListDoesNotOfferAHeldPiece() throws Exception {
        // 5 rings received, 2 sold: 3 on hand, all free. One of the two comes back: 4 on hand, 1 of them held.
        mvc.perform(post(REVERSALS).with(machine()).contentType(MediaType.APPLICATION_JSON).content(returnOf(UUID.randomUUID(), UUID.randomUUID())))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/admin/inventory").param("size", "100").with(person()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.variantId == '" + ring + "')].onHand").value(4))
                .andExpect(jsonPath("$.content[?(@.variantId == '" + ring + "')].quarantined").value(1))
                .andExpect(jsonPath("$.content[?(@.variantId == '" + ring + "')].available").value(3))
                .andExpect(jsonPath("$.content[?(@.variantId == '" + ring + "')].free").value(3));
        // With every other ring held or gone, it is out of stock to a buyer.
        jdbc.update("UPDATE inventory SET on_hand = 1 WHERE variant_id = ?", ring);
        mvc.perform(get("/api/v1/admin/inventory").param("size", "100").param("outOfStock", "true").with(person()))
                .andExpect(jsonPath("$.content[?(@.variantId == '" + ring + "')].available").value(0));
        mvc.perform(get("/api/v1/admin/inventory").param("size", "100").param("outOfStock", "false").with(person()))
                .andExpect(jsonPath("$.content[?(@.variantId == '" + ring + "')]").isEmpty());
    }

    @Test
    @DisplayName("a person with INVENTORY_ADMIN is refused on both doors, and so is a call with no token")
    void onlyTheOrderSystem() throws Exception {
        mvc.perform(post(REVERSALS).with(person()).contentType(MediaType.APPLICATION_JSON).content(cancellation(UUID.randomUUID())))
                .andExpect(status().isForbidden());
        mvc.perform(get(REVERSALS + "/" + UUID.randomUUID()).with(person())).andExpect(status().isForbidden());
        mvc.perform(post(REVERSALS).contentType(MediaType.APPLICATION_JSON).content(cancellation(UUID.randomUUID())))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(REVERSALS + "/" + UUID.randomUUID())).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sale_reversal WHERE order_id = ?", Integer.class, order)).isZero();
    }
}
