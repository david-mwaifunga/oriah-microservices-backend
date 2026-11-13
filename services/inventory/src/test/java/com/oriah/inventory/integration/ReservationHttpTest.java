package com.oriah.inventory.integration;

import java.time.Duration;
import java.time.Instant;
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
import com.oriah.inventory.service.StockService;
import com.oriah.inventory.service.StockTestData;

import static com.oriah.inventory.service.StockTestData.box;
import static com.oriah.inventory.service.StockTestData.context;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Reservations over HTTP (Phase 7). They are for the order system, not for people: every call needs INVENTORY_ADMIN AND to be the machine
 * identity (the {@code oriah-admin} service account). A person holding INVENTORY_ADMIN, an owner or a manager, who reaches inventory directly
 * is refused, so nobody can fabricate a sale or release another customer's hold (the gateway denies the prefix too; this is the second
 * wall). No reply carries a cost: cost is read under {@code /api/v1/admin/costs} with its own capability.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestClockConfiguration.class})
@TestPropertySource(properties = {"eureka.client.enabled=false"})
class ReservationHttpTest {

    private static final String BASE = "/api/v1/admin/inventory/reservations";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private StockService stock;

    @Autowired
    private BoxCommitmentService commitments;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    private UUID ring;
    private UUID box;
    private UUID product;

    @BeforeEach
    void stockOnTheShelf() {
        TestClockConfiguration.CLOCK.set(TestClockConfiguration.T0);
        StockTestData.wipe(jdbc);
        jdbc.execute("DELETE FROM box_commitment");
        jdbc.execute("DELETE FROM product_state");
        ring = UUID.randomUUID();
        box = UUID.randomUUID();
        StockTestData.stock(stock, ring, 5);
        stock.applyReceipt(box(box, "2026-11-09T08:00:00.123456Z"), context(3));
        product = UUID.randomUUID();
        commitments.applyProduct(new ProductSnapshot(product, "SELLABLE", "ACTIVE", box, 0, false), UUID.randomUUID(), "system:test");
    }

    /** The order system: the oriah-admin service account, which holds INVENTORY_ADMIN. */
    private static RequestPostProcessor machine() {
        return jwt().jwt(j -> j.subject("sa-1").claim("preferred_username", "service-account-oriah-admin"))
                .authorities(new SimpleGrantedAuthority("ROLE_INVENTORY_ADMIN"));
    }

    /** A person with the same role (an owner or a manager). */
    private static RequestPostProcessor person() {
        return jwt().jwt(j -> j.subject("kc-maya").claim("preferred_username", "maya").claim("azp", "oriah-admin"))
                .authorities(new SimpleGrantedAuthority("ROLE_INVENTORY_ADMIN"));
    }

    private static RequestPostProcessor shopper() {
        return jwt().jwt(j -> j.subject("kc-alice").claim("preferred_username", "alice")).authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }

    private String reserveBody(UUID reservationId, UUID order, int qty) {
        return """
                {"reservationId":"%s","orderId":"%s","lines":[{"saleLineId":"%s","variantId":"%s","productId":"%s","quantity":%d,"boxLineId":"%s"}]}"""
                .formatted(reservationId, order, UUID.randomUUID(), ring, product, qty, UUID.randomUUID());
    }

    private ResultActions reserve(RequestPostProcessor who, UUID reservationId, UUID order, int qty) throws Exception {
        return mvc.perform(post(BASE).with(who).contentType(MediaType.APPLICATION_JSON).content(reserveBody(reservationId, order, qty)));
    }

    @Test
    @DisplayName("the order system holds an order's pieces and boxes: the hold, its time and where each line came from are the answer")
    void reserving() throws Exception {
        UUID id = UUID.randomUUID();
        UUID order = UUID.randomUUID();

        reserve(machine(), id, order, 2).andExpect(status().isOk())
                .andExpect(jsonPath("$.reservationId").value(id.toString()))
                .andExpect(jsonPath("$.orderId").value(order.toString()))
                .andExpect(jsonPath("$.status").value("HELD"))
                .andExpect(jsonPath("$.heldAt").value("2026-11-12T10:00:00Z"))
                .andExpect(jsonPath("$.expiresAt").value("2026-11-12T10:10:00Z"))
                .andExpect(jsonPath("$.lines", hasSize(2)))
                .andExpect(jsonPath("$.lines[?(@.kind=='GOODS')].quantity").value(2))
                .andExpect(jsonPath("$.lines[?(@.kind=='BOX')].variantId").value(box.toString()))
                .andExpect(content().string(not(containsString("cost"))));
    }

    @Test
    @DisplayName("a hold that is too long is a 400; a quantity of zero is a 400")
    void badRequests() throws Exception {
        mvc.perform(post(BASE).with(machine()).contentType(MediaType.APPLICATION_JSON)
                .content(reserveBody(UUID.randomUUID(), UUID.randomUUID(), 0))).andExpect(status().isBadRequest());
        mvc.perform(post(BASE).with(machine()).contentType(MediaType.APPLICATION_JSON)
                .content(reserveBody(UUID.randomUUID(), UUID.randomUUID(), 1).replace("{\"reservationId\"", "{\"holdSeconds\":999999,\"reservationId\"")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a shortage is a 409 `insufficient-stock` that names every short line, so the order system can say what is short")
    void aShortage() throws Exception {
        reserve(machine(), UUID.randomUUID(), UUID.randomUUID(), 6).andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://oriah.dev/problems/insufficient-stock"))
                .andExpect(jsonPath("$.shortages", hasSize(1)))
                .andExpect(jsonPath("$.shortages[0].variantId").value(ring.toString()))
                .andExpect(jsonPath("$.shortages[0].kind").value("GOODS"))
                .andExpect(jsonPath("$.shortages[0].requested").value(6))
                .andExpect(jsonPath("$.shortages[0].available").value(5));
    }

    @Test
    @DisplayName("an order that already holds a reservation is told so: 409 `order-already-holds`")
    void orderAlreadyHolds() throws Exception {
        UUID order = UUID.randomUUID();
        reserve(machine(), UUID.randomUUID(), order, 1).andExpect(status().isOk());

        reserve(machine(), UUID.randomUUID(), order, 1).andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://oriah.dev/problems/order-already-holds"));
    }

    @Test
    @DisplayName("consuming sells the order, dated to the money; extending stretches the hold; releasing frees it; each answers the reservation")
    void theLifeOfAHold() throws Exception {
        UUID sold = UUID.randomUUID();
        reserve(machine(), sold, UUID.randomUUID(), 2).andExpect(status().isOk());
        mvc.perform(post(BASE + "/" + sold + "/consume").with(machine()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"occurredAt\":\"2026-11-10T10:00:00Z\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONSUMED"))
                .andExpect(content().string(not(containsString("cost"))));

        UUID stretched = UUID.randomUUID();
        reserve(machine(), stretched, UUID.randomUUID(), 1).andExpect(status().isOk());
        Instant until = TestClockConfiguration.T0.plus(Duration.ofHours(24));
        mvc.perform(post(BASE + "/" + stretched + "/extend").with(machine()).contentType(MediaType.APPLICATION_JSON).content("{\"until\":\"" + until + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.expiresAt").value(until.toString()));

        UUID released = UUID.randomUUID();
        reserve(machine(), released, UUID.randomUUID(), 1).andExpect(status().isOk());
        mvc.perform(post(BASE + "/" + released + "/release").with(machine())).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RELEASED"));
        mvc.perform(get(BASE + "/" + released).with(machine())).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RELEASED"));
    }

    @Test
    @DisplayName("a lapsed hold cannot be sold: 409 `reservation-expired`; a hold that does not exist is a 404")
    void lapsedAndUnknown() throws Exception {
        UUID id = UUID.randomUUID();
        reserve(machine(), id, UUID.randomUUID(), 1).andExpect(status().isOk());
        TestClockConfiguration.CLOCK.advance(Duration.ofMinutes(11));

        mvc.perform(post(BASE + "/" + id + "/consume").with(machine()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.type").value("https://oriah.dev/problems/reservation-expired"));
        mvc.perform(get(BASE + "/" + UUID.randomUUID()).with(machine())).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://oriah.dev/problems/reservation-not-found"));
    }

    @Test
    @DisplayName("a person with INVENTORY_ADMIN is refused on every reservation call: only the order system may hold, sell or release")
    void aPersonIsRefused() throws Exception {
        UUID id = UUID.randomUUID();
        reserve(machine(), id, UUID.randomUUID(), 1).andExpect(status().isOk());

        reserve(person(), UUID.randomUUID(), UUID.randomUUID(), 1).andExpect(status().isForbidden());
        mvc.perform(post(BASE + "/" + id + "/consume").with(person()).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        mvc.perform(post(BASE + "/" + id + "/release").with(person())).andExpect(status().isForbidden());
        mvc.perform(post(BASE + "/" + id + "/extend").with(person()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"until\":\"2026-11-12T20:00:00Z\"}")).andExpect(status().isForbidden());
        mvc.perform(get(BASE + "/" + id).with(person())).andExpect(status().isForbidden());

        mvc.perform(get(BASE + "/" + id).with(machine())).andExpect(jsonPath("$.status").value("HELD"));
    }

    @Test
    @DisplayName("a shopper is refused, and so is a call with no token")
    void shoppersAndStrangers() throws Exception {
        reserve(shopper(), UUID.randomUUID(), UUID.randomUUID(), 1).andExpect(status().isForbidden());
        mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(reserveBody(UUID.randomUUID(), UUID.randomUUID(), 1)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("the old per-variant reserve, release and confirm are gone: a bare counter had no owner and could not be released exactly once")
    void theOldEndpointsAreGone() throws Exception {
        for (String action : new String[] {"reserve", "release", "confirm"}) {
            mvc.perform(post("/api/v1/admin/inventory/" + ring + "/" + action).with(machine()).contentType(MediaType.APPLICATION_JSON).content("{\"qty\":1}"))
                    .andExpect(status().isNotFound());
        }
    }
}
