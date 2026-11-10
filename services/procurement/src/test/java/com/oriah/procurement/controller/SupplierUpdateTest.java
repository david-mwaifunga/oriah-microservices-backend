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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.jayway.jsonpath.JsonPath;
import com.oriah.procurement.TestcontainersConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Editing a supplier with PATCH: send only what changes.
 *
 * <p>The rule that makes a partial edit safe is that a field can be in one of
 * three states, and each means something different:
 *
 * <ul>
 *   <li><b>left out</b>: unchanged;</li>
 *   <li><b>sent as {@code null} or blank</b>: cleared, for the three optional
 *       fields (a contact who has left, a payment arrangement that no longer
 *       applies);</li>
 *   <li><b>sent with a value</b>: replaced.</li>
 * </ul>
 *
 * <p>The two required fields, {@code name} and {@code country}, can be left out or
 * replaced but never emptied, and {@code active} is a yes or a no, never empty.
 * Without the first distinction "forgot to send it" and "wants it gone" look the
 * same, and an edit of one field silently wipes the others.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false"
})
class SupplierUpdateTest {

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
        jdbc.update("DELETE FROM suppliers");
    }

    /** A supplier with every optional field filled, so a clear or a keep is visible. */
    private String fullSupplier() throws Exception {
        return create("""
                {"name":"Fycargo","country":"CN","contactName":"Mr Li",
                 "contactDetails":"WhatsApp +86 000","paymentNotes":"Bank transfer"}""");
    }

    private String create(String body) throws Exception {
        MvcResult created = mvc.perform(post(PATH).with(SupplierCrudTest.maya())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(created.getResponse().getContentAsString(), "$.id");
    }

    private org.springframework.test.web.servlet.ResultActions patchOf(String id, String body) throws Exception {
        return mvc.perform(patch(PATH + "/" + id).with(SupplierCrudTest.maya())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    // ------------------------------------------------- left out, cleared, replaced

    @Test
    @DisplayName("an empty body changes nothing, and nothing that was left out is touched")
    void emptyBodyChangesNothing() throws Exception {
        String id = fullSupplier();

        patchOf(id, "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Fycargo"))
                .andExpect(jsonPath("$.country").value("CN"))
                .andExpect(jsonPath("$.contactName").value("Mr Li"))
                .andExpect(jsonPath("$.contactDetails").value("WhatsApp +86 000"))
                .andExpect(jsonPath("$.paymentNotes").value("Bank transfer"))
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    @DisplayName("sending one field changes that field only")
    void oneFieldOnly() throws Exception {
        String id = fullSupplier();

        patchOf(id, "{\"contactName\":\"Ms Wang\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contactName").value("Ms Wang"))
                .andExpect(jsonPath("$.name").value("Fycargo"))
                .andExpect(jsonPath("$.country").value("CN"))
                .andExpect(jsonPath("$.contactDetails").value("WhatsApp +86 000"))
                .andExpect(jsonPath("$.paymentNotes").value("Bank transfer"));
    }

    @Test
    @DisplayName("every field can be replaced at once; the name is trimmed and the country upper-cased")
    void everythingReplaced() throws Exception {
        String id = fullSupplier();

        patchOf(id, """
                {"name":"  Mercury Freight ","country":" ae ","contactName":"Sara",
                 "contactDetails":"sara@mercury.example","paymentNotes":"Cash on arrival","active":false}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Mercury Freight"))
                .andExpect(jsonPath("$.country").value("AE"))
                .andExpect(jsonPath("$.contactName").value("Sara"))
                .andExpect(jsonPath("$.contactDetails").value("sara@mercury.example"))
                .andExpect(jsonPath("$.paymentNotes").value("Cash on arrival"))
                .andExpect(jsonPath("$.active").value(false));
    }

    @Test
    @DisplayName("an optional field sent as null is cleared; the others stay")
    void nullClearsAnOptionalField() throws Exception {
        String id = fullSupplier();

        patchOf(id, "{\"contactName\":null}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contactName").value(nullValue()))
                .andExpect(jsonPath("$.contactDetails").value("WhatsApp +86 000"))
                .andExpect(jsonPath("$.paymentNotes").value("Bank transfer"));

        assertThat(jdbc.queryForObject("SELECT contact_name FROM suppliers WHERE id = ?::uuid", String.class, id))
                .isNull();
    }

    @Test
    @DisplayName("an optional field sent as blank text is cleared too, so it is stored as null, never as an empty string")
    void blankClearsAnOptionalField() throws Exception {
        String id = fullSupplier();

        patchOf(id, "{\"contactDetails\":\"   \",\"paymentNotes\":\"\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contactDetails").value(nullValue()))
                .andExpect(jsonPath("$.paymentNotes").value(nullValue()))
                .andExpect(jsonPath("$.contactName").value("Mr Li"));

        assertThat(jdbc.queryForObject("SELECT payment_notes FROM suppliers WHERE id = ?::uuid", String.class, id))
                .isNull();
    }

    @Test
    @DisplayName("an optional field can be cleared, left alone and set again over successive edits")
    void clearThenSet() throws Exception {
        String id = fullSupplier();

        patchOf(id, "{\"paymentNotes\":null}").andExpect(jsonPath("$.paymentNotes").value(nullValue()));
        patchOf(id, "{\"name\":\"Fycargo Ltd\"}").andExpect(jsonPath("$.paymentNotes").value(nullValue()));
        patchOf(id, "{\"paymentNotes\":\"Mobile money\"}").andExpect(jsonPath("$.paymentNotes").value("Mobile money"));
    }

    // ------------------------------------------------------------- active

    @Test
    @DisplayName("active false stops using a supplier and active true brings it back; neither touches the rest")
    void activeSwitch() throws Exception {
        String id = fullSupplier();

        patchOf(id, "{\"active\":false}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.name").value("Fycargo"));
        patchOf(id, "{\"active\":true}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    @DisplayName("active sent as null is a 400: it is a yes or a no, never empty")
    void nullActiveIsRefused() throws Exception {
        String id = fullSupplier();

        patchOf(id, "{\"active\":null}")
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());

        assertThat(jdbc.queryForObject("SELECT active FROM suppliers WHERE id = ?::uuid", Boolean.class, id)).isTrue();
    }

    @Test
    @DisplayName("a supplier that has been stopped can still be edited, and a stopped supplier still holds its name")
    void stoppedSupplierStillHoldsItsName() throws Exception {
        String id = fullSupplier();
        patchOf(id, "{\"active\":false}").andExpect(status().isOk());

        patchOf(id, "{\"contactName\":\"Ms Wang\"}").andExpect(status().isOk());

        // Inactive is "real, but not used": its name must not be taken by an accident.
        mvc.perform(post(PATH).with(SupplierCrudTest.maya()).contentType(MediaType.APPLICATION_JSON)
                        .content(SupplierCrudTest.supplier("fycargo", "CN")))
                .andExpect(status().isConflict());
    }

    // ----------------------------------------------- required fields are never emptied

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "{\"name\":null}", "{\"name\":\"\"}", "{\"name\":\"   \"}",
            "{\"country\":null}", "{\"country\":\"\"}", "{\"country\":\"  \"}"})
    @DisplayName("name and country can be left out or replaced, but sent null or blank they are a 400 and nothing changes")
    void requiredFieldsCannotBeEmptied(String body) throws Exception {
        String id = fullSupplier();

        patchOf(id, body)
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());

        assertThat(jdbc.queryForObject("SELECT name FROM suppliers WHERE id = ?::uuid", String.class, id))
                .isEqualTo("Fycargo");
        assertThat(jdbc.queryForObject("SELECT country FROM suppliers WHERE id = ?::uuid", String.class, id))
                .isEqualTo("CN");
    }

    @Test
    @DisplayName("a value that breaks a rule is a 400 naming every field at fault, and not even the good fields in the same request are applied")
    void invalidValuesAreFieldErrorsAndNothingIsApplied() throws Exception {
        String id = fullSupplier();

        patchOf(id, "{\"country\":\"UK\",\"name\":\"" + "x".repeat(121) + "\",\"contactName\":\"Fine\","
                + "\"paymentNotes\":\"" + "y".repeat(501) + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://oriah.dev/problems/validation"))
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("country")))
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("name")))
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("paymentNotes")));

        // The valid contactName in that same request was not applied either.
        assertThat(jdbc.queryForObject("SELECT contact_name FROM suppliers WHERE id = ?::uuid", String.class, id))
                .isEqualTo("Mr Li");
    }

    // --------------------------------------------------------------- the name

    @Test
    @DisplayName("renaming to a name another live supplier has is a 409, in any letter case, and the supplier is unchanged")
    void takenNameIs409() throws Exception {
        create(SupplierCrudTest.supplier("Mercury", "AE"));
        String id = fullSupplier();

        patchOf(id, "{\"name\":\"MERCURY\",\"contactName\":\"Changed\"}")
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("https://oriah.dev/problems/conflict"))
                .andExpect(jsonPath("$.detail").value(containsString("MERCURY")));

        assertThat(jdbc.queryForObject("SELECT name FROM suppliers WHERE id = ?::uuid", String.class, id))
                .isEqualTo("Fycargo");
        assertThat(jdbc.queryForObject("SELECT contact_name FROM suppliers WHERE id = ?::uuid", String.class, id))
                .isEqualTo("Mr Li");
    }

    @Test
    @DisplayName("a supplier can be 'renamed' to its own name in a different letter case, and keeps its own name free of itself")
    void ownNameInAnotherCaseIsFine() throws Exception {
        String id = fullSupplier();

        patchOf(id, "{\"name\":\"FYCARGO\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("FYCARGO"));
    }

    @Test
    @DisplayName("a deleted supplier's name can be taken by a rename")
    void aDeletedNameIsFreeForARename() throws Exception {
        String gone = create(SupplierCrudTest.supplier("Mercury", "AE"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete(PATH + "/" + gone).with(SupplierCrudTest.maya()))
                .andExpect(status().isNoContent());
        String id = fullSupplier();

        patchOf(id, "{\"name\":\"Mercury\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Mercury"));
    }

    // ------------------------------------------------------ version and audit

    @Test
    @DisplayName("an edit raises the version and records who changed it; an edit that changes nothing does not")
    void versionAndAudit() throws Exception {
        String id = fullSupplier();

        patchOf(id, "{}")
                .andExpect(jsonPath("$.version").value(0));
        patchOf(id, "{\"contactName\":\"Ms Wang\"}")
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.updatedBy").value("kc-maya"));
        // Sending the value it already has is not a change either.
        patchOf(id, "{\"contactName\":\"Ms Wang\"}")
                .andExpect(jsonPath("$.version").value(1));
    }

    // ------------------------------------------------------------- not found

    @Test
    @DisplayName("editing an unknown, a deleted or a malformed id is 404, 404 and 400")
    void unknownDeletedAndMalformed() throws Exception {
        patchOf(UUID.randomUUID().toString(), "{\"contactName\":\"x\"}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://oriah.dev/problems/not-found"));

        String id = fullSupplier();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete(PATH + "/" + id).with(SupplierCrudTest.maya()))
                .andExpect(status().isNoContent());
        patchOf(id, "{\"contactName\":\"x\"}").andExpect(status().isNotFound());

        patchOf("not-a-uuid", "{}").andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a body that is not JSON, or has the wrong type for a field, is a 400, not a 500")
    void malformedBodies() throws Exception {
        String id = fullSupplier();

        patchOf(id, "{not json").andExpect(status().isBadRequest());
        patchOf(id, "{\"active\":\"maybe\"}").andExpect(status().isBadRequest());
        patchOf(id, "{\"name\":{\"a\":1}}").andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a field the API does not have is ignored, so a client that sends the whole supplier back does no harm")
    void unknownFieldsAreIgnored() throws Exception {
        String id = fullSupplier();

        // What a client gets from GET and sends back: id, version, audit columns and all.
        patchOf(id, """
                {"id":"00000000-0000-0000-0000-000000000000","version":99,"createdBy":"someone-else",
                 "createdAt":"2000-01-01T00:00:00Z","contactName":"Ms Wang"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.createdBy").value("kc-maya"))
                .andExpect(jsonPath("$.contactName").value("Ms Wang"));
    }
}
