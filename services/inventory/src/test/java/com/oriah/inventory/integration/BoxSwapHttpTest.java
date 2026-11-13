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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Swapping the box of a paid order, over HTTP: the order system's call, never a person's (the same two walls as a reservation: the gateway
 * denies the prefix, and the service wants INVENTORY_ADMIN AND the service account). The reply says what was swapped and when it is dated, and
 * carries no cost.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestClockConfiguration.class})
@TestPropertySource(properties = {"eureka.client.enabled=false"})
class BoxSwapHttpTest {

    private static final String SWAPS = "/api/v1/admin/inventory/box-swaps";

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

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    private UUID velvetBox;
    private UUID product;
    private Line sale;

    @BeforeEach
    void aPaidSale() {
        TestClockConfiguration.CLOCK.set(TestClockConfiguration.T0);
        StockTestData.wipe(jdbc);
        jdbc.execute("DELETE FROM box_commitment");
        jdbc.execute("DELETE FROM product_state");
        UUID ring = UUID.randomUUID();
        UUID box = UUID.randomUUID();
        velvetBox = UUID.randomUUID();
        StockTestData.stock(stock, ring, 5);
        stock.applyReceipt(box(box, "2026-11-09T08:00:00.123456Z"), context(3));
        stock.applyReceipt(box(velvetBox, "2026-11-09T09:00:00Z"), context(4));
        product = UUID.randomUUID();
        commitments.applyProduct(new ProductSnapshot(product, "SELLABLE", "ACTIVE", box, 0, false), UUID.randomUUID(), "system:test");
        sale = new Line(UUID.randomUUID(), ring, product, 1, UUID.randomUUID(), null);
        UUID id = UUID.randomUUID();
        reservations.reserve(id, UUID.randomUUID(), List.of(sale), null, ACTOR);
        reservations.consume(id, java.time.Instant.parse("2026-11-10T10:00:00Z"), ACTOR);
    }

    private static RequestPostProcessor machine() {
        return jwt().jwt(j -> j.subject("sa-1").claim("preferred_username", "service-account-oriah-admin"))
                .authorities(new SimpleGrantedAuthority("ROLE_INVENTORY_ADMIN"));
    }

    private static RequestPostProcessor person() {
        return jwt().jwt(j -> j.subject("kc-maya").claim("preferred_username", "maya").claim("azp", "oriah-admin"))
                .authorities(new SimpleGrantedAuthority("ROLE_INVENTORY_ADMIN"));
    }

    private String body(UUID amendment, UUID replacementLine) {
        return """
                {"amendmentId":"%s","orderId":"%s","productId":"%s","originalSaleLineId":"%s","replacementSaleLineId":"%s","replacementBoxVariantId":"%s",
                 "reason":"the customer asked for the velvet box"}""".formatted(amendment, UUID.randomUUID(), product, sale.boxLineId(), replacementLine, velvetBox);
    }

    @Test
    @DisplayName("the order system swaps a box: the reply says what moved and the day it is dated to; a retry is the same swap; no cost anywhere")
    void swapping() throws Exception {
        UUID amendment = UUID.randomUUID();
        UUID replacementLine = UUID.randomUUID();
        String request = body(amendment, replacementLine);

        mvc.perform(post(SWAPS).with(machine()).contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amendmentId").value(amendment.toString()))
                .andExpect(jsonPath("$.quantity").value(1))
                .andExpect(jsonPath("$.occurredAt").value("2026-11-10T10:00:00Z"))
                .andExpect(jsonPath("$.replay").value(false))
                .andExpect(content().string(not(containsString("cost"))));
        mvc.perform(post(SWAPS).with(machine()).contentType(MediaType.APPLICATION_JSON).content(request.replaceFirst("\"orderId\":\"[^\"]*\"", "\"orderId\":\"" + UUID.randomUUID() + "\"")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://oriah.dev/problems/amendment-id-used"));
    }

    @Test
    @DisplayName("an unknown sale line is a 404 and a box swapped already is a 409, each by name; a short reason is a 400")
    void theRefusals() throws Exception {
        mvc.perform(post(SWAPS).with(machine()).contentType(MediaType.APPLICATION_JSON)
                .content(body(UUID.randomUUID(), UUID.randomUUID()).replace(sale.boxLineId().toString(), UUID.randomUUID().toString())))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.type").value("https://oriah.dev/problems/sale-line-not-found"));
        mvc.perform(post(SWAPS).with(machine()).contentType(MediaType.APPLICATION_JSON).content(body(UUID.randomUUID(), UUID.randomUUID())))
                .andExpect(status().isOk());
        mvc.perform(post(SWAPS).with(machine()).contentType(MediaType.APPLICATION_JSON).content(body(UUID.randomUUID(), UUID.randomUUID())))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.type").value("https://oriah.dev/problems/already-swapped"));
        mvc.perform(post(SWAPS).with(machine()).contentType(MediaType.APPLICATION_JSON)
                .content(body(UUID.randomUUID(), UUID.randomUUID()).replace("the customer asked for the velvet box", "short")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a person with INVENTORY_ADMIN is refused, and so is a call with no token")
    void aPersonIsRefused() throws Exception {
        mvc.perform(post(SWAPS).with(person()).contentType(MediaType.APPLICATION_JSON).content(body(UUID.randomUUID(), UUID.randomUUID())))
                .andExpect(status().isForbidden());
        mvc.perform(post(SWAPS).contentType(MediaType.APPLICATION_JSON).content(body(UUID.randomUUID(), UUID.randomUUID())))
                .andExpect(status().isUnauthorized());
    }
}
