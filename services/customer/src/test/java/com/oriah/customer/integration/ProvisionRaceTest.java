package com.oriah.customer.integration;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.jayway.jsonpath.JsonPath;
import com.oriah.customer.TestcontainersConfiguration;
import com.oriah.customer.model.Customer;
import com.oriah.customer.repository.CustomerRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * First sign-in from two places at once: one profile, and nobody gets a 500.
 *
 * <p>{@code POST /api/v1/customers/me} is idempotent self-provisioning: the first
 * call creates the shopper's profile from their token, every later call returns it.
 * A shopper who opens the storefront in two tabs, or double-clicks "sign in",
 * makes the first call twice at the same moment. Both look for the profile, both
 * find none, both insert, and the database's UNIQUE on {@code keycloak_id} refuses
 * the second. That used to surface as a 500 on the shopper's very first visit.
 *
 * <p>The right answer for the one that lost is the profile the winner made, with a
 * 200: it did not create anything. Only the call that really created the profile
 * answers 201.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"eureka.client.enabled=false"})
class ProvisionRaceTest {

    private static final String PATH = "/api/v1/customers/me";
    private static final SimpleGrantedAuthority ROLE_USER = new SimpleGrantedAuthority("ROLE_USER");
    private static final int THREADS = 8;
    private static final int ROUNDS = 3;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactions;

    @MockitoSpyBean
    private CustomerRepository repository;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM addresses");
        jdbc.update("DELETE FROM customers");
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor shopper(String sub) {
        return jwt().jwt(j -> j.subject(sub).claim("email", sub + "@example.test")
                        .claim("given_name", "Test").claim("family_name", "Shopper"))
                .authorities(ROLE_USER);
    }

    @Test
    @DisplayName("simultaneous first sign-ins: exactly one 201, the rest 200 with the same profile, no 500, one row")
    void simultaneousFirstCallsMakeOneProfile() throws Exception {
        // Several rounds with a fresh shopper each, so a lucky round cannot hide the race.
        for (int round = 0; round < ROUNDS; round++) {
            String sub = "kc-race-" + round;
            ExecutorService pool = Executors.newFixedThreadPool(THREADS);
            CountDownLatch ready = new CountDownLatch(THREADS);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<MvcResult>> results = new ArrayList<>();
            try {
                for (int i = 0; i < THREADS; i++) {
                    results.add(pool.submit(() -> {
                        ready.countDown();
                        go.await();
                        return mvc.perform(post(PATH).with(shopper(sub))).andReturn();
                    }));
                }
                assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
                go.countDown();

                List<Integer> statuses = new ArrayList<>();
                Set<String> ids = new HashSet<>();
                for (Future<MvcResult> f : results) {
                    MvcResult result = f.get(60, TimeUnit.SECONDS);
                    statuses.add(result.getResponse().getStatus());
                    if (result.getResponse().getStatus() < 300) {
                        ids.add(JsonPath.read(result.getResponse().getContentAsString(), "$.id"));
                    }
                }

                assertThat(statuses).as("round %d: statuses of %d simultaneous first calls", round, THREADS)
                        .containsOnly(200, 201)
                        .filteredOn(s -> s == 201).hasSize(1);
                assertThat(ids).as("round %d: every call got the same profile", round).hasSize(1);
            } finally {
                pool.shutdownNow();
            }
            assertThat(jdbc.queryForObject("SELECT count(*) FROM customers WHERE keycloak_id = ?",
                    Integer.class, sub)).as("round %d: one row", round).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("a call that loses the race to insert returns the winner's profile with 200, not a 500 and not a 201")
    void theLoserGetsTheWinnersProfile() throws Exception {
        String sub = "kc-loser";
        mvc.perform(post(PATH).with(shopper(sub))).andExpect(status().isCreated());
        Customer winner = new TransactionTemplate(transactions).execute(status -> {
            Customer c = repository.findByKeycloakId(sub).orElseThrow();
            c.addresses().size(); // load while the session is open: the response reads it
            return c;
        });

        // The loser looked, found nothing (the winner had not committed yet), then tried to insert.
        // Real threads cannot be put in that window for certain; this makes it certain: the first
        // look finds nothing, and any look after the database refused the insert finds the winner.
        doReturn(Optional.empty()).doReturn(Optional.of(winner)).when(repository).findByKeycloakId(sub);

        mvc.perform(post(PATH).with(shopper(sub)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(winner.id().toString()));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM customers WHERE keycloak_id = ?",
                Integer.class, sub)).isEqualTo(1);
    }
}
