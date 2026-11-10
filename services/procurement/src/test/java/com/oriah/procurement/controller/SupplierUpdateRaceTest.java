package com.oriah.procurement.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.jayway.jsonpath.JsonPath;
import com.oriah.procurement.TestcontainersConfiguration;
import com.oriah.procurement.repository.SupplierRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Two people changing the same supplier at the same moment: nobody's change is
 * silently overwritten, and nobody gets a 500.
 *
 * <p>Two different collisions can happen and each has its own guard:
 *
 * <ul>
 *   <li>two edits of one supplier: the {@code @Version} column. The edit that
 *       read the supplier before the other one saved finds the version moved and
 *       is refused as a 409 {@code concurrent-update};</li>
 *   <li>two suppliers renamed (or one renamed and one added) to the same name: the
 *       partial unique index, which the name check in front of it cannot replace
 *       because check and write are not one atomic step. Refused as the same 409
 *       the add answers.</li>
 * </ul>
 *
 * <p>Real threads cannot be forced into the narrow window between a read and a
 * write, so the two deterministic tests open it on purpose; the last test then runs
 * the real race anyway and checks the one thing that must always hold.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false"
})
class SupplierUpdateRaceTest {

    private static final String PATH = SupplierCrudTest.PATH;
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

    @Autowired
    private PlatformTransactionManager transactions;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM suppliers");
    }

    /**
     * What another request's save looks like to the one under test: its own
     * transaction, committed before the first one writes. It must be a separate
     * transaction. A plain {@code jdbc.update} made from inside the service shares
     * the service's connection, so it would be rolled back together with the edit
     * that is refused and the test would prove nothing about who won.
     */
    private void anotherRequestSaves(String id) {
        TransactionTemplate separate = new TransactionTemplate(transactions);
        separate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        separate.executeWithoutResult(status -> jdbc.update(
                "UPDATE suppliers SET contact_name = 'Someone else', version = version + 1 WHERE id = ?::uuid", id));
    }

    private String create(String body) throws Exception {
        MvcResult created = mvc.perform(post(PATH).with(SupplierCrudTest.maya())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(created.getResponse().getContentAsString(), "$.id");
    }

    @Test
    @DisplayName("an edit made from a stale read is a 409 concurrent-update, and the other person's change stands")
    void aStaleEditIsRefused() throws Exception {
        String id = create("{\"name\":\"Fycargo\",\"country\":\"CN\",\"contactName\":\"Mr Li\"}");

        // Between this edit reading the supplier and writing it, someone else saves a change.
        // The name check sits exactly there: the service has read the supplier and has not yet
        // written it. Nobody else has the name, so the check itself still answers false.
        doAnswer(invocation -> {
            anotherRequestSaves(id);
            return false;
        }).when(repository).existsOtherLiveName(anyString(), any(UUID.class));

        mvc.perform(patch(PATH + "/" + id).with(SupplierCrudTest.maya())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Fycargo Ltd\"}"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("https://oriah.dev/problems/concurrent-update"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());

        assertThat(jdbc.queryForObject("SELECT name FROM suppliers WHERE id = ?::uuid", String.class, id))
                .as("the edit that lost must not have been applied")
                .isEqualTo("Fycargo");
        assertThat(jdbc.queryForObject("SELECT contact_name FROM suppliers WHERE id = ?::uuid", String.class, id))
                .as("the other person's change stands: the edit that lost did not overwrite it")
                .isEqualTo("Someone else");
        assertThat(jdbc.queryForObject("SELECT version FROM suppliers WHERE id = ?::uuid", Long.class, id))
                .as("exactly one change was written")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("a rename that gets past the name check is still refused by the index: 409, not 500")
    void aRenameThatSlipsPastTheCheckIsStillA409() throws Exception {
        create("{\"name\":\"Mercury\",\"country\":\"AE\"}");
        String id = create("{\"name\":\"Fycargo\",\"country\":\"CN\"}");

        // The check says "nobody else has it" for everyone, as it would for two requests that
        // both ran it before either wrote.
        doReturn(false).when(repository).existsOtherLiveName(anyString(), any(UUID.class));

        mvc.perform(patch(PATH + "/" + id).with(SupplierCrudTest.maya())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"MERCURY\"}"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("https://oriah.dev/problems/conflict"))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(content().string(not(containsString("uq_suppliers_name_live"))))
                .andExpect(content().string(not(containsString("duplicate key"))));

        // The check really ran and really said "free": the 409 above came from the index.
        verify(repository).existsOtherLiveName(eq("MERCURY"), any(UUID.class));
        assertThat(jdbc.queryForObject("SELECT name FROM suppliers WHERE id = ?::uuid", String.class, id))
                .isEqualTo("Fycargo");
    }

    @Test
    @DisplayName("eight simultaneous edits of one supplier: only 200 or 409, never 500, and the version counts exactly the edits that won")
    void simultaneousEditsNeverLoseCount() throws Exception {
        String id = create("{\"name\":\"Fycargo\",\"country\":\"CN\",\"contactName\":\"Mr Li\"}");

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        try {
            for (int i = 0; i < THREADS; i++) {
                String body = "{\"contactName\":\"Contact " + i + "\"}";
                results.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return mvc.perform(patch(PATH + "/" + id).with(SupplierCrudTest.maya())
                                    .contentType(MediaType.APPLICATION_JSON).content(body))
                            .andReturn().getResponse().getStatus();
                }));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            go.countDown();

            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : results) {
                statuses.add(f.get(60, TimeUnit.SECONDS));
            }

            long won = statuses.stream().filter(s -> s == 200).count();
            assertThat(statuses).as("statuses of %d simultaneous edits", THREADS).containsOnly(200, 409);
            assertThat(won).as("at least one edit must win").isGreaterThanOrEqualTo(1);
            // Every edit that answered 200 was written exactly once, and none that answered 409 was.
            assertThat(jdbc.queryForObject("SELECT version FROM suppliers WHERE id = ?::uuid", Long.class, id))
                    .isEqualTo(won);
        } finally {
            pool.shutdownNow();
        }
    }
}
