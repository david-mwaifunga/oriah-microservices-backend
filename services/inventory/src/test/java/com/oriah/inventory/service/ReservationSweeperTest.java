package com.oriah.inventory.service;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.oriah.inventory.TestClockConfiguration;
import com.oriah.inventory.TestcontainersConfiguration;
import com.oriah.inventory.service.BoxCommitmentService.ProductSnapshot;
import com.oriah.inventory.service.ReservationService.Line;
import com.oriah.inventory.service.ReservationService.Status;

import static com.oriah.inventory.service.StockTestData.ACTOR;
import static com.oriah.inventory.service.StockTestData.box;
import static com.oriah.inventory.service.StockTestData.context;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Nothing but a clock ends a hold nobody sells or releases: the sweeper looks for holds past their time every half minute and ends them,
 * a batch at a time. It shares the scheduler with the outbox relay, and Spring's default scheduler has ONE thread, which a relay round
 * blocked on a stopped Kafka can hold for 25 seconds, so the pool is two.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, TestClockConfiguration.class})
@TestPropertySource(properties = {"eureka.client.enabled=false"})
class ReservationSweeperTest {

    @Autowired
    private ReservationSweeper sweeper;

    @Autowired
    private ReservationService reservations;

    @Autowired
    private BoxCommitmentService commitments;

    @Autowired
    private StockService stock;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Environment environment;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    private UUID ring;
    private UUID product;

    @BeforeEach
    void stockOnTheShelf() {
        TestClockConfiguration.CLOCK.set(TestClockConfiguration.T0);
        StockTestData.wipe(jdbc);
        jdbc.execute("DELETE FROM box_commitment");
        jdbc.execute("DELETE FROM product_state");
        ring = UUID.randomUUID();
        UUID box = UUID.randomUUID();
        StockTestData.stock(stock, ring, 5);
        stock.applyReceipt(box(box, "2026-11-09T08:00:00.123456Z"), context(3));
        product = UUID.randomUUID();
        commitments.applyProduct(new ProductSnapshot(product, "SELLABLE", "ACTIVE", box, 0, false), UUID.randomUUID(), "system:test");
    }

    @Test
    @DisplayName("a sweep ends the holds whose time has passed and leaves the others")
    void aSweepEndsLapsedHolds() {
        UUID lapsed = UUID.randomUUID();
        UUID fresh = UUID.randomUUID();
        reservations.reserve(lapsed, UUID.randomUUID(), List.of(new Line(UUID.randomUUID(), ring, product, 1, UUID.randomUUID(), null)), null, ACTOR);
        TestClockConfiguration.CLOCK.advance(Duration.ofMinutes(11));
        reservations.reserve(fresh, UUID.randomUUID(), List.of(new Line(UUID.randomUUID(), ring, product, 1, UUID.randomUUID(), null)), null, ACTOR);

        int ended = sweeper.sweep();

        assertThat(ended).isEqualTo(1);
        assertThat(reservations.get(lapsed).status()).isEqualTo(Status.EXPIRED);
        assertThat(reservations.get(fresh).status()).isEqualTo(Status.HELD);
    }

    @Test
    @DisplayName("the sweep is a scheduled job, every thirty seconds unless set otherwise")
    void theSweepIsScheduled() throws Exception {
        Scheduled scheduled = ReservationSweeper.class.getMethod("scheduled").getAnnotation(Scheduled.class);

        assertThat(scheduled).as("the method that runs on the clock").isNotNull();
        assertThat(scheduled.fixedDelayString()).contains("oriah.inventory.reservations.sweep.interval").contains("PT30S");
    }

    @Test
    @DisplayName("the scheduler has two threads: the relay and the sweeper never wait on each other")
    void twoSchedulerThreads() {
        assertThat(environment.getProperty("spring.task.scheduling.pool.size", Integer.class)).isEqualTo(2);
    }
}
