package com.oriah.procurement.controller;

import java.util.ArrayList;
import java.util.List;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import com.oriah.procurement.TestcontainersConfiguration;
import com.oriah.procurement.repository.SupplierRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Two staff members adding the same supplier at the same moment: exactly one
 * wins, the others are told it exists, and nobody gets a 500.
 *
 * <p>The service checks the name before it saves, but that check and the insert
 * are not one atomic step, so simultaneous requests can all pass the check. The
 * partial unique index is what actually decides, and the procurement-local
 * handler is what turns the loser's database error into a 409. This test runs
 * the real race, with real threads, against a real PostgreSQL, so it covers the
 * path the pre-check cannot.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false"
})
class UniqueNameRaceTest {

    private static final int THREADS = 8;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    @MockitoSpyBean
    private SupplierRepository repository;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM suppliers");
    }

    @Test
    @DisplayName("when the name check lets a duplicate through, as it does under a race, the index still refuses it: 409, not 500")
    void aDuplicateThatSlipsPastTheCheckIsStillA409() throws Exception {
        mvc.perform(post(SupplierCrudTest.PATH).with(SupplierCrudTest.maya())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(SupplierCrudTest.supplier("Fycargo", "CN")))
                .andExpect(status().isCreated());

        // The check says "not taken" for everyone, exactly as it would for two
        // requests that both ran it before either inserted. Real threads cannot
        // be forced into that window; this makes the window certain.
        doReturn(false).when(repository).existsLiveName(anyString());

        mvc.perform(post(SupplierCrudTest.PATH).with(SupplierCrudTest.maya())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(SupplierCrudTest.supplier("FYCARGO", "AE")))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("https://oriah.dev/problems/conflict"))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                // The driver's message names the index and the clashing value; none of it is echoed.
                .andExpect(content().string(not(containsString("uq_suppliers_name_live"))))
                .andExpect(content().string(not(containsString("duplicate key"))));

        // The SECOND request's pre-check really ran and really said "free": the 409 above came from
        // the index. Asserted on that request's own tidied name ("FYCARGO"), because the first
        // request's call was with "Fycargo", so a bare "was it called" would pass even if the second
        // request never consulted the check at all.
        verify(repository).existsLiveName("FYCARGO");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM suppliers", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("eight simultaneous creates of one name: exactly one 201, seven 409, no 500, one row")
    void exactlyOneWins() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        try {
            for (int i = 0; i < THREADS; i++) {
                results.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return mvc.perform(post(SupplierCrudTest.PATH).with(SupplierCrudTest.maya())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(SupplierCrudTest.supplier("Fycargo", "CN")))
                            .andReturn().getResponse().getStatus();
                }));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            go.countDown();

            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : results) {
                statuses.add(f.get(60, TimeUnit.SECONDS));
            }

            assertThat(statuses).as("statuses of %d simultaneous creates", THREADS)
                    .containsOnly(201, 409)
                    .filteredOn(s -> s == 201).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM suppliers", Integer.class)).isEqualTo(1);
    }
}
