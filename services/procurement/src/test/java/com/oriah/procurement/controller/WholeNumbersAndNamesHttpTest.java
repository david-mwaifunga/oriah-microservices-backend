package com.oriah.procurement.controller;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;
import com.oriah.procurement.ProcurementTestData;
import com.oriah.procurement.TestcontainersConfiguration;
import com.oriah.procurement.client.CatalogClient;
import com.oriah.procurement.client.CatalogClient.VariantLookupResult;
import com.oriah.procurement.client.CatalogClient.VariantLookupResult.Entry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Two things a valid request can do that used to go wrong, found by review:
 *
 * <ul>
 *   <li><b>A fraction where a whole number belongs.</b> The platform's JSON reader turns
 *       {@code 1.9} into the whole number {@code 1} before any validation sees it, so an order for
 *       "1.9" rings was recorded as one, and a {@code version} of {@code 0.9} passed as 0 and
 *       defeated the stale-copy check. Stock and versions are counted in whole numbers: a fraction
 *       is refused (400), never rounded.</li>
 *   <li><b>A long name from the catalogue.</b> A variant's label is the product title (up to 200
 *       characters) plus one "axis value" pair per option axis (up to 64 each), so it has no
 *       practical limit. It used to be a {@code VARCHAR(300)}, and a perfectly valid product made
 *       placing an order a 500.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false"
})
class WholeNumbersAndNamesHttpTest {

    static final String ORDERS = "/api/v1/admin/procurement/purchase-orders";
    static final String CONSIGNMENTS = "/api/v1/admin/procurement/consignments";
    static final String SUPPLIERS = "/api/v1/admin/procurement/suppliers";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private CatalogClient catalog;

    private final Map<UUID, Entry> known = new HashMap<>();

    @BeforeEach
    void clean() {
        ProcurementTestData.wipe(jdbc);
        Mockito.reset(catalog);
        known.clear();
        when(catalog.lookup(any())).thenAnswer(call -> {
            CatalogClient.VariantLookupBody body = call.getArgument(0);
            return new VariantLookupResult(body.variantIds().stream()
                    .map(id -> known.getOrDefault(id, new Entry(id, false, null, null, null, null, null, null, null, null)))
                    .toList());
        });
    }

    static RequestPostProcessor maya() {
        return jwt().jwt(j -> j.subject("kc-maya")).authorities(new SimpleGrantedAuthority("ROLE_PROCUREMENT_ADMIN"));
    }

    private UUID variant(String variantTitle) {
        UUID id = UUID.randomUUID();
        known.put(id, new Entry(id, true, UUID.randomUUID(), "A ring", variantTitle, "SKU-" + id.toString().substring(0, 8),
                "SELLABLE", "DRAFT", true, false));
        return id;
    }

    private String supplier() throws Exception {
        MvcResult created = mvc.perform(post(SUPPLIERS).with(maya()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Whole Numbers " + UUID.randomUUID().toString().substring(0, 8) + "\",\"country\":\"AE\"}"))
                .andExpect(status().isCreated()).andReturn();
        return JsonPath.read(created.getResponse().getContentAsString(), "$.id");
    }

    private ResultActions raw(String path, String body) throws Exception {
        return mvc.perform(post(path).with(maya()).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    private String placedOrder() throws Exception {
        UUID v = variant("Ring - M");
        MvcResult placed = raw(ORDERS, "{\"supplierId\":\"" + supplier() + "\",\"lines\":[{\"variantId\":\"" + v
                + "\",\"quantity\":2,\"totalPaid\":100.00}]}").andExpect(status().isCreated()).andReturn();
        return JsonPath.read(placed.getResponse().getContentAsString(), "$.id");
    }

    // ------------------------------------------------------ whole numbers

    @Test
    @DisplayName("an order for 1.9 rings is refused, not recorded as one")
    void aFractionalQuantityIsRefused() throws Exception {
        UUID v = variant("Ring - M");

        raw(ORDERS, "{\"supplierId\":\"" + supplier() + "\",\"lines\":[{\"variantId\":\"" + v
                + "\",\"quantity\":1.9,\"totalPaid\":100.00}]}")
                .andExpect(status().isBadRequest());

        assertThat(count("purchase_order")).isZero();
    }

    @Test
    @DisplayName("a version of 0.9 does not pass as 0: the stale-copy check cannot be defeated with a fraction")
    void aFractionalVersionIsRefused() throws Exception {
        String order = placedOrder();

        raw(ORDERS + "/" + order + "/cancel", "{\"version\":0.9}").andExpect(status().isBadRequest());

        mvc.perform(get(ORDERS + "/" + order).with(maya()))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.version").value(0));
    }

    @Test
    @DisplayName("a fraction is refused wherever a count, a version or a weight in grams is expected")
    void fractionsAreRefusedEverywhere() throws Exception {
        String order = placedOrder();
        UUID v = variant("Ring - P");

        // The order's version, in a consignment's list of orders.
        raw(CONSIGNMENTS, "{\"purchaseOrders\":[{\"id\":\"" + order + "\",\"version\":0.5}]}")
                .andExpect(status().isBadRequest());
        assertThat(count("consignment")).isZero();
        // A count and a weight on opening stock.
        raw(CONSIGNMENTS, "{\"opening\":true,\"lines\":[{\"variantId\":\"" + v + "\",\"quantity\":2.5,\"totalCost\":10.00}]}")
                .andExpect(status().isBadRequest());
        raw(CONSIGNMENTS, "{\"opening\":true,\"lines\":[{\"variantId\":\"" + v
                + "\",\"quantity\":2,\"totalCost\":10.00,\"chargeableWeightGrams\":12.5}]}")
                .andExpect(status().isBadRequest());
        assertThat(count("consignment")).isZero();

        // The consignment's own version, on a change.
        MvcResult created = raw(CONSIGNMENTS, "{\"purchaseOrders\":[{\"id\":\"" + order + "\",\"version\":0}]}")
                .andExpect(status().isCreated()).andReturn();
        String consignment = JsonPath.read(created.getResponse().getContentAsString(), "$.id");
        mvc.perform(put(CONSIGNMENTS + "/" + consignment + "/cost-lines").with(maya()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0.4,\"costLines\":[]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get(CONSIGNMENTS + "/" + consignment).with(maya())).andExpect(jsonPath("$.version").value(0));
    }

    @Test
    @DisplayName("a whole number is still accepted, however it is written")
    void wholeNumbersStillWork() throws Exception {
        UUID v = variant("Ring - M");

        raw(ORDERS, "{\"supplierId\":\"" + supplier() + "\",\"lines\":[{\"variantId\":\"" + v
                + "\",\"quantity\":3,\"totalPaid\":100.00}]}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.lines[0].quantity").value(3));
    }

    // ---------------------------------------------------------- long names

    private static String longTitle() {
        // 200-character product title, " - ", and three option axes of 64 + 1 + 64 characters each.
        return "T".repeat(200) + " - " + String.join(", ", "A".repeat(64) + " " + "a".repeat(64),
                "B".repeat(64) + " " + "b".repeat(64), "C".repeat(64) + " " + "c".repeat(64));
    }

    @Test
    @DisplayName("an order for a variant whose catalogue label is nearly 600 characters is placed, and the label is kept whole")
    void aLongLabelOnAnOrder() throws Exception {
        String title = longTitle();
        assertThat(title.length()).as("well past the old VARCHAR(300)").isGreaterThan(500);
        UUID v = variant(title);

        MvcResult placed = raw(ORDERS, "{\"supplierId\":\"" + supplier() + "\",\"lines\":[{\"variantId\":\"" + v
                + "\",\"quantity\":1,\"totalPaid\":10.00}]}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.lines[0].variantTitle").value(title))
                .andReturn();

        String id = JsonPath.read(placed.getResponse().getContentAsString(), "$.id");
        mvc.perform(get(ORDERS + "/" + id).with(maya())).andExpect(jsonPath("$.lines[0].variantTitle").value(title));
    }

    @Test
    @DisplayName("and the same for a line of the opening stock, and for a consignment made from such an order")
    void aLongLabelOnConsignments() throws Exception {
        String title = longTitle();
        UUID v = variant(title);
        UUID w = variant(title + " second");

        raw(CONSIGNMENTS, "{\"opening\":true,\"lines\":[{\"variantId\":\"" + v + "\",\"quantity\":2,\"totalCost\":10.00}]}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.lines[0].variantTitle").value(title));

        MvcResult placed = raw(ORDERS, "{\"supplierId\":\"" + supplier() + "\",\"lines\":[{\"variantId\":\"" + w
                + "\",\"quantity\":1,\"totalPaid\":10.00}]}").andExpect(status().isCreated()).andReturn();
        String order = JsonPath.read(placed.getResponse().getContentAsString(), "$.id");
        raw(CONSIGNMENTS, "{\"purchaseOrders\":[{\"id\":\"" + order + "\",\"version\":0}]}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.lines[0].variantTitle").value(title + " second"));
    }

    // ------------------------------------------------- a long opening list

    @Test
    @DisplayName("the opening stock may name 200 variants: the catalogue is asked in batches of 100, not refused")
    void aLongOpeningList() throws Exception {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 150; i++) {
            lines.add("{\"variantId\":\"" + variant("Ring " + i) + "\",\"quantity\":1,\"totalCost\":5.00}");
        }
        // The real catalogue refuses a lookup of more than 100 with a 400: so does this stand-in.
        // doAnswer, not when(...): the default stub answers with its argument, which a when() call would pass as null.
        Mockito.doAnswer(call -> {
            CatalogClient.VariantLookupBody body = call.getArgument(0);
            if (body.variantIds().size() > 100) {
                throw new IllegalStateException("catalog answers at most 100 variants at a time");
            }
            return new VariantLookupResult(body.variantIds().stream().map(known::get).toList());
        }).when(catalog).lookup(any());

        raw(CONSIGNMENTS, "{\"opening\":true,\"lines\":[" + String.join(",", lines) + "]}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.lines.length()").value(150));
    }
}
