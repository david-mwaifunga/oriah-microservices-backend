package com.oriah.procurement.controller;

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
import com.oriah.procurement.TestcontainersConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Creating, reading and deleting a supplier through the real HTTP stack, the real
 * security rules and a real PostgreSQL.
 *
 * <p>A supplier is "name, country, who you deal with there, how you pay them"
 * (CLIENT_DECISIONS 4.1). Everything here is what a staff member with the
 * procurement capability sees; who may call at all is the security tests' job.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false"
})
class SupplierCrudTest {

    static final String PATH = "/api/v1/admin/procurement/suppliers";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM suppliers");
    }

    static RequestPostProcessor maya() {
        return jwt().jwt(j -> j.subject("kc-maya"))
                .authorities(new SimpleGrantedAuthority("ROLE_PROCUREMENT_ADMIN"));
    }

    static String supplier(String name, String country) {
        return "{\"name\":\"" + name + "\",\"country\":\"" + country + "\"}";
    }

    private String create(String body) throws Exception {
        MvcResult created = mvc.perform(post(PATH).with(maya()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(created.getResponse().getContentAsString(), "$.id");
    }

    // ------------------------------------------------------------------ create

    @Test
    @DisplayName("create answers 201 with a Location, and the supplier as it was stored, who made it and when")
    void createAnswers201() throws Exception {
        MvcResult result = mvc.perform(post(PATH).with(maya()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Fycargo","country":"CN","contactName":"Mr Li",
                                 "contactDetails":"WhatsApp +86 000","paymentNotes":"Bank transfer, in full"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.name").value("Fycargo"))
                .andExpect(jsonPath("$.country").value("CN"))
                .andExpect(jsonPath("$.contactName").value("Mr Li"))
                .andExpect(jsonPath("$.contactDetails").value("WhatsApp +86 000"))
                .andExpect(jsonPath("$.paymentNotes").value("Bank transfer, in full"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.createdBy").value("kc-maya"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andReturn();

        String id = JsonPath.read(result.getResponse().getContentAsString(), "$.id");
        // A path, exactly as every other service answers. Not an absolute URL built from the
        // request: behind the gateway that would name the service's own host (procurement:8093),
        // which a browser outside the network cannot reach.
        assertThat(result.getResponse().getHeader("Location")).isEqualTo(PATH + "/" + id);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM suppliers", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("only the name and the country are needed; the rest is optional")
    void onlyNameAndCountryAreNeeded() throws Exception {
        mvc.perform(post(PATH).with(maya()).contentType(MediaType.APPLICATION_JSON).content(supplier("Mercury", "AE")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.contactName").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.contactDetails").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.paymentNotes").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    @DisplayName("the name is trimmed and the country stored in upper case")
    void tidied() throws Exception {
        mvc.perform(post(PATH).with(maya()).contentType(MediaType.APPLICATION_JSON)
                        .content(supplier("  Mercury Freight  ", " ae ")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Mercury Freight"))
                .andExpect(jsonPath("$.country").value("AE"));
    }

    @Test
    @DisplayName("a supplier that breaks a rule is 400 with fieldErrors naming every field at fault, and nothing is saved")
    void invalidSupplierIs400WithFieldErrors() throws Exception {
        mvc.perform(post(PATH).with(maya()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"country\":\"UK\",\"contactDetails\":\"" + "x".repeat(501) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("https://oriah.dev/problems/validation"))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("name")))
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("country")))
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("contactDetails")));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM suppliers", Integer.class)).isZero();
    }

    @Test
    @DisplayName("a name over 120 characters and a country that is not an ISO code are each a field error")
    void limitsAreFieldErrors() throws Exception {
        mvc.perform(post(PATH).with(maya()).contentType(MediaType.APPLICATION_JSON)
                        .content(supplier("x".repeat(121), "ZMB")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("name")))
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("country")));
    }

    @Test
    @DisplayName("a NUL character in any text field is a 400 naming the field, not a 500, and nothing is saved")
    void nulIsRefusedOnCreate() throws Exception {
        for (String field : new String[] {"name", "contactName", "contactDetails", "paymentNotes"}) {
            String name = field.equals("name") ? "a\\u0000b" : "Fycargo";
            String body = "{\"name\":\"" + name + "\",\"country\":\"CN\""
                    + (field.equals("name") ? "" : ",\"" + field + "\":\"a\\u0000b\"") + "}";

            mvc.perform(post(PATH).with(maya()).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("https://oriah.dev/problems/validation"))
                    .andExpect(jsonPath("$.fieldErrors[*].field", hasItem(field)));
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM suppliers", Integer.class)).isZero();
    }

    @Test
    @DisplayName("limits count the text as sent, so 120 characters plus a trailing space is over the name limit")
    void paddingCountsTowardsTheLimit() throws Exception {
        // Known and harmless: the aggregate counts after trimming, the request before. Pinned so a
        // change to either is a decision, not an accident.
        mvc.perform(post(PATH).with(maya()).contentType(MediaType.APPLICATION_JSON)
                        .content(supplier("x".repeat(120) + " ", "CN")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("name")));
        mvc.perform(post(PATH).with(maya()).contentType(MediaType.APPLICATION_JSON)
                        .content(supplier("x".repeat(120), "CN")))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("a duplicate name, in any letter case, is 409 and the first supplier is untouched")
    void duplicateNameIs409() throws Exception {
        create(supplier("Fycargo", "CN"));

        for (String again : new String[] {"Fycargo", "FYCARGO", " fycargo "}) {
            mvc.perform(post(PATH).with(maya()).contentType(MediaType.APPLICATION_JSON).content(supplier(again, "AE")))
                    .andExpect(status().isConflict())
                    .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                    .andExpect(jsonPath("$.type").value("https://oriah.dev/problems/conflict"))
                    // The detail names the name the caller sent (their own input), tidied.
                    .andExpect(jsonPath("$.detail").value(containsString(again.strip())));
        }

        assertThat(jdbc.queryForObject("SELECT count(*) FROM suppliers", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT country FROM suppliers", String.class)).isEqualTo("CN");
    }

    @Test
    @DisplayName("a deleted supplier's name can be used again")
    void aDeletedNameIsFree() throws Exception {
        String id = create(supplier("Fycargo", "CN"));
        mvc.perform(delete(PATH + "/" + id).with(maya())).andExpect(status().isNoContent());

        create(supplier("Fycargo", "CN"));
    }

    // --------------------------------------------------------------------- get

    @Test
    @DisplayName("get answers 200 with the supplier")
    void getAnswers200() throws Exception {
        String id = create("{\"name\":\"Fycargo\",\"country\":\"CN\",\"paymentNotes\":\"Cash\"}");

        mvc.perform(get(PATH + "/" + id).with(maya()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.name").value("Fycargo"))
                .andExpect(jsonPath("$.paymentNotes").value("Cash"));
    }

    @Test
    @DisplayName("an unknown supplier is 404 with the platform's problem body, and a malformed id is 400")
    void unknownAndMalformedIds() throws Exception {
        mvc.perform(get(PATH + "/" + UUID.randomUUID()).with(maya()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("https://oriah.dev/problems/not-found"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());

        mvc.perform(get(PATH + "/not-a-uuid").with(maya()))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------ delete

    @Test
    @DisplayName("delete answers 204, the supplier is then gone from the API, and the row is kept, marked deleted")
    void deleteIsSoft() throws Exception {
        String id = create(supplier("Fycargo", "CN"));

        mvc.perform(delete(PATH + "/" + id).with(maya())).andExpect(status().isNoContent());

        mvc.perform(get(PATH + "/" + id).with(maya())).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT is_deleted FROM suppliers WHERE id = ?::uuid", Boolean.class, id))
                .isTrue();
    }

    @Test
    @DisplayName("deleting an unknown or an already deleted supplier is 404")
    void deleteUnknownIs404() throws Exception {
        mvc.perform(delete(PATH + "/" + UUID.randomUUID()).with(maya())).andExpect(status().isNotFound());

        String id = create(supplier("Fycargo", "CN"));
        mvc.perform(delete(PATH + "/" + id).with(maya())).andExpect(status().isNoContent());
        mvc.perform(delete(PATH + "/" + id).with(maya())).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("what the 409 says does not leak the database's own message")
    void conflictDoesNotLeakTheDriverMessage() throws Exception {
        create(supplier("Fycargo", "CN"));

        mvc.perform(post(PATH).with(maya()).contentType(MediaType.APPLICATION_JSON).content(supplier("fycargo", "CN")))
                .andExpect(status().isConflict())
                .andExpect(content().string(not(containsString("uq_suppliers_name_live"))))
                .andExpect(content().string(not(containsString("duplicate key"))));
    }
}
