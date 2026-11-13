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
import com.oriah.inventory.service.BoxCommitmentService;
import com.oriah.inventory.service.BoxCommitmentService.ProductSnapshot;
import com.oriah.inventory.service.StockService;
import com.oriah.inventory.service.StockTestData;

import static com.oriah.inventory.service.StockTestData.box;
import static com.oriah.inventory.service.StockTestData.context;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Boxes set aside for a product, over HTTP: INVENTORY_ADMIN (the plan's gate for box commitments), one record per product on its
 * default box, the box counts that say what is free, and a write-off that names the products whose boxes it cut.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"eureka.client.enabled=false"})
class BoxCommitmentHttpTest {

    private static final String COMMITMENTS = "/api/v1/admin/inventory/box-commitments";

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

    private UUID box;

    @BeforeEach
    void twoHundredBoxes() {
        StockTestData.wipe(jdbc);
        jdbc.execute("DELETE FROM box_commitment");
        jdbc.execute("DELETE FROM product_state");
        box = UUID.randomUUID();
        stock.applyReceipt(box(box, "2026-11-09T08:00:00.123456Z"), context(7201));
    }

    private static RequestPostProcessor manager() {
        return jwt().jwt(j -> j.subject("kc-maya")).authorities(new SimpleGrantedAuthority("ROLE_INVENTORY_ADMIN"));
    }

    private UUID product() {
        UUID product = UUID.randomUUID();
        commitments.applyProduct(new ProductSnapshot(product, "SELLABLE", "ACTIVE", box, 0, false), UUID.randomUUID(), "system:test");
        return product;
    }

    private org.springframework.test.web.servlet.ResultActions set(UUID product, int quantity) throws Exception {
        return mvc.perform(put(COMMITMENTS + "/" + product).with(manager()).contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":" + quantity + "}"));
    }

    @Test
    @DisplayName("setting boxes aside answers with the record and the box's counts: held, set aside, reserved, free")
    void settingAside() throws Exception {
        UUID gold = product();

        set(gold, 50).andExpect(status().isOk())
                .andExpect(jsonPath("$.productId").value(gold.toString()))
                .andExpect(jsonPath("$.boxVariantId").value(box.toString()))
                .andExpect(jsonPath("$.quantity").value(50))
                .andExpect(jsonPath("$.committedBy").value("user:kc-maya"))
                .andExpect(jsonPath("$.committedAt").exists())
                .andExpect(jsonPath("$.box.onHand").value(200))
                .andExpect(jsonPath("$.box.committed").value(50))
                .andExpect(jsonPath("$.box.reserved").value(0))
                .andExpect(jsonPath("$.box.free").value(150));
    }

    @Test
    @DisplayName("more than is free is a 409 that says how many are: `insufficient-free-packaging` with `available`")
    void notEnoughFree() throws Exception {
        set(product(), 190).andExpect(status().isOk());

        set(product(), 11).andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://oriah.dev/problems/insufficient-free-packaging"))
                .andExpect(jsonPath("$.available").value(10));
    }

    @Test
    @DisplayName("a body that is not a positive whole number of boxes is 400; a product inventory has not heard of is 409, not 404")
    void theOtherAnswers() throws Exception {
        UUID gold = product();
        for (String bad : new String[] {"{\"quantity\":0}", "{\"quantity\":-5}", "{\"quantity\":2.5}", "{}", "{\"quantity\":\"many\"}"}) {
            mvc.perform(put(COMMITMENTS + "/" + gold).with(manager()).contentType(MediaType.APPLICATION_JSON).content(bad)).andExpect(status().isBadRequest());
        }
        set(UUID.randomUUID(), 5).andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://oriah.dev/problems/product-state-not-received"));
    }

    @Test
    @DisplayName("releasing is 204 and the boxes are free again; releasing what is not there is 404")
    void releasing() throws Exception {
        UUID gold = product();
        set(gold, 50).andExpect(status().isOk());

        mvc.perform(delete(COMMITMENTS + "/" + gold).with(manager())).andExpect(status().isNoContent());
        mvc.perform(delete(COMMITMENTS + "/" + gold).with(manager())).andExpect(status().isNotFound());

        mvc.perform(get("/api/v1/admin/inventory").with(manager())).andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.variantId=='" + box + "')].committed").value(0))
                .andExpect(jsonPath("$.content[?(@.variantId=='" + box + "')].free").value(200));
    }

    @Test
    @DisplayName("the records are listed by product or by box, and each product's history says what was done to its boxes, by whom and when")
    void readingThemBack() throws Exception {
        UUID gold = product();
        UUID silver = product();
        set(gold, 50).andExpect(status().isOk());
        set(silver, 20).andExpect(status().isOk());
        set(gold, 40).andExpect(status().isOk());

        mvc.perform(get(COMMITMENTS).with(manager()).param("boxVariantId", box.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.commitments", hasSize(2)));
        mvc.perform(get(COMMITMENTS).with(manager()).param("productId", gold.toString()))
                .andExpect(jsonPath("$.commitments", hasSize(1)))
                .andExpect(jsonPath("$.commitments[0].quantity").value(40));
        mvc.perform(get(COMMITMENTS + "/" + gold + "/history").with(manager()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changes", hasSize(2)))
                .andExpect(jsonPath("$.changes[0].kind").value("ALLOCATE"))
                .andExpect(jsonPath("$.changes[0].qtyDelta").value(50))
                .andExpect(jsonPath("$.changes[1].kind").value("REPLACE"))
                .andExpect(jsonPath("$.changes[1].qtyDelta").value(-10))
                .andExpect(jsonPath("$.changes[1].quantityAfter").value(40))
                .andExpect(jsonPath("$.changes[1].actor").value("user:kc-maya"));
    }

    @Test
    @DisplayName("the stock list says how many of each box are set aside and free")
    void theStockListCarriesTheNewCounts() throws Exception {
        set(product(), 50).andExpect(status().isOk());

        mvc.perform(get("/api/v1/admin/inventory").with(manager()))
                .andExpect(jsonPath("$.content[?(@.variantId=='" + box + "')].onHand").value(200))
                .andExpect(jsonPath("$.content[?(@.variantId=='" + box + "')].committed").value(50))
                .andExpect(jsonPath("$.content[?(@.variantId=='" + box + "')].free").value(150));
    }

    @Test
    @DisplayName("a write-off that cuts into set-aside boxes names the products and how many each lost, and reports what is set aside and free now")
    void aWriteOffNamesTheProducts() throws Exception {
        UUID gold = product();
        set(gold, 190).andExpect(status().isOk());       // 10 free
        RequestPostProcessor owner = jwt().jwt(j -> j.subject("kc-owner")).authorities(new SimpleGrantedAuthority("ROLE_STOCK_WRITE_OFF"));

        mvc.perform(post("/api/v1/admin/inventory/write-offs").with(owner).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + UUID.randomUUID() + "\",\"variantId\":\"" + box + "\",\"qty\":25,\"reason\":\"WRITE_OFF_BROKEN\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.onHand").value(175))
                .andExpect(jsonPath("$.committed").value(175))
                .andExpect(jsonPath("$.free").value(0))
                .andExpect(jsonPath("$.cutCommitments", hasSize(1)))
                .andExpect(jsonPath("$.cutCommitments[0].productId").value(gold.toString()))
                .andExpect(jsonPath("$.cutCommitments[0].boxVariantId").value(box.toString()))
                .andExpect(jsonPath("$.cutCommitments[0].cut").value(15));
    }

    @Test
    @DisplayName("INVENTORY_ADMIN only: a write-off owner, a bookkeeper, a sales assistant and a customer are refused; no token is 401")
    void who() throws Exception {
        UUID gold = product();

        for (String role : new String[] {"ROLE_STOCK_WRITE_OFF", "ROLE_COST_VIEWER", "ROLE_SALES_AGENT", "ROLE_USER", "ROLE_CATALOG_ADMIN"}) {
            RequestPostProcessor caller = jwt().authorities(new SimpleGrantedAuthority(role));
            mvc.perform(put(COMMITMENTS + "/" + gold).with(caller).contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":1}")).andExpect(status().isForbidden());
            mvc.perform(delete(COMMITMENTS + "/" + gold).with(caller)).andExpect(status().isForbidden());
            mvc.perform(get(COMMITMENTS).with(caller)).andExpect(status().isForbidden());
        }
        mvc.perform(put(COMMITMENTS + "/" + gold).contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":1}")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("nothing about cost is in any of these replies: they are counts and names")
    void noCostAnywhere() throws Exception {
        UUID gold = product();

        String reply = set(gold, 50).andReturn().getResponse().getContentAsString();
        String history = mvc.perform(get(COMMITMENTS + "/" + gold + "/history").with(manager())).andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat((reply + history).toLowerCase()).doesNotContain("cost").doesNotContain("78.9");
    }
}
