package com.oriah.inventory.integration;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.oriah.inventory.TestcontainersConfiguration;
import com.oriah.inventory.model.Inventory;
import com.oriah.inventory.model.ProductRef;
import com.oriah.inventory.repository.InventoryRepository;
import com.oriah.inventory.repository.ProductRefRepository;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /api/v1/admin/inventory} — the list that did not exist.
 *
 * <p>Until now inventory had exactly one admin write path and a single-variant
 * lookup, which is why the admin Inventory page is a "seed stock" form with no
 * way to see what stock exists, and why the dashboard had no inventory figure
 * it could honestly show.
 *
 * <h2>Two design choices the tests pin</h2>
 *
 * <p><strong>Ordered by scarcity, not by id.</strong> The first thing an
 * inventory admin wants is what is about to run out. Sorting by available
 * ascending puts the problems on page one; sorting by variant id would bury
 * them at random.
 *
 * <p><strong>{@code available} is computed, not stored</strong>
 * ({@code onHand - reserved}), so the out-of-stock filter has to be expressed
 * as arithmetic in the query rather than as a column predicate. The test for
 * it uses a row that is out of stock because it is fully RESERVED, not because
 * on-hand is zero — the case a naive {@code onHand = 0} filter would miss
 * while looking like it worked.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"eureka.client.enabled=false"})
class AdminInventoryListTest {

    private static final SimpleGrantedAuthority ROLE_USER =
            new SimpleGrantedAuthority("ROLE_USER");
    private static final SimpleGrantedAuthority ROLE_INVENTORY_ADMIN =
            new SimpleGrantedAuthority("ROLE_INVENTORY_ADMIN");

    private static final String PATH = "/api/v1/admin/inventory";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private InventoryRepository repository;
    @Autowired
    private ProductRefRepository productRefs;
    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void reset() {
        repository.deleteAll();
        productRefs.deleteAll();
    }

    private UUID stock(int onHand, int reserve) {
        UUID variantId = UUID.randomUUID();
        Inventory inv = new Inventory(variantId, onHand);
        if (reserve > 0) {
            inv.reserve(reserve);
        }
        repository.saveAndFlush(inv);
        return variantId;
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("a plain USER is refused by the service, not only by the gateway")
    void userIsForbidden() throws Exception {
        mvc.perform(get(PATH).with(jwt().authorities(ROLE_USER)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("INVENTORY_ADMIN gets a page of stock records")
    void adminGetsAPage() throws Exception {
        stock(10, 0);

        mvc.perform(get(PATH).with(jwt().authorities(ROLE_INVENTORY_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].onHand").value(10))
                .andExpect(jsonPath("$.content[0].reserved").value(0))
                .andExpect(jsonPath("$.content[0].available").value(10));
    }

    @Test
    @DisplayName("scarcest first, so what is running out is on page one")
    void scarcestFirst() throws Exception {
        stock(50, 0);   // available 50
        stock(10, 8);   // available 2
        stock(5, 0);    // available 5

        mvc.perform(get(PATH).with(jwt().authorities(ROLE_INVENTORY_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].available").value(2))
                .andExpect(jsonPath("$.content[1].available").value(5))
                .andExpect(jsonPath("$.content[2].available").value(50));
    }

    @Test
    @DisplayName("the out-of-stock filter catches fully-reserved rows, not just onHand=0")
    void outOfStockIncludesFullyReserved() throws Exception {
        stock(0, 0);    // nothing on hand
        stock(4, 4);    // on hand, but every unit is spoken for -> available 0
        stock(9, 1);    // available 8, must NOT match

        mvc.perform(get(PATH).param("outOfStock", "true")
                        .with(jwt().authorities(ROLE_INVENTORY_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    @DisplayName("carries the product title when one has arrived over Kafka")
    void includesTitleWhenKnown() throws Exception {
        // ProductRef is populated by ProductEventConsumer from catalog events.
        // A variant whose event has not arrived yet still lists, with a null
        // title -- an inventory row that exists is worth showing even if the
        // catalogue has not caught up.
        UUID variantId = stock(7, 0);
        productRefs.saveAndFlush(new ProductRef(variantId, variantId, "Aurelia 18k"));

        mvc.perform(get(PATH).with(jwt().authorities(ROLE_INVENTORY_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].title").value("Aurelia 18k"));
    }

    @Test
    @DisplayName("lists a variant whose product event has not arrived, with a null title")
    void listsUnknownVariant() throws Exception {
        stock(3, 0);

        mvc.perform(get(PATH).with(jwt().authorities(ROLE_INVENTORY_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].title").doesNotExist());
    }

    @Test
    @DisplayName("size above the cap is rejected, not silently clamped")
    void oversizedPageRejected() throws Exception {
        mvc.perform(get(PATH).param("size", "201")
                        .with(jwt().authorities(ROLE_INVENTORY_ADMIN)))
                .andExpect(status().isBadRequest());
    }
}
