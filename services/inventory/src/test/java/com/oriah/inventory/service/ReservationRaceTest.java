package com.oriah.inventory.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
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
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.oriah.inventory.TestClockConfiguration;
import com.oriah.inventory.TestcontainersConfiguration;
import com.oriah.inventory.exception.CommitmentProblem;
import com.oriah.inventory.service.BoxCommitmentService.ProductSnapshot;
import com.oriah.inventory.service.ReservationService.Line;
import com.oriah.inventory.service.ReservationService.Status;

import static com.oriah.inventory.service.StockTestData.ACTOR;
import static com.oriah.inventory.service.StockTestData.box;
import static com.oriah.inventory.service.StockTestData.context;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Holds taken, sold, released and expired AT THE SAME MOMENT. Whatever the interleaving: the last piece goes to exactly one order; two holds
 * that touch the same variants in opposite orders never deadlock; a hold is sold or let go, never both; a hold that several sweeps and a
 * release all try to end is ended once; and when it is over, what every stock row says is reserved equals what the held lines add up to.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, TestClockConfiguration.class})
@TestPropertySource(properties = {"eureka.client.enabled=false"})
class ReservationRaceTest {

    @Autowired
    private ReservationService reservations;

    @Autowired
    private BoxCommitmentService commitments;

    @Autowired
    private StockService stock;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    private UUID x;
    private UUID y;
    private UUID box;
    private UUID product;

    @BeforeEach
    void stock() {
        TestClockConfiguration.CLOCK.set(TestClockConfiguration.T0);
        StockTestData.wipe(jdbc);
        jdbc.execute("DELETE FROM box_commitment");
        jdbc.execute("DELETE FROM product_state");
        x = UUID.randomUUID();
        y = UUID.randomUUID();
        box = UUID.randomUUID();
        stock.applyReceipt(StockTestData.simple(x, 100), context(1));
        stock.applyReceipt(StockTestData.simple(y, 100), context(2));
        stock.applyReceipt(box(box, "2026-11-09T08:00:00.123456Z"), context(3));
        product = UUID.randomUUID();
        commitments.applyProduct(new ProductSnapshot(product, "SELLABLE", "ACTIVE", box, 0, false), UUID.randomUUID(), "system:test");
    }

    private Line line(UUID variant, int qty) {
        return new Line(UUID.randomUUID(), variant, product, qty, UUID.randomUUID(), null);
    }

    private void assertConsistent() {
        List<UUID> off = jdbc.queryForList("""
                SELECT i.variant_id FROM inventory i
                 WHERE i.reserved <> coalesce((SELECT sum(l.quantity) FROM reservation_line l JOIN reservation r ON r.id = l.reservation_id
                                                WHERE r.status = 'HELD' AND l.variant_id = i.variant_id), 0)""", UUID.class);
        assertThat(off).as("reserved equals what the held lines add up to").isEmpty();
        var report = stock.reconcile(null);
        assertThat(report.countMismatches()).isEmpty();
        assertThat(report.ledgerMismatches()).isEmpty();
        assertThat(report.commitmentMismatches()).isEmpty();
    }

    /** Runs the tasks at the same instant on their own threads; every task's outcome comes back (a thrown exception is the outcome). */
    private <T> List<Object> together(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    go.await();
                    try {
                        return task.call();
                    } catch (Exception e) {
                        return e;
                    }
                }));
            }
            go.countDown();
            List<Object> outcomes = new ArrayList<>();
            for (Future<Object> f : futures) {
                outcomes.add(f.get(60, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("eight orders reach for the last 3 pieces at once: exactly one holds them, the rest are told there is not enough")
    void theLastPiecesGoToExactlyOneOrder() throws Exception {
        UUID scarce = UUID.randomUUID();
        stock.applyReceipt(StockTestData.simple(scarce, 3), context(4));
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            tasks.add(() -> reservations.reserve(UUID.randomUUID(), UUID.randomUUID(), List.of(line(scarce, 3)), null, ACTOR));
        }

        List<Object> outcomes = together(tasks);

        assertThat(outcomes.stream().filter(o -> o instanceof ReservationService.View).count()).as("one order holds them").isEqualTo(1);
        assertThat(outcomes.stream().filter(o -> o instanceof CommitmentProblem p && p.code().equals("insufficient-stock")).count()).isEqualTo(7);
        assertConsistent();
    }

    @Test
    @DisplayName("holds touching the same two pieces and the same box in opposite orders all succeed: no deadlock, nothing lost")
    void oppositeOrderHoldsDoNotDeadlock() throws Exception {
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            boolean xFirst = i % 2 == 0;
            tasks.add(() -> reservations.reserve(UUID.randomUUID(), UUID.randomUUID(),
                    xFirst ? List.of(line(x, 1), line(y, 1)) : List.of(line(y, 1), line(x, 1)), null, ACTOR));
        }

        List<Object> outcomes = together(tasks);

        assertThat(outcomes).as("every hold succeeded").allSatisfy(o -> assertThat(o).isInstanceOf(ReservationService.View.class));
        assertThat(jdbc.queryForObject("SELECT reserved FROM inventory WHERE variant_id = ?", Integer.class, box)).as("one box per piece").isEqualTo(40);
        assertConsistent();
    }

    @Test
    @DisplayName("a hold that is sold and released at the same moment ends one way only, and the stock agrees with whichever won")
    void soldOrReleasedNeverBoth() throws Exception {
        for (int round = 0; round < 10; round++) {
            UUID id = UUID.randomUUID();
            reservations.reserve(id, UUID.randomUUID(), List.of(line(x, 2)), null, ACTOR);
            int soldBefore = jdbc.queryForObject("SELECT on_hand FROM inventory WHERE variant_id = ?", Integer.class, x);

            List<Object> outcomes = together(List.of(
                    () -> reservations.consume(id, TestClockConfiguration.T0, ACTOR),
                    () -> reservations.release(id, ACTOR)));

            Status end = reservations.get(id).status();
            assertThat(end).isIn(Status.CONSUMED, Status.RELEASED);
            int onHand = jdbc.queryForObject("SELECT on_hand FROM inventory WHERE variant_id = ?", Integer.class, x);
            assertThat(onHand).as("round " + round + " " + end + " " + outcomes).isEqualTo(end == Status.CONSUMED ? soldBefore - 2 : soldBefore);
            assertConsistent();
        }
    }

    @Test
    @DisplayName("several sweeps and a release all trying to end the same lapsed holds end each one once")
    void sweepsEndEachHoldOnce() throws Exception {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            UUID id = UUID.randomUUID();
            ids.add(id);
            reservations.reserve(id, UUID.randomUUID(), List.of(line(x, 1)), null, ACTOR);
        }
        TestClockConfiguration.CLOCK.advance(Duration.ofMinutes(11));

        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            tasks.add(() -> reservations.expireDue(10));
        }
        tasks.add(() -> reservations.release(ids.get(0), ACTOR));
        List<Object> outcomes = together(tasks);

        assertThat(outcomes).noneMatch(o -> o instanceof Exception);
        int ended = outcomes.stream().filter(o -> o instanceof Integer).mapToInt(o -> (Integer) o).sum();
        long expired = ids.stream().filter(id -> reservations.get(id).status() == Status.EXPIRED).count();
        assertThat(expired + ids.stream().filter(id -> reservations.get(id).status() == Status.RELEASED).count()).as("every hold ended").isEqualTo(10);
        assertThat(ended).as("the sweeps ended exactly the ones the release did not").isEqualTo((int) expired);
        assertThat(jdbc.queryForObject("SELECT reserved FROM inventory WHERE variant_id = ?", Integer.class, x)).isZero();
        assertConsistent();
    }
}
