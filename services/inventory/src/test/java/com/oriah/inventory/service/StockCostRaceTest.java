package com.oriah.inventory.service;

import java.time.Instant;
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
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.oriah.inventory.TestClockConfiguration;
import com.oriah.inventory.TestcontainersConfiguration;
import com.oriah.inventory.service.BoxCommitmentService.ProductSnapshot;
import com.oriah.inventory.service.ReservationService.Line;

import static com.oriah.inventory.service.StockTestData.ACTOR;
import static com.oriah.inventory.service.StockTestData.box;
import static com.oriah.inventory.service.StockTestData.context;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Announcing what stock cost takes the outbox's lock, which is held until commit, so it must always be the LAST lock a transaction takes: two sales that
 * hold the same two variants in opposite order must never wait for each other through it. Many pairs of sales run at once; none may be refused as a deadlock,
 * and every row that moved a cost is announced exactly once.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, TestClockConfiguration.class})
@TestPropertySource(properties = {"eureka.client.enabled=false"})
class StockCostRaceTest {

    private static final Instant SOLD_ON = Instant.parse("2026-11-10T10:00:00Z");
    private static final int ROUNDS = 12;

    @Autowired
    private StockService stock;

    @Autowired
    private ReservationService reservations;

    @Autowired
    private BoxCommitmentService commitments;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void clean() {
        TestClockConfiguration.CLOCK.set(TestClockConfiguration.T0);
        StockTestData.wipe(jdbc);
        jdbc.execute("DELETE FROM box_commitment");
        jdbc.execute("DELETE FROM product_state");
    }

    @Test
    @DisplayName("sales that take the same two variants in opposite order, at the same moment, never deadlock through the announcement, and every row is announced once")
    void oppositeOrderSalesDoNotDeadlock() throws Exception {
        UUID ringA = UUID.randomUUID();
        UUID ringB = UUID.randomUUID();
        UUID productA = UUID.randomUUID();
        UUID productB = UUID.randomUUID();
        UUID box = UUID.randomUUID();
        for (UUID[] v : new UUID[][] {{ringA, productA}, {ringB, productB}}) {
            jdbc.update("INSERT INTO product_index (variant_id, title, product_id) VALUES (?, 'Ring', ?)", v[0], v[1]);
            StockTestData.stock(stock, v[0], 60);   // enough pieces for every round: each sells one
        }
        stock.applyReceipt(box(box, "2026-11-09T09:00:00Z"), context(7002));
        commitments.applyProduct(new ProductSnapshot(productA, "SELLABLE", "ACTIVE", box, 0, false), UUID.randomUUID(), "system:test");
        commitments.applyProduct(new ProductSnapshot(productB, "SELLABLE", "ACTIVE", box, 0, false), UUID.randomUUID(), "system:test");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < ROUNDS; round++) {
                UUID first = hold(List.of(line(ringA, productA), line(ringB, productB)));
                UUID second = hold(List.of(line(ringB, productB), line(ringA, productA)));
                CountDownLatch go = new CountDownLatch(1);
                List<Future<?>> done = new ArrayList<>();
                for (UUID reservation : List.of(first, second)) {
                    done.add(pool.submit(() -> {
                        go.await();
                        reservations.consume(reservation, SOLD_ON, ACTOR);
                        return null;
                    }));
                }
                go.countDown();
                for (Future<?> f : done) {
                    f.get(60, TimeUnit.SECONDS);
                }
            }
        } finally {
            pool.shutdownNow();
        }

        long costRows = jdbc.queryForObject("SELECT count(*) FROM stock_movement WHERE role IN ('ORIGINAL', 'RECUT_REVERSAL', 'RECUT_REPLACEMENT') "
                + "AND reason <> 'CONSIGNMENT_RECEIVED'", Long.class);
        // The outbox is append-only, so it still holds what earlier tests announced; a movement id is never reused (TRUNCATE keeps the sequence), so this test's announcements are the
        // ones about movements that are in the ledger now.
        String mine = " FROM outbox WHERE event_type = 'stock.cost_recorded' AND (payload::jsonb ->> 'movementId')::bigint IN (SELECT id FROM stock_movement)";
        long announced = jdbc.queryForObject("SELECT count(*)" + mine, Long.class);
        assertThat(costRows).isGreaterThan(0);
        assertThat(announced).as("every row that moved a cost, once").isEqualTo(costRows);
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT payload::jsonb ->> 'movementId')" + mine, Long.class))
                .isEqualTo(costRows);
    }

    private static Line line(UUID variant, UUID product) {
        return new Line(UUID.randomUUID(), variant, product, 1, UUID.randomUUID(), null);
    }

    private UUID hold(List<Line> lines) {
        UUID reservation = UUID.randomUUID();
        reservations.reserve(reservation, UUID.randomUUID(), lines, null, ACTOR);
        return reservation;
    }
}
