package com.oriah.procurement.controller;

import java.time.Instant;
import java.util.List;
import java.util.Map;
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
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;
import com.oriah.procurement.ProcurementTestData;
import com.oriah.procurement.TestcontainersConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Who may touch suppliers: staff holding {@code PROCUREMENT_ADMIN}, and nobody else.
 *
 * <p>Every one of the five supplier endpoints is tried with no token, with a plain
 * shopper's token, and with a token for every <em>other</em> capability the
 * platform has. The gateway already refuses these callers, but services call each
 * other directly and their ports are closed only in production, so the service
 * must refuse them itself: that is what is tested here, against the service.
 *
 * <p>Two things beyond the status codes matter. A refused call must change
 * nothing (a 403 that still deleted the supplier would be worse than no check), and
 * the refusal must not say which role would have been enough, since that tells an
 * attacker what to go and steal.
 *
 * <p>Job roles ({@code MANAGER}, {@code BOOKKEEPER} and the rest) are people's
 * titles, not what the code checks: Keycloak puts the capabilities a title carries
 * into the token, so a token that somehow has only the title is refused.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false"
})
class SupplierSecurityTest {

    private static final String PATH = SupplierCrudTest.PATH;

    /** Every role in the realm except the one that opens this door. */
    private static final List<String> OTHER_ROLES = List.of(
            "USER",
            "CATALOG_ADMIN", "INVENTORY_ADMIN", "CUSTOMER_ADMIN", "ORDER_ADMIN", "PAYMENT_ADMIN",
            "FINANCE_ADMIN", "SHIPPING_ADMIN", "SALES_AGENT", "FULFILMENT_AGENT", "COST_VIEWER",
            "STOCK_WRITE_OFF", "BACKDATE_SALE", "DELIVERY_WAIVER",
            "OWNER", "MANAGER", "SALES", "DISPATCH", "BOOKKEEPER");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    private String id;

    @BeforeEach
    void aSupplierToTryToHarm() throws Exception {
        ProcurementTestData.wipe(jdbc);
        MvcResult created = mvc.perform(post(PATH).with(SupplierCrudTest.maya())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Fycargo\",\"country\":\"CN\",\"contactName\":\"Mr Li\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        id = JsonPath.read(created.getResponse().getContentAsString(), "$.id");
    }

    private MockHttpServletRequestBuilder call(String endpoint) {
        return switch (endpoint) {
            case "create" -> post(PATH).contentType(MediaType.APPLICATION_JSON)
                    .content(SupplierCrudTest.supplier("Intruder Ltd", "ZM"));
            case "list" -> get(PATH);
            case "get" -> get(PATH + "/" + id);
            case "update" -> patch(PATH + "/" + id).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"contactName\":\"Hacker\",\"active\":false}");
            case "delete" -> delete(PATH + "/" + id);
            default -> throw new IllegalArgumentException(endpoint);
        };
    }

    private MockHttpServletResponse answer(String endpoint, RequestPostProcessor token) throws Exception {
        MockHttpServletRequestBuilder request = call(endpoint);
        return mvc.perform(token == null ? request : request.with(token)).andReturn().getResponse();
    }

    private static RequestPostProcessor role(String role) {
        return jwt().jwt(j -> j.subject("kc-someone"))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"create", "list", "get", "update", "delete"})
    @DisplayName("no token: 401")
    void noTokenIs401(String endpoint) throws Exception {
        assertThat(answer(endpoint, null).getStatus()).as("%s with no token", endpoint).isEqualTo(401);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"create", "list", "get", "update", "delete"})
    @DisplayName("every other role, capability or job title, is refused with 403 and the refusal does not name the role that would work")
    void everyOtherRoleIsForbidden(String endpoint) throws Exception {
        for (String role : OTHER_ROLES) {
            MockHttpServletResponse response = answer(endpoint, role(role));

            assertThat(response.getStatus()).as("%s as %s", endpoint, role).isEqualTo(403);
            assertThat(response.getContentType()).as("%s as %s", endpoint, role)
                    .startsWith("application/problem+json");
            assertThat(response.getContentAsString()).as("%s as %s", endpoint, role)
                    .doesNotContain("PROCUREMENT_ADMIN");
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"get", "update", "delete"})
    @DisplayName("a refused caller learns nothing about whether a supplier exists: unknown ids are refused the same way")
    void refusalDoesNotDependOnTheSupplierExisting(String endpoint) throws Exception {
        id = UUID.randomUUID().toString();

        assertThat(answer(endpoint, role("USER")).getStatus()).as(endpoint).isEqualTo(403);
        assertThat(answer(endpoint, null).getStatus()).as(endpoint).isEqualTo(401);
    }

    @Test
    @DisplayName("refused calls change nothing: no supplier added, edited or deleted")
    void refusedCallsChangeNothing() throws Exception {
        for (String endpoint : List.of("create", "update", "delete")) {
            answer(endpoint, null);
            for (String role : OTHER_ROLES) {
                answer(endpoint, role(role));
            }
        }

        assertThat(jdbc.queryForObject("SELECT count(*) FROM suppliers", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT name, contact_name, active, is_deleted, version FROM suppliers"))
                .containsEntry("name", "Fycargo")
                .containsEntry("contact_name", "Mr Li")
                .containsEntry("active", true)
                .containsEntry("is_deleted", false)
                .containsEntry("version", 0L);
    }

    // ------------------------------------------------------------ the real token path
    //
    // Every test above injects authorities directly, which skips the step a real request
    // depends on: this service's own mapping of Keycloak's realm_access.roles claim to
    // ROLE_<NAME>. These go in by the front door: a bearer token, the decoder, the
    // converter, then the role check.

    private static Jwt token(String subject, Object realmAccess) {
        Instant now = Instant.now();
        Jwt.Builder builder = Jwt.withTokenValue("t").header("alg", "none").subject(subject)
                .issuedAt(now).expiresAt(now.plusSeconds(3600));
        if (realmAccess != null) {
            builder.claim("realm_access", realmAccess);
        }
        return builder.build();
    }

    @Test
    @DisplayName("a bearer token whose realm_access.roles holds PROCUREMENT_ADMIN is admitted, through the service's own role mapping")
    void aRealTokenWithTheRoleIsAdmitted() throws Exception {
        when(jwtDecoder.decode("good")).thenReturn(
                token("kc-real", Map.of("roles", List.of("USER", "PROCUREMENT_ADMIN"))));

        mvc.perform(get(PATH).header("Authorization", "Bearer good")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("a bearer token without the role is 403: other roles, no realm_access at all, or roles that are not a list")
    void aRealTokenWithoutTheRoleIsForbidden() throws Exception {
        when(jwtDecoder.decode("other")).thenReturn(
                token("kc-a", Map.of("roles", List.of("USER", "INVENTORY_ADMIN", "MANAGER"))));
        when(jwtDecoder.decode("none")).thenReturn(token("kc-b", null));
        when(jwtDecoder.decode("odd")).thenReturn(token("kc-c", Map.of("roles", "PROCUREMENT_ADMIN")));
        when(jwtDecoder.decode("empty")).thenReturn(token("kc-d", Map.of("roles", List.of())));

        for (String bearer : List.of("other", "none", "odd", "empty")) {
            assertThat(mvc.perform(get(PATH).header("Authorization", "Bearer " + bearer))
                    .andReturn().getResponse().getStatus())
                    .as("a token that is '%s'", bearer).isEqualTo(403);
        }
    }

    @Test
    @DisplayName("a token the decoder refuses (expired, forged) is 401, not an error and not an admission")
    void aBadTokenIs401() throws Exception {
        when(jwtDecoder.decode("bad")).thenThrow(new BadJwtException("expired"));

        for (String endpoint : List.of("create", "list", "get", "update", "delete")) {
            MockHttpServletResponse response = mvc.perform(call(endpoint).header("Authorization", "Bearer bad"))
                    .andReturn().getResponse();
            assertThat(response.getStatus()).as("%s with a refused token", endpoint).isEqualTo(401);
        }
    }

    @Test
    @DisplayName("the one capability that does open the door opens all five endpoints")
    void procurementAdminIsAllowedEverywhere() throws Exception {
        for (String endpoint : List.of("list", "get", "update", "create", "delete")) {
            int status = answer(endpoint, SupplierCrudTest.maya()).getStatus();

            assertThat(status).as("%s as PROCUREMENT_ADMIN", endpoint).isIn(200, 201, 204);
        }
    }
}
