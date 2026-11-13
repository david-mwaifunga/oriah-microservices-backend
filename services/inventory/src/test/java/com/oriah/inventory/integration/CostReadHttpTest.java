package com.oriah.inventory.integration;

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

import com.oriah.inventory.TestcontainersConfiguration;
import com.oriah.inventory.ledger.StockReason;
import com.oriah.inventory.model.Inventory;
import com.oriah.inventory.repository.InventoryRepository;
import com.oriah.inventory.service.StockService;
import com.oriah.inventory.service.StockTestData;

import static com.oriah.inventory.service.StockTestData.ACTOR;
import static com.oriah.inventory.service.StockTestData.context;
import static com.oriah.inventory.service.StockTestData.ring;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What stock cost, for the people who may read it (COST_VIEWER: the owner, a manager, a bookkeeper), and for nobody
 * else. Cost is gated at the API, not hidden in a screen: it is a field in these replies and no others, and this test
 * proves the others have none. The machine identity that calls inventory (INVENTORY_ADMIN) and a sales assistant are
 * refused here.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"eureka.client.enabled=false"})
class CostReadHttpTest {

    private static final String LOTS = "/api/v1/admin/costs/lots";
    private static final String MOVEMENTS = "/api/v1/admin/costs/movements";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private StockService stock;

    @Autowired
    private InventoryRepository inventories;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void clean() {
        StockTestData.wipe(jdbc);
    }

    private static RequestPostProcessor bookkeeper() {
        return jwt().jwt(j -> j.subject("kc-bea")).authorities(new SimpleGrantedAuthority("ROLE_COST_VIEWER"));
    }

    private UUID rings() {
        UUID variant = UUID.randomUUID();
        stock.applyReceipt(ring(variant, "2026-11-09T08:00:00.123456Z"), context(5001));
        return variant;
    }

    @Test
    @DisplayName("a bookkeeper reads the lots: each cost to the ngwee, with the names it was received under, oldest first")
    void theLots() throws Exception {
        UUID variant = rings();

        mvc.perform(get(LOTS).with(bookkeeper()).param("variantId", variant.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].unitCost").value(1528.99))
                .andExpect(jsonPath("$.content[0].qtyReceived").value(4))
                .andExpect(jsonPath("$.content[0].qtyRemaining").value(4))
                .andExpect(jsonPath("$.content[0].slicePosition").value(1))
                .andExpect(jsonPath("$.content[0].currency").value("ZMW"))
                .andExpect(jsonPath("$.content[0].sku").value("RING-1"))
                .andExpect(jsonPath("$.content[0].productTitle").value("Aurelia Ring"))
                .andExpect(jsonPath("$.content[0].variantTitle").value("Aurelia Ring - RING-1"))
                .andExpect(jsonPath("$.content[0].consignmentReference").value("C-000045"))
                .andExpect(jsonPath("$.content[0].supplierName").value("Dubai Gold"))
                .andExpect(jsonPath("$.content[0].estimated").value(false))
                .andExpect(jsonPath("$.content[0].revision").value(1))
                .andExpect(jsonPath("$.content[0].replaced").value(false))
                .andExpect(jsonPath("$.content[1].unitCost").value(1528.98))
                .andExpect(jsonPath("$.content[1].qtyReceived").value(3));
    }

    @Test
    @DisplayName("open=true shows only lots that still hold stock, so the first sale empties the first lot out of the list")
    void openLots() throws Exception {
        UUID variant = rings();
        Inventory inv = inventories.findById(variant).orElseThrow();
        inv.reserve(4);
        inventories.saveAndFlush(inv);
        stock.consume(variant, 4, UUID.randomUUID(), null, ACTOR);

        mvc.perform(get(LOTS).with(bookkeeper()).param("variantId", variant.toString()).param("open", "true"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].slicePosition").value(2))
                .andExpect(jsonPath("$.content[0].qtyRemaining").value(3));
        mvc.perform(get(LOTS).with(bookkeeper()).param("variantId", variant.toString()))
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    @DisplayName("the ledger: what each action did to each lot, at the lot's cost, with the reason, and whether it is in force")
    void theMovements() throws Exception {
        UUID variant = rings();
        Inventory inv = inventories.findById(variant).orElseThrow();
        inv.reserve(3);
        inventories.saveAndFlush(inv);
        UUID sale = UUID.randomUUID();
        stock.consume(variant, 3, sale, null, ACTOR);
        stock.writeOff(UUID.randomUUID(), variant, 1, StockReason.WRITE_OFF_LOST, null, "lost at the market", ACTOR);

        mvc.perform(get(MOVEMENTS).with(bookkeeper()).param("variantId", variant.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(4))           // two receipt rows, a sale row, a write-off row
                .andExpect(jsonPath("$.content[0].reason").value("CONSIGNMENT_RECEIVED"))
                .andExpect(jsonPath("$.content[0].qtyDelta").value(4))
                .andExpect(jsonPath("$.content[0].unitCost").value(1528.99))
                .andExpect(jsonPath("$.content[2].reason").value("SALE"))
                .andExpect(jsonPath("$.content[2].qtyDelta").value(-3))
                .andExpect(jsonPath("$.content[2].actionId").value(sale.toString()))
                .andExpect(jsonPath("$.content[2].costMethod").value("FIFO"))
                .andExpect(jsonPath("$.content[2].costBasis").value("RECEIVED_AT"))
                .andExpect(jsonPath("$.content[2].live").value(true))
                .andExpect(jsonPath("$.content[2].sku").value("RING-1"))
                .andExpect(jsonPath("$.content[3].reason").value("WRITE_OFF_LOST"))
                .andExpect(jsonPath("$.content[3].note").value("lost at the market"));

        mvc.perform(get(MOVEMENTS).with(bookkeeper()).param("actionId", sale.toString()))
                .andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get(MOVEMENTS).with(bookkeeper()).param("variantId", variant.toString()).param("reason", "WRITE_OFF_LOST"))
                .andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get(MOVEMENTS).with(bookkeeper()).param("reason", "STOLEN_BY_ELVES")).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("only COST_VIEWER reads cost: the machine identity (INVENTORY_ADMIN), a sales assistant, the owner's write-off capability and a customer are refused")
    void whoMayRead() throws Exception {
        rings();

        for (String role : new String[] {"ROLE_INVENTORY_ADMIN", "ROLE_SALES_AGENT", "ROLE_STOCK_WRITE_OFF", "ROLE_USER", "ROLE_PROCUREMENT_ADMIN"}) {
            mvc.perform(get(LOTS).with(jwt().authorities(new SimpleGrantedAuthority(role)))).andExpect(status().isForbidden());
            mvc.perform(get(MOVEMENTS).with(jwt().authorities(new SimpleGrantedAuthority(role)))).andExpect(status().isForbidden());
        }
        mvc.perform(get(LOTS)).andExpect(status().isUnauthorized());
        mvc.perform(get(MOVEMENTS)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a page is asked for the way every admin list is: a size over 200 is refused, not clamped")
    void paging() throws Exception {
        mvc.perform(get(LOTS).with(bookkeeper()).param("size", "201")).andExpect(status().isBadRequest());
        mvc.perform(get(LOTS).with(bookkeeper()).param("page", "-1")).andExpect(status().isBadRequest());
        mvc.perform(get(MOVEMENTS).with(bookkeeper()).param("size", "0")).andExpect(status().isBadRequest());
        mvc.perform(get(LOTS).with(bookkeeper())).andExpect(status().isOk()).andExpect(jsonPath("$.content", hasSize(0)));
    }

    @Test
    @DisplayName("cost is in the cost reads and in no other reply: not the stock list, not the public read, not a sale, not a write-off")
    void costIsInNoOtherReply() throws Exception {
        UUID variant = rings();
        RequestPostProcessor admin = jwt().authorities(new SimpleGrantedAuthority("ROLE_INVENTORY_ADMIN"));
        RequestPostProcessor owner = jwt().authorities(new SimpleGrantedAuthority("ROLE_STOCK_WRITE_OFF"));

        String list = mvc.perform(get("/api/v1/admin/inventory").with(admin)).andReturn().getResponse().getContentAsString();
        String publicRead = mvc.perform(get("/api/v1/inventory/" + variant)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        mvc.perform(post("/api/v1/admin/inventory/" + variant + "/reserve").with(admin).contentType(MediaType.APPLICATION_JSON).content("{\"qty\":2}"))
                .andExpect(status().isOk());
        String sale = mvc.perform(post("/api/v1/admin/inventory/" + variant + "/confirm").with(admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"qty\":1,\"saleLineId\":\"" + UUID.randomUUID() + "\"}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String writeOff = mvc.perform(post("/api/v1/admin/inventory/write-offs").with(owner).contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"" + UUID.randomUUID() + "\",\"variantId\":\"" + variant + "\",\"qty\":1,\"reason\":\"WRITE_OFF_BROKEN\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String writeOffList = mvc.perform(get("/api/v1/admin/inventory/write-offs").with(owner)).andReturn().getResponse().getContentAsString();

        for (String reply : new String[] {list, publicRead, sale, writeOff, writeOffList}) {
            assertThat(reply.toLowerCase()).doesNotContain("cost").doesNotContain("1528");
        }
    }

    @Test
    @DisplayName("the stock count against the lots: counts only, no cost, and a clean ledger reports nothing wrong")
    void reconciliation() throws Exception {
        UUID variant = rings();
        RequestPostProcessor admin = jwt().authorities(new SimpleGrantedAuthority("ROLE_INVENTORY_ADMIN"));

        mvc.perform(get("/api/v1/admin/inventory/reconciliation").with(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checkedVariants").value(1))
                .andExpect(jsonPath("$.countMismatches", hasSize(0)))
                .andExpect(jsonPath("$.ledgerMismatches", hasSize(0)));

        // A count that disagrees with its lots (here by raw SQL, which is exactly what this read exists to catch).
        jdbc.update("UPDATE inventory SET on_hand = on_hand + 2 WHERE variant_id = ?", variant);
        mvc.perform(get("/api/v1/admin/inventory/reconciliation").with(admin).param("variantId", variant.toString()))
                .andExpect(jsonPath("$.countMismatches", hasSize(1)))
                .andExpect(jsonPath("$.countMismatches[0].variantId").value(variant.toString()))
                .andExpect(jsonPath("$.countMismatches[0].onHand").value(9))
                .andExpect(jsonPath("$.countMismatches[0].heldInLots").value(7))
                .andExpect(jsonPath("$.countMismatches[0].difference").value(2))
                .andExpect(content().string(not(org.hamcrest.Matchers.containsStringIgnoringCase("cost"))));
        mvc.perform(get("/api/v1/admin/inventory/reconciliation").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_COST_VIEWER"))))
                .andExpect(status().isForbidden());
    }
}
