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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;
import com.oriah.inventory.TestcontainersConfiguration;
import com.oriah.inventory.model.Inventory;
import com.oriah.inventory.repository.InventoryRepository;
import com.oriah.inventory.service.StockService;
import com.oriah.inventory.service.StockTestData;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code /api/v1/admin/inventory/write-offs}: stock that leaves with no sale, and some of it coming back. Owner only
 * (STOCK_WRITE_OFF, which MANAGER does not hold), checked by the service as well as the gateway. Nothing here shows a
 * cost: the people who may write stock off are not thereby the people who may read what it cost.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"eureka.client.enabled=false"})
class WriteOffHttpTest {

    private static final String PATH = "/api/v1/admin/inventory/write-offs";

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

    private static RequestPostProcessor owner() {
        return jwt().jwt(j -> j.subject("kc-maya")).authorities(new SimpleGrantedAuthority("ROLE_STOCK_WRITE_OFF"));
    }

    private UUID stocked(int qty) {
        UUID variant = UUID.randomUUID();
        StockTestData.stock(stock, variant, qty);
        return variant;
    }

    private static String body(UUID request, UUID variant, int qty, String reason) {
        return "{\"requestId\":\"" + request + "\",\"variantId\":\"" + variant + "\",\"qty\":" + qty + ",\"reason\":\"" + reason + "\"}";
    }

    private MvcResult writeOff(UUID variant, int qty, String reason) throws Exception {
        return mvc.perform(post(PATH).with(owner()).contentType(MediaType.APPLICATION_JSON)
                        .content(body(UUID.randomUUID(), variant, qty, reason)))
                .andExpect(status().isCreated()).andReturn();
    }

    @Test
    @DisplayName("the owner writes 2 pieces off: 201, the new counts, where to read it, and no cost anywhere in the answer")
    void theOwnerWritesOff() throws Exception {
        UUID variant = stocked(10);

        MvcResult result = mvc.perform(post(PATH).with(owner()).contentType(MediaType.APPLICATION_JSON)
                        .content(body(UUID.randomUUID(), variant, 2, "WRITE_OFF_LOST")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.variantId").value(variant.toString()))
                .andExpect(jsonPath("$.reason").value("WRITE_OFF_LOST"))
                .andExpect(jsonPath("$.qty").value(2))
                .andExpect(jsonPath("$.onHand").value(8))
                .andExpect(jsonPath("$.available").value(8))
                .andExpect(header().string("Location", containsString("/api/v1/admin/inventory/write-offs/")))
                .andReturn();

        assertThat(result.getResponse().getContentAsString().toLowerCase()).doesNotContain("cost").doesNotContain("price");
        String id = JsonPath.read(result.getResponse().getContentAsString(), "$.actionId");
        mvc.perform(get(PATH + "/" + id).with(owner())).andExpect(status().isOk())
                .andExpect(jsonPath("$.reason").value("WRITE_OFF_LOST")).andExpect(jsonPath("$.outstanding").value(2));
    }

    @Test
    @DisplayName("the same request sent again is 200 with the same action, and takes nothing more")
    void aRetryIsAnswered() throws Exception {
        UUID variant = stocked(10);
        UUID request = UUID.randomUUID();
        String payload = body(request, variant, 2, "GIVEN_AWAY_GIFT");

        MvcResult first = mvc.perform(post(PATH).with(owner()).contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isCreated()).andReturn();
        mvc.perform(post(PATH).with(owner()).contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actionId").value((String) JsonPath.read(first.getResponse().getContentAsString(), "$.actionId")))
                .andExpect(jsonPath("$.onHand").value(8));

        assertThat(inventories.findById(variant).orElseThrow().onHand()).isEqualTo(8);
    }

    @Test
    @DisplayName("only the owner's capability gets in: INVENTORY_ADMIN (which a manager holds), a customer and a bookkeeper are refused, and no token is 401")
    void whoMayWriteOff() throws Exception {
        UUID variant = stocked(10);
        String payload = body(UUID.randomUUID(), variant, 1, "WRITE_OFF_LOST");

        for (String role : new String[] {"ROLE_INVENTORY_ADMIN", "ROLE_USER", "ROLE_COST_VIEWER", "ROLE_FINANCE_ADMIN"}) {
            mvc.perform(post(PATH).with(jwt().authorities(new SimpleGrantedAuthority(role))).contentType(MediaType.APPLICATION_JSON).content(payload))
                    .andExpect(status().isForbidden());
            mvc.perform(get(PATH).with(jwt().authorities(new SimpleGrantedAuthority(role)))).andExpect(status().isForbidden());
        }
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(payload)).andExpect(status().isUnauthorized());
        assertThat(inventories.findById(variant).orElseThrow().onHand()).isEqualTo(10);
    }

    @Test
    @DisplayName("a malformed request is 400 naming the field: no request id, no quantity, a reason that is not on the list")
    void validation() throws Exception {
        UUID variant = stocked(10);

        mvc.perform(post(PATH).with(owner()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"variantId\":\"" + variant + "\",\"qty\":1,\"reason\":\"WRITE_OFF_LOST\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[0].field").value("requestId"));
        mvc.perform(post(PATH).with(owner()).contentType(MediaType.APPLICATION_JSON).content(body(UUID.randomUUID(), variant, 0, "WRITE_OFF_LOST")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[0].field").value("qty"));
        mvc.perform(post(PATH).with(owner()).contentType(MediaType.APPLICATION_JSON).content(body(UUID.randomUUID(), variant, 1, "SALE")))
                .andExpect(status().isBadRequest());
        mvc.perform(post(PATH).with(owner()).contentType(MediaType.APPLICATION_JSON).content(body(UUID.randomUUID(), variant, 1, "STOLEN_BY_ELVES")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value(containsString("WRITE_OFF_STOLEN")));
        assertThat(inventories.findById(variant).orElseThrow().onHand()).isEqualTo(10);
    }

    @Test
    @DisplayName("more than is available is 409 (a reserved piece belongs to a customer), and an unknown variant is 404")
    void conflictsAndMissing() throws Exception {
        UUID variant = stocked(10);
        Inventory inv = inventories.findById(variant).orElseThrow();
        inv.reserve(9);
        inventories.saveAndFlush(inv);

        mvc.perform(post(PATH).with(owner()).contentType(MediaType.APPLICATION_JSON).content(body(UUID.randomUUID(), variant, 2, "WRITE_OFF_BROKEN")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.type").value("https://oriah.dev/problems/insufficient-stock"));
        mvc.perform(post(PATH).with(owner()).contentType(MediaType.APPLICATION_JSON).content(body(UUID.randomUUID(), UUID.randomUUID(), 1, "WRITE_OFF_LOST")))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("a give-away is taken back: 201, the units return, and what is still out is told")
    void aTakeBack() throws Exception {
        UUID variant = stocked(10);
        MvcResult gift = writeOff(variant, 4, "GIVEN_AWAY_MODEL");
        String id = JsonPath.read(gift.getResponse().getContentAsString(), "$.actionId");

        mvc.perform(post(PATH + "/" + id + "/take-back").with(owner()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + UUID.randomUUID() + "\",\"qty\":3,\"note\":\"came back from the shoot\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.writeOffActionId").value(id))
                .andExpect(jsonPath("$.qty").value(3))
                .andExpect(jsonPath("$.outstanding").value(1))
                .andExpect(jsonPath("$.onHand").value(9));

        mvc.perform(get(PATH).with(owner()).param("variantId", variant.toString())).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].qty").value(4))
                .andExpect(jsonPath("$.content[0].takenBack").value(3))
                .andExpect(jsonPath("$.content[0].outstanding").value(1))
                .andExpect(jsonPath("$.content[0].sku").exists());
    }

    @Test
    @DisplayName("a take-back of more than is out is 409; of an unknown action 404; of a sale 409")
    void takeBackRefusals() throws Exception {
        UUID variant = stocked(10);
        MvcResult lost = writeOff(variant, 1, "WRITE_OFF_LOST");
        String id = JsonPath.read(lost.getResponse().getContentAsString(), "$.actionId");

        mvc.perform(post(PATH + "/" + id + "/take-back").with(owner()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + UUID.randomUUID() + "\",\"qty\":2}"))
                .andExpect(status().isConflict());
        mvc.perform(post(PATH + "/" + UUID.randomUUID() + "/take-back").with(owner()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + UUID.randomUUID() + "\",\"qty\":1}"))
                .andExpect(status().isNotFound());
        Inventory inv = inventories.findById(variant).orElseThrow();
        inv.reserve(1);
        inventories.saveAndFlush(inv);
        UUID sale = UUID.randomUUID();
        stock.consume(variant, 1, sale, null, "user:kc-maya");
        mvc.perform(post(PATH + "/" + sale + "/take-back").with(owner()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + UUID.randomUUID() + "\",\"qty\":1}"))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("the list asks for a page the way every admin list does: a size over 200 is refused, not clamped")
    void listPaging() throws Exception {
        mvc.perform(get(PATH).with(owner()).param("size", "201")).andExpect(status().isBadRequest());
        mvc.perform(get(PATH).with(owner()).param("page", "-1")).andExpect(status().isBadRequest());
        mvc.perform(get(PATH).with(owner())).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
    }
}
