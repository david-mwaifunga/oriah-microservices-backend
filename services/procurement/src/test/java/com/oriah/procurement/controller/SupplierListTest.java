package com.oriah.procurement.controller;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.oriah.procurement.ProcurementTestData;
import com.oriah.procurement.TestcontainersConfiguration;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Listing suppliers: what comes back, in what order, filtered how, and what is
 * refused.
 *
 * <p>The shape is the platform's page (payment's admin list answers the same
 * way): {@code content} and {@code totalElements}. The order is fixed, by name
 * ignoring letter case and then id, so that a page of a growing list does not
 * shuffle; the caller cannot choose one, and asking is a 400 rather than being
 * quietly ignored. Sizes are bounded and an out-of-range size is a 400 rather than
 * being silently clamped, which would hide the caller's mistake.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false"
})
class SupplierListTest {

    private static final String PATH = SupplierCrudTest.PATH;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void clean() {
        ProcurementTestData.wipe(jdbc);
    }

    private void insert(String name, boolean active, boolean deleted) {
        jdbc.update("INSERT INTO suppliers (id, name, country, active, is_deleted) VALUES (?, ?, 'ZM', ?, ?)",
                UUID.randomUUID(), name, active, deleted);
    }

    private void insert(String name) {
        insert(name, true, false);
    }

    @Test
    @DisplayName("nothing has been added yet: 200 with an empty page, because nothing is seeded")
    void emptyToBeginWith() throws Exception {
        mvc.perform(get(PATH).with(SupplierCrudTest.maya()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value(empty()))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    @DisplayName("ordered by name ignoring letter case, then id, and a deleted supplier is not listed")
    void orderedByNameIgnoringCase() throws Exception {
        insert("banana");
        insert("Apple Two");
        insert("Cherry");
        insert("Apple");
        insert("Zebra", true, true);

        mvc.perform(get(PATH).with(SupplierCrudTest.maya()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.content[*].name", contains("Apple", "Apple Two", "banana", "Cherry")));
    }

    @Test
    @DisplayName("each entry is the full supplier: id, fields, active, version, audit")
    void entriesAreFullSuppliers() throws Exception {
        insert("Fycargo");

        mvc.perform(get(PATH).with(SupplierCrudTest.maya()))
                .andExpect(jsonPath("$.content[0].id").isNotEmpty())
                .andExpect(jsonPath("$.content[0].name").value("Fycargo"))
                .andExpect(jsonPath("$.content[0].country").value("ZM"))
                .andExpect(jsonPath("$.content[0].active").value(true))
                .andExpect(jsonPath("$.content[0].version").value(0))
                .andExpect(jsonPath("$.content[0].createdAt").isNotEmpty());
    }

    @Test
    @DisplayName("active=true lists only suppliers in use, active=false only those we stopped using")
    void filterByActive() throws Exception {
        insert("In use");
        insert("Stopped", false, false);
        insert("Stopped and deleted", false, true);

        mvc.perform(get(PATH).param("active", "true").with(SupplierCrudTest.maya()))
                .andExpect(jsonPath("$.content[*].name", contains("In use")));
        mvc.perform(get(PATH).param("active", "false").with(SupplierCrudTest.maya()))
                .andExpect(jsonPath("$.content[*].name", contains("Stopped")));
        mvc.perform(get(PATH).with(SupplierCrudTest.maya()))
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    @DisplayName("q finds a name containing the text, ignoring letter case; a blank q is no filter")
    void searchByName() throws Exception {
        insert("Fycargo");
        insert("Cargo Express");
        insert("Mercury");

        mvc.perform(get(PATH).param("q", "CARGO").with(SupplierCrudTest.maya()))
                .andExpect(jsonPath("$.content[*].name", contains("Cargo Express", "Fycargo")));
        mvc.perform(get(PATH).param("q", "xyz").with(SupplierCrudTest.maya()))
                .andExpect(jsonPath("$.content").value(empty()));
        mvc.perform(get(PATH).param("q", "   ").with(SupplierCrudTest.maya()))
                .andExpect(jsonPath("$.totalElements").value(3));
    }

    @Test
    @DisplayName("% and _ in q are ordinary characters, not wildcards")
    void searchCharactersAreLiteral() throws Exception {
        insert("100% Gold");
        insert("A_B Traders");
        insert("AXB Traders");
        insert("Plain");

        mvc.perform(get(PATH).param("q", "%").with(SupplierCrudTest.maya()))
                .andExpect(jsonPath("$.content[*].name", contains("100% Gold")));
        mvc.perform(get(PATH).param("q", "a_b").with(SupplierCrudTest.maya()))
                .andExpect(jsonPath("$.content[*].name", contains("A_B Traders")));
        mvc.perform(get(PATH).param("q", "!").with(SupplierCrudTest.maya()))
                .andExpect(jsonPath("$.content").value(empty()));
    }

    @Test
    @DisplayName("q and active combine")
    void filtersCombine() throws Exception {
        insert("Fycargo");
        insert("Cargo Stopped", false, false);

        mvc.perform(get(PATH).param("q", "cargo").param("active", "false").with(SupplierCrudTest.maya()))
                .andExpect(jsonPath("$.content[*].name", contains("Cargo Stopped")));
    }

    @Test
    @DisplayName("paging: page and size slice the ordered list, and totals describe the whole")
    void paging() throws Exception {
        for (String n : new String[] {"a", "b", "c", "d", "e"}) {
            insert(n);
        }

        mvc.perform(get(PATH).param("page", "1").param("size", "2").with(SupplierCrudTest.maya()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].name", contains("c", "d")))
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.number").value(1))
                .andExpect(jsonPath("$.size").value(2));
    }

    @Test
    @DisplayName("the default page size is 50 and the largest allowed is 100")
    void sizeDefaultsAndLimits() throws Exception {
        for (int i = 0; i < 55; i++) {
            insert(String.format("Supplier %02d", i));
        }

        mvc.perform(get(PATH).with(SupplierCrudTest.maya()))
                .andExpect(jsonPath("$.size").value(50))
                .andExpect(jsonPath("$.totalElements").value(55));
        mvc.perform(get(PATH).param("size", "100").with(SupplierCrudTest.maya()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(55));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"size=0", "size=101", "size=-1", "page=-1", "sort=name", "sort=name,desc"})
    @DisplayName("a parameter out of range, or one this endpoint does not take, is a 400 in the platform's shape")
    void badParametersAreRefused(String query) throws Exception {
        insert("Fycargo");

        mvc.perform(get(PATH + "?" + query).with(SupplierCrudTest.maya()))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("https://oriah.dev/problems/bad-request"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"active=maybe", "size=abc", "page=1.5"})
    @DisplayName("a parameter that cannot be read as its type is a 400 that names the parameter, as everywhere on the platform")
    void unreadableParametersNameTheParameter(String query) throws Exception {
        // The shared advice answers a type mismatch as 'validation' with a fieldErrors
        // entry (not 'bad-request'), so the storefront can attach it to the input.
        String parameter = query.substring(0, query.indexOf('='));

        mvc.perform(get(PATH + "?" + query).with(SupplierCrudTest.maya()))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("https://oriah.dev/problems/validation"))
                .andExpect(jsonPath("$.fieldErrors[*].field", contains(parameter)))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"page=1073741824&size=2", "page=2147483647&size=50", "page=100000000&size=100"})
    @DisplayName("a page so far into the list that its offset cannot be represented is a 400, not a 500")
    void aPageBeyondTheLargestOffsetIsRefused(String query) throws Exception {
        // page * size is the row offset PostgreSQL is asked to skip; it must fit in an int, or
        // the query is built with a wrapped-around negative offset and the request fails.
        mvc.perform(get(PATH + "?" + query).with(SupplierCrudTest.maya()))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("https://oriah.dev/problems/bad-request"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    @DisplayName("a far page that is still representable is simply an empty page")
    void aFarPageIsEmpty() throws Exception {
        insert("Fycargo");

        mvc.perform(get(PATH).param("page", "1000").param("size", "100").with(SupplierCrudTest.maya()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value(empty()))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @DisplayName("a NUL character in q is a 400: it must not reach the database, which cannot compare with it")
    void aNulInTheSearchIsRefused() throws Exception {
        mvc.perform(get(PATH).param("q", "a\u0000b").with(SupplierCrudTest.maya()))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("https://oriah.dev/problems/bad-request"));
    }

    @Test
    @DisplayName("a deleted supplier is never found by a search, with or without the active filter")
    void deletedSuppliersAreNotFoundBySearch() throws Exception {
        insert("Cargo Live");
        insert("Cargo Gone", true, true);
        insert("Cargo Stopped Gone", false, true);
        insert("Cargo Stopped", false, false);

        mvc.perform(get(PATH).param("q", "cargo").with(SupplierCrudTest.maya()))
                .andExpect(jsonPath("$.content[*].name", contains("Cargo Live", "Cargo Stopped")));
        mvc.perform(get(PATH).param("q", "cargo").param("active", "true").with(SupplierCrudTest.maya()))
                .andExpect(jsonPath("$.content[*].name", contains("Cargo Live")));
        mvc.perform(get(PATH).param("q", "cargo").param("active", "false").with(SupplierCrudTest.maya()))
                .andExpect(jsonPath("$.content[*].name", contains("Cargo Stopped")));
    }

    @Test
    @DisplayName("% and _ stay ordinary characters when the search is combined with the active filter")
    void wildcardsStayLiteralWithTheActiveFilter() throws Exception {
        insert("100% Gold");
        insert("100% Stopped", false, false);
        insert("100X Gold");
        insert("A_B Traders");
        insert("AXB Traders");

        mvc.perform(get(PATH).param("q", "%").param("active", "true").with(SupplierCrudTest.maya()))
                .andExpect(jsonPath("$.content[*].name", contains("100% Gold")));
        mvc.perform(get(PATH).param("q", "%").param("active", "false").with(SupplierCrudTest.maya()))
                .andExpect(jsonPath("$.content[*].name", contains("100% Stopped")));
        mvc.perform(get(PATH).param("q", "a_b").param("active", "true").with(SupplierCrudTest.maya()))
                .andExpect(jsonPath("$.content[*].name", contains("A_B Traders")));
    }

    @Test
    @DisplayName("the 400 for sort says the order is fixed")
    void sortRefusalExplains() throws Exception {
        mvc.perform(get(PATH + "?sort=name").with(SupplierCrudTest.maya()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("sort")));
    }
}
