package com.oriah.customer.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.oriah.customer.TestcontainersConfiguration;
import com.oriah.customer.repository.CustomerRepository;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full-stack integration tests against a real Postgres container.
 *
 * <p>Each test uses the {@code with(jwt())} post-processor to attach a
 * synthetic JWT with claims that the controller would receive from
 * Keycloak in production. {@code subject(...)} sets the {@code sub}
 * claim, which is what the service uses as the natural key.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"eureka.client.enabled=false"})
class CustomerIntegrationTest {

    private static final SimpleGrantedAuthority ROLE_USER = new SimpleGrantedAuthority("ROLE_USER");
    private static final SimpleGrantedAuthority ROLE_CUSTOMER_ADMIN =
            new SimpleGrantedAuthority("ROLE_CUSTOMER_ADMIN");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private CustomerRepository repository;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    @DisplayName("POST /me bootstraps a customer from the JWT, idempotent")
    void postMeBootstrapsAndIsIdempotent() throws Exception {
        var jwt = jwt()
                .authorities(ROLE_USER)
                .jwt(j -> j.subject("kc-alice-1").claim("email", "alice@oriah.local")
                        .claim("given_name", "Alice").claim("family_name", "Buyer"));

        // First POST: 201 Created
        mvc.perform(post("/api/v1/customers/me").with(jwt))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("alice@oriah.local"))
                .andExpect(jsonPath("$.firstName").value("Alice"));

        // Second POST: 200 OK (same customer, idempotent)
        mvc.perform(post("/api/v1/customers/me").with(jwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("alice@oriah.local"));
    }

    @Test
    @DisplayName("/me without a JWT is 401")
    void meRequiresJwt() throws Exception {
        mvc.perform(get("/api/v1/customers/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("PATCH /me updates fields and persists")
    void patchMeUpdates() throws Exception {
        var jwt = jwt().authorities(ROLE_USER)
                .jwt(j -> j.subject("kc-alice-2").claim("email", "old@x").claim("given_name", "A").claim("family_name", "B"));

        mvc.perform(post("/api/v1/customers/me").with(jwt))
                .andExpect(status().isCreated());

        mvc.perform(patch("/api/v1/customers/me").with(jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"Alicia\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstName").value("Alicia"));
    }

    @Test
    @DisplayName("address CRUD: add, list, delete")
    void addressCrud() throws Exception {
        var jwt = jwt().authorities(ROLE_USER)
                .jwt(j -> j.subject("kc-alice-3").claim("email", "a@x").claim("given_name", "A").claim("family_name", "B"));

        mvc.perform(post("/api/v1/customers/me").with(jwt))
                .andExpect(status().isCreated());

        String body = """
                {"label":"Home","line1":"1 Main St","city":"Lagos","postalCode":"100001","country":"NG","isDefault":true}
                """;
        String location = mvc.perform(post("/api/v1/customers/me/addresses").with(jwt)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.isDefault").value(true))
                .andReturn().getResponse().getHeader("Location");

        mvc.perform(get("/api/v1/customers/me/addresses").with(jwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        // Extract id from Location: /api/v1/customers/me/addresses/<uuid>
        String id = location.substring(location.lastIndexOf('/') + 1);
        mvc.perform(delete("/api/v1/customers/me/addresses/" + id).with(jwt))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("admin GET /admin/customers is forbidden for ROLE_USER")
    void adminEndpointForbiddenForUser() throws Exception {
        mvc.perform(get("/api/v1/admin/customers")
                        .with(jwt().authorities(ROLE_USER)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("admin GET /admin/customers is 200 for CUSTOMER_ADMIN")
    void adminEndpointAllowedForAdmin() throws Exception {
        mvc.perform(get("/api/v1/admin/customers")
                        .with(jwt().authorities(ROLE_CUSTOMER_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    // ------------------------------------------------------------------
    // Not-found paths. All five answered 404 with an EMPTY BODY before Tier 5.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("GET /me before provisioning is 404 WITH a problem body")
    void meBeforeProvisioningIsAProblem404() throws Exception {
        // Reachable in normal use: a brand-new Keycloak user holds a valid JWT
        // but has never called POST /me. With an empty 404 the storefront could
        // only report "Upstream returned 404 Not Found", when the actionable
        // message is that the profile has not been created yet.
        mvc.perform(get("/api/v1/customers/me").with(jwt().authorities(ROLE_USER)
                        .jwt(j -> j.subject("kc-never-provisioned"))))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.type").value("https://oriah.dev/problems/not-found"))
                .andExpect(jsonPath("$.detail").value(
                        "Customer not found with the given input data customer : 'me'"))
                .andExpect(jsonPath("$.traceId").exists());
    }

    @Test
    @DisplayName("PATCH /me before provisioning is 404 with a problem body")
    void patchMeBeforeProvisioningIsAProblem404() throws Exception {
        mvc.perform(patch("/api/v1/customers/me").with(jwt().authorities(ROLE_USER)
                        .jwt(j -> j.subject("kc-never-provisioned-2")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"Nobody\"}"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value(
                        "Customer not found with the given input data customer : 'me'"));
    }

    @Test
    @DisplayName("POST /me/addresses before provisioning is 404 with a problem body")
    void addAddressBeforeProvisioningIsAProblem404() throws Exception {
        // NOTE the inconsistency this does NOT fix, recorded deliberately:
        // GET /me/addresses answers the SAME state with 200 and an empty list.
        // One condition, two answers. Harmonizing them is a status change with
        // no bug behind it, so it is deferred to be done WITH the frontend
        // rather than slipped into a conventions tier.
        mvc.perform(post("/api/v1/customers/me/addresses").with(jwt().authorities(ROLE_USER)
                        .jwt(j -> j.subject("kc-never-provisioned-3")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"label":"Home","line1":"1 Main St","city":"Lagos",                                "postalCode":"100001","country":"NG","isDefault":true}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value(
                        "Customer not found with the given input data customer : 'me'"));
    }

    @Test
    @DisplayName("DELETE an address that is not there is 404 with a problem body")
    void deleteUnknownAddressIsAProblem404() throws Exception {
        var jwt = jwt().authorities(ROLE_USER)
                .jwt(j -> j.subject("kc-addr-404").claim("email", "a@x")
                        .claim("given_name", "A").claim("family_name", "B"));
        mvc.perform(post("/api/v1/customers/me").with(jwt)).andExpect(status().isCreated());

        java.util.UUID missing = java.util.UUID.randomUUID();
        mvc.perform(delete("/api/v1/customers/me/addresses/" + missing).with(jwt))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value(
                        "Address not found with the given input data addressId : '" + missing + "'"));
    }

    @Test
    @DisplayName("admin GET /admin/customers/{id} unknown is 404 with a problem body")
    void adminGetUnknownCustomerIsAProblem404() throws Exception {
        java.util.UUID missing = java.util.UUID.randomUUID();
        mvc.perform(get("/api/v1/admin/customers/" + missing)
                        .with(jwt().authorities(ROLE_CUSTOMER_ADMIN)))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value(
                        "Customer not found with the given input data id : '" + missing + "'"));
    }

    @Test
    @DisplayName("the admin list renders customers WITH their addresses, instead of a 500")
    void adminListIncludesAddressesAndDoesNotBlowUp() throws Exception {
        // This endpoint has returned 500 for its whole life, and nothing
        // caught it because no test ever listed a customer that HAD an
        // address. CustomerService.findAll is @Transactional and returns
        // ENTITIES; AdminCustomerController then calls
        // CustomerResponse::from, which iterates the lazy `addresses`
        // collection after the transaction has closed:
        //
        //   org.hibernate.LazyInitializationException: Cannot lazily
        //   initialize collection of role Customer.addresses
        //
        // Found by calling the live endpoint while building the admin UI on
        // top of it, not by reading the code.
        String sub = UUID.randomUUID().toString();
        mvc.perform(post("/api/v1/customers/me")
                        .with(jwt().authorities(ROLE_USER).jwt(j -> j.subject(sub)
                                .claim("email", "listed@oriah.test"))))
                .andExpect(status().is2xxSuccessful());
        mvc.perform(post("/api/v1/customers/me/addresses")
                        .with(jwt().authorities(ROLE_USER).jwt(j -> j.subject(sub)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"label":"Home","line1":"1 Atelier Way","city":"Lagos",
                                 "postalCode":"100001","country":"NG","isDefault":true}
                                """))
                .andExpect(status().is2xxSuccessful());

        mvc.perform(get("/api/v1/admin/customers")
                        .with(jwt().authorities(ROLE_CUSTOMER_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.email == 'listed@oriah.test')]").exists())
                .andExpect(jsonPath(
                        "$.content[?(@.email == 'listed@oriah.test')].addresses[0].city")
                        .value(org.hamcrest.Matchers.hasItem("Lagos")));
    }
}
