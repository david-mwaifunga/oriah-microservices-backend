package com.oriah.inventory.integration;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.oriah.inventory.TestcontainersConfiguration;
import com.oriah.inventory.model.Inventory;
import com.oriah.inventory.repository.InventoryRepository;
import com.oriah.inventory.service.InventoryService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Two people change the same piece's stock at the same moment.
 *
 * <p>Every stock change is read-modify-write: load the row, change the counts
 * in memory, save on commit. If two requests both read before either saves,
 * the second save overwrites the first with counts computed from a stale read
 * -- a "lost update". For a reserve against the last unit, both shoppers are
 * told yes and the shop has sold one piece twice.
 *
 * <p>The fix is a version number on the row ({@code @Version}): each save says
 * "update where version = the one I read", so the second save matches no row
 * and is refused at commit. The loser gets 409 "someone else changed this --
 * reload and try again"; the winner's change stands.
 *
 * <p>A real race only overlaps sometimes, which would make a flaky test. So the
 * repository is spied on and each request is held, just after it has read the
 * row, until the other one has read it too -- the overlap happens every time.
 * Only then are they let go to change and commit. The conflict surfaces at
 * commit, inside the transactional service proxy, which is why the test drives
 * the HTTP endpoint and not the entity.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "eureka.client.enabled=false")
class ConcurrentStockChangeTest {

    private static final SimpleGrantedAuthority INVENTORY_ADMIN =
            new SimpleGrantedAuthority("ROLE_INVENTORY_ADMIN");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private InventoryService service;

    @MockitoSpyBean
    private InventoryRepository repository;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    /** Armed only while a race runs; null means "do not hold anyone". */
    private volatile CyclicBarrier bothHaveRead;

    @BeforeEach
    void holdEachReaderUntilTheOtherHasReadToo() {
        repository.deleteAll();
        // The repository is an interface proxy, so the spy has no "real method"
        // to call; its default answer is what forwards to the real repository.
        Answer<?> realRepository = mockingDetails(repository).getMockCreationSettings().getDefaultAnswer();
        doAnswer(invocation -> {
            Object found = realRepository.answer(invocation);
            CyclicBarrier barrier = bothHaveRead;
            if (barrier != null) {
                barrier.await(20, TimeUnit.SECONDS);
            }
            return found;
        }).when(repository).findById(any());
    }

    @AfterEach
    void disarm() {
        bothHaveRead = null;
    }

    @Test
    @DisplayName("two reserves for the last unit: one wins, the other gets 409, one unit reserved")
    void reserveRace() throws Exception {
        UUID id = stock(1, 0);

        List<MvcResult> results = race(id, "reserve");

        assertOneWonOneLost(results);
        Inventory after = repository.findById(id).orElseThrow();
        assertThat(after.reserved()).isEqualTo(1);
        assertThat(after.onHand()).isEqualTo(1);
    }

    @Test
    @DisplayName("two releases of one unit each: one applies, the other gets 409")
    void releaseRace() throws Exception {
        UUID id = stock(2, 2);

        List<MvcResult> results = race(id, "release");

        assertOneWonOneLost(results);
        Inventory after = repository.findById(id).orElseThrow();
        assertThat(after.reserved()).isEqualTo(1);
        assertThat(after.onHand()).isEqualTo(2);
    }

    @Test
    @DisplayName("two confirms of one unit each: one applies, the other gets 409")
    void confirmRace() throws Exception {
        UUID id = stock(2, 2);

        List<MvcResult> results = race(id, "confirm");

        assertOneWonOneLost(results);
        Inventory after = repository.findById(id).orElseThrow();
        assertThat(after.reserved()).isEqualTo(1);
        assertThat(after.onHand()).isEqualTo(1);
    }

    @Test
    @DisplayName("two restocks of one unit each: one applies, the other gets 409")
    void restockRace() throws Exception {
        UUID id = stock(1, 0);

        List<MvcResult> results = race(id, "restock");

        assertOneWonOneLost(results);
        Inventory after = repository.findById(id).orElseThrow();
        assertThat(after.onHand()).isEqualTo(2);
        assertThat(after.reserved()).isZero();
    }

    /** A row with the given counts, created before any race is armed. */
    private UUID stock(int onHand, int reserved) {
        UUID id = UUID.randomUUID();
        service.create(id, onHand);
        if (reserved > 0) {
            service.reserve(id, reserved);
        }
        return id;
    }

    /** Sends the same one-unit change twice, at the same moment. */
    private List<MvcResult> race(UUID id, String operation) throws Exception {
        bothHaveRead = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<MvcResult> send = () -> mvc.perform(
                            post("/api/v1/admin/inventory/" + id + "/" + operation)
                                    .with(jwt().authorities(INVENTORY_ADMIN))
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"qty\":1}"))
                    .andReturn();
            Future<MvcResult> first = pool.submit(send);
            Future<MvcResult> second = pool.submit(send);
            return List.of(first.get(60, TimeUnit.SECONDS), second.get(60, TimeUnit.SECONDS));
        } finally {
            bothHaveRead = null;
            pool.shutdownNow();
        }
    }

    private static void assertOneWonOneLost(List<MvcResult> results) throws Exception {
        List<Integer> statuses = results.stream().map(r -> r.getResponse().getStatus()).sorted().toList();
        assertThat(statuses)
                .as("exactly one change applies; the other is refused, never both applied")
                .containsExactly(200, 409);
        MvcResult loser = results.stream().filter(r -> r.getResponse().getStatus() == 409).findFirst().orElseThrow();
        assertThat(loser.getResponse().getContentAsString())
                .contains("https://oriah.dev/problems/concurrent-update");
    }
}
