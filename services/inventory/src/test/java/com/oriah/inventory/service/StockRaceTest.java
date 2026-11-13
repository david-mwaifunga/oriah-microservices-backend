package com.oriah.inventory.service;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.oriah.inventory.TestcontainersConfiguration;
import com.oriah.inventory.events.ReceiptSnapshot;
import com.oriah.inventory.ledger.StockReason;
import com.oriah.inventory.repository.InventoryRepository;

import static com.oriah.inventory.service.StockTestData.ACTOR;
import static com.oriah.inventory.service.StockTestData.another;
import static com.oriah.inventory.service.StockTestData.context;
import static com.oriah.inventory.service.StockTestData.ring;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;

/**
 * Two things happen to one variant's stock at the same moment, and the loser of the race is told to try again: never
 * refused for good, never answered with a count that is already out of date.
 *
 * <p>A real race only overlaps sometimes, which would make a flaky test. So (as in ConcurrentStockChangeTest) the
 * repository is spied on and one request is held, just after it has read the stock row, until the other has had its
 * go: the overlap happens every time.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"eureka.client.enabled=false"})
class StockRaceTest {

    private static final String MONDAY = "2026-11-09T08:00:00.123456Z";
    private static final String TUESDAY = "2026-11-10T08:00:00.000001Z";

    @Autowired
    private StockService stock;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoSpyBean
    private InventoryRepository inventories;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    private ExecutorService pool;

    @BeforeEach
    void clean() {
        StockTestData.wipe(jdbc);
        pool = Executors.newFixedThreadPool(3);
    }

    @AfterEach
    void stop() {
        pool.shutdownNow();
    }

    /** The repository is an interface proxy, so the spy has no "real method" to call: its default answer forwards to it. */
    private Answer<?> realRepository() {
        return mockingDetails(inventories).getMockCreationSettings().getDefaultAnswer();
    }

    @Test
    @DisplayName("a receipt is not refused for good because a write-off committed just after it read the stock: the write-off is told to retry")
    void aReceiptRacingAWriteOff() throws Exception {
        UUID variant = UUID.randomUUID();
        ReceiptSnapshot first = ring(variant, MONDAY);
        stock.applyReceipt(first, context(1));                          // 7 rings on hand
        ReceiptSnapshot second = another(first, TUESDAY, 1);            // 7 more, a later receipt

        AtomicReference<Thread> receiving = new AtomicReference<>();
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch go = new CountDownLatch(1);
        Answer<?> real = realRepository();
        Answer<?> holdTheReceipt = invocation -> {
            Object found = real.answer(invocation);
            if (Thread.currentThread() == receiving.get() && holding.getCount() > 0) {
                holding.countDown();
                go.await(30, TimeUnit.SECONDS);
            }
            return found;
        };
        // Whichever way the receipt reads the stock row, it is held just after it has.
        doAnswer(holdTheReceipt).when(inventories).findById(any());
        doAnswer(holdTheReceipt).when(inventories).findByIdForUpdate(any());

        Future<?> receipt = pool.submit(() -> {
            receiving.set(Thread.currentThread());
            stock.applyReceipt(second, context(2));
            return null;
        });
        assertThat(holding.await(30, TimeUnit.SECONDS)).as("the receipt read the stock row").isTrue();

        // The receipt has read the row and not yet checked it against its lots. Now stock leaves.
        Future<?> writeOff = pool.submit(() -> stock.writeOff(UUID.randomUUID(), variant, 2, StockReason.WRITE_OFF_BROKEN, null, null, ACTOR));
        try {
            writeOff.get(3, TimeUnit.SECONDS);
        } catch (TimeoutException waitingForTheReceipt) {
            // Fine: it waits for the stock row the receipt holds.
        }
        go.countDown();

        receipt.get(60, TimeUnit.SECONDS);      // must not throw: a valid receipt is never parked as corrupt

        try {
            writeOff.get(60, TimeUnit.SECONDS);
        } catch (ExecutionException lost) {
            assertThat(lost.getCause()).as("the write-off that lost is told to retry").isInstanceOf(OptimisticLockingFailureException.class);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cost_lot WHERE consignment_line_id = ?", Integer.class, second.consignmentLineId()))
                .as("the receipt became lots").isEqualTo(2);
        StockService.ReconciliationReport report = stock.reconcile(variant);
        assertThat(report.countMismatches()).isEmpty();
        assertThat(report.ledgerMismatches()).isEmpty();
    }

    @Test
    @DisplayName("a write-off sent twice at the same moment is answered with the stock after the first, or told to retry: never with the count from before it")
    void aRepeatedWriteOffIsNotAnsweredWithAStaleCount() throws Exception {
        UUID variant = UUID.randomUUID();
        stock.applyReceipt(ring(variant, MONDAY), context(1));          // 7 on hand
        UUID request = UUID.randomUUID();

        AtomicReference<Thread> repeating = new AtomicReference<>(Thread.currentThread());
        AtomicBoolean armed = new AtomicBoolean(true);
        Answer<?> real = realRepository();
        doAnswer(invocation -> {
            Object found = real.answer(invocation);
            if (Thread.currentThread() == repeating.get() && armed.compareAndSet(true, false)) {
                // The repeat has read the stock (7 on hand). Now the original, with the same id, goes through and commits.
                pool.submit(() -> stock.writeOff(request, variant, 3, StockReason.WRITE_OFF_LOST, null, null, ACTOR)).get(60, TimeUnit.SECONDS);
            }
            return found;
        }).when(inventories).findById(any());

        StockService.WriteOffResult answer = null;
        try {
            answer = stock.writeOff(request, variant, 3, StockReason.WRITE_OFF_LOST, null, null, ACTOR).orElseThrow();
        } catch (OptimisticLockingFailureException toldToRetry) {
            // Safe: nothing was applied twice and nothing stale was said.
        }

        assertThat(jdbc.queryForObject("SELECT on_hand FROM inventory WHERE variant_id = ?", Integer.class, variant)).isEqualTo(4);
        if (answer != null) {
            assertThat(answer.replay()).isTrue();
            assertThat(answer.onHand()).as("the count after the original write-off, not before it").isEqualTo(4);
            assertThat(answer.available()).isEqualTo(4);
        }
    }
}
