package com.oriah.inventory.integration;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.oriah.inventory.TestcontainersConfiguration;
import com.oriah.inventory.events.ReceiptSnapshot;
import com.oriah.inventory.events.ReceiptSnapshot.Slice;
import com.oriah.inventory.model.Inventory;
import com.oriah.inventory.repository.InventoryRepository;
import com.oriah.inventory.service.StockService;
import com.oriah.inventory.service.StockTestData;

import static com.oriah.inventory.service.StockTestData.ACTOR;
import static com.oriah.inventory.service.StockTestData.amended;
import static com.oriah.inventory.service.StockTestData.amendmentContext;
import static com.oriah.inventory.service.StockTestData.context;
import static com.oriah.inventory.service.StockTestData.ring;
import static com.oriah.inventory.service.StockTestData.sevenRingsAsKeyed;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The price check's one question to inventory: for these variants, what do the lots that still hold stock cost? Every
 * variant asked about is answered (one that was never received answers "nothing held", it is never left out, because a
 * caller that read a missing entry as "no cost" would let a price through unseen), and the figures are served only to
 * COST_VIEWER, like every other cost read.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"eureka.client.enabled=false"})
class CostHeldReadHttpTest {

    private static final String HELD = "/api/v1/admin/costs/held";

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
    @DisplayName("the held lots of a received variant: each lot's cost, the dearest, and the weighted average, to the ngwee")
    void theHeldCost() throws Exception {
        UUID variant = rings();

        mvc.perform(get(HELD).with(bookkeeper()).param("variantIds", variant.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.variants", hasSize(1)))
                .andExpect(jsonPath("$.variants[0].variantId").value(variant.toString()))
                .andExpect(jsonPath("$.variants[0].onHand").value(7))
                .andExpect(jsonPath("$.variants[0].heldUnits").value(7))
                .andExpect(jsonPath("$.variants[0].highestUnitCost").value(1528.99))
                .andExpect(jsonPath("$.variants[0].averageUnitCost").value(1528.99))
                .andExpect(jsonPath("$.variants[0].currency").value("ZMW"))
                .andExpect(jsonPath("$.variants[0].estimated").value(false))
                .andExpect(jsonPath("$.variants[0].lots", hasSize(2)))
                .andExpect(jsonPath("$.variants[0].lots[0].unitCost").value(1528.99))
                .andExpect(jsonPath("$.variants[0].lots[0].qtyRemaining").value(4))
                .andExpect(jsonPath("$.variants[0].lots[0].revision").value(1))
                .andExpect(jsonPath("$.variants[0].lots[0].estimated").value(false))
                .andExpect(jsonPath("$.variants[0].lots[0].consignmentReference").value("C-000045"))
                .andExpect(jsonPath("$.variants[0].lots[0].lotId").exists())
                .andExpect(jsonPath("$.variants[0].lots[0].receivedAt").exists())
                .andExpect(jsonPath("$.variants[0].lots[1].unitCost").value(1528.98))
                .andExpect(jsonPath("$.variants[0].lots[1].qtyRemaining").value(3));
    }

    @Test
    @DisplayName("a lot that has been sold out of stops counting: the dear lot sells and the dearest held cost falls")
    void aSoldOutLotDropsOut() throws Exception {
        UUID variant = rings();
        Inventory inv = inventories.findById(variant).orElseThrow();
        inv.reserve(4);
        inventories.saveAndFlush(inv);
        stock.consume(variant, 4, UUID.randomUUID(), null, ACTOR);

        mvc.perform(get(HELD).with(bookkeeper()).param("variantIds", variant.toString()))
                .andExpect(jsonPath("$.variants[0].onHand").value(3))
                .andExpect(jsonPath("$.variants[0].heldUnits").value(3))
                .andExpect(jsonPath("$.variants[0].lots", hasSize(1)))
                .andExpect(jsonPath("$.variants[0].highestUnitCost").value(1528.98))
                .andExpect(jsonPath("$.variants[0].averageUnitCost").value(1528.98));
    }

    @Test
    @DisplayName("a variant that was never received is answered, with nothing held and no cost, not left out")
    void anUnreceivedVariantIsAnswered() throws Exception {
        UUID received = rings();
        UUID never = UUID.randomUUID();

        mvc.perform(get(HELD).with(bookkeeper()).param("variantIds", never + "," + received))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.variants", hasSize(2)))
                .andExpect(jsonPath("$.variants[0].variantId").value(never.toString()))
                .andExpect(jsonPath("$.variants[0].onHand").value(0))
                .andExpect(jsonPath("$.variants[0].heldUnits").value(0))
                .andExpect(jsonPath("$.variants[0].lots", hasSize(0)))
                .andExpect(jsonPath("$.variants[0].highestUnitCost").value(nullValue()))
                .andExpect(jsonPath("$.variants[0].averageUnitCost").value(nullValue()))
                .andExpect(jsonPath("$.variants[0].currency").value(nullValue()))
                .andExpect(jsonPath("$.variants[1].variantId").value(received.toString()))
                .andExpect(jsonPath("$.variants[1].highestUnitCost").value(1528.99));
    }

    @Test
    @DisplayName("every id asked about is answered once, in the order asked, even if it was named twice")
    void oncePerIdInTheOrderAsked() throws Exception {
        UUID a = rings();
        UUID b = rings();

        mvc.perform(get(HELD).with(bookkeeper()).param("variantIds", b + "," + a + "," + b))
                .andExpect(jsonPath("$.variants", hasSize(2)))
                .andExpect(jsonPath("$.variants[0].variantId").value(b.toString()))
                .andExpect(jsonPath("$.variants[1].variantId").value(a.toString()));
    }

    @Test
    @DisplayName("a corrected consignment: the lots the correction replaced are not held, the new ones are, at their new cost")
    void aCorrectionReplacesTheLots() throws Exception {
        UUID variant = UUID.randomUUID();
        ReceiptSnapshot line = sevenRingsAsKeyed(variant, "2026-02-10T08:00:00.000001Z");
        stock.applyReceipt(line, context(5001));
        // K800 of courier corrected to K8,000: 5 x 2,142.86 + 2 x 2,142.85
        stock.applyAmendment(amended(line, 2, "the courier's bill was K8,000, not K800", "2026-11-13T09:00:00Z", "8000.00", "15000.00",
                List.of(new Slice(5, new BigDecimal("2142.86")), new Slice(2, new BigDecimal("2142.85"))), false),
                amendmentContext(6000, "2026-11-13T09:00:00Z"));

        mvc.perform(get(HELD).with(bookkeeper()).param("variantIds", variant.toString()))
                .andExpect(jsonPath("$.variants[0].heldUnits").value(7))
                .andExpect(jsonPath("$.variants[0].highestUnitCost").value(2142.86))
                .andExpect(jsonPath("$.variants[0].lots", hasSize(2)))
                .andExpect(jsonPath("$.variants[0].lots[0].revision").value(2))
                .andExpect(jsonPath("$.variants[0].lots[1].revision").value(2));
    }

    @Test
    @DisplayName("up to 100 ids are answered; 101, none, a blank list and an id that is not a UUID are refused")
    void howManyMayBeAsked() throws Exception {
        List<UUID> hundred = IntStream.range(0, 100).mapToObj(i -> UUID.randomUUID()).toList();
        mvc.perform(get(HELD).with(bookkeeper()).param("variantIds", join(hundred)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.variants", hasSize(100)));

        List<UUID> tooMany = new ArrayList<>(hundred);
        tooMany.add(UUID.randomUUID());
        mvc.perform(get(HELD).with(bookkeeper()).param("variantIds", join(tooMany))).andExpect(status().isBadRequest());
        mvc.perform(get(HELD).with(bookkeeper())).andExpect(status().isBadRequest());
        mvc.perform(get(HELD).with(bookkeeper()).param("variantIds", "")).andExpect(status().isBadRequest());
        mvc.perform(get(HELD).with(bookkeeper()).param("variantIds", "not-a-uuid")).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("only COST_VIEWER reads held cost: the machine identity, a sales assistant, a customer are refused; no token is 401")
    void whoMayRead() throws Exception {
        UUID variant = rings();

        for (String role : new String[] {"ROLE_INVENTORY_ADMIN", "ROLE_SALES_AGENT", "ROLE_STOCK_WRITE_OFF", "ROLE_USER", "ROLE_CATALOG_ADMIN"}) {
            mvc.perform(get(HELD).with(jwt().authorities(new SimpleGrantedAuthority(role))).param("variantIds", variant.toString()))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(get(HELD).param("variantIds", variant.toString())).andExpect(status().isUnauthorized());
    }

    private static String join(List<UUID> ids) {
        return ids.stream().map(UUID::toString).collect(Collectors.joining(","));
    }
}
