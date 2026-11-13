package com.oriah.payment.confirmation;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.oriah.payment.TestcontainersConfiguration;
import com.oriah.payment.dto.ConfirmPaymentRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What order's answers do to a confirmation, and what they refuse to do (Phase 7). Order and payment answer each other asynchronously, so an answer can
 * arrive after a void, after the other answer, or while a void is being made: none of it may lose or invent money. Payment never contradicts what it has
 * recorded; where the two services disagree it says so (a log at ERROR and a mark on the record) rather than quietly picking one.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"eureka.client.enabled=false"})
class SettlementOutcomesTest {

    private static final Staff SAM = new Staff("user:kc-sam", "Sam Phiri", false);

    @Autowired
    private SettlementOutcomes outcomes;

    @Autowired
    private ConfirmationService confirmations;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactions;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    private UUID confirmed(UUID order) {
        return confirmations.confirm(new ConfirmPaymentRequest(order, "MTN", "MP" + UUID.randomUUID().toString().replace("-", "").substring(0, 10),
                new BigDecimal("1000.00"), new BigDecimal("10.00"), null, null, null, null), "key-" + UUID.randomUUID(), SAM).view().id();
    }

    private Map<String, Object> row(UUID id) {
        return jdbc.queryForMap("SELECT outcome, outcome_reason FROM payment_confirmation WHERE id = ?", id);
    }

    private long events(UUID id, String type) {
        return jdbc.queryForObject("SELECT count(*) FROM outbox WHERE aggregate_type = 'payment_confirmation' AND aggregate_id = ? AND event_type = ?", Long.class, id.toString(), type);
    }

    @Test
    @DisplayName("money that paid a sale after it was voided is SETTLED, because it did pay the sale, and the record says so for good: SETTLED_AFTER_VOID, voided, never silently fine")
    void paidAfterAVoid() {
        UUID order = UUID.randomUUID();
        UUID id = confirmed(order);
        confirmations.voidConfirmation(id, "keyed against the wrong order", SAM);

        outcomes.settled(id);

        assertThat(row(id)).containsEntry("outcome", "SETTLED").containsEntry("outcome_reason", "SETTLED_AFTER_VOID");
        assertThat(confirmations.get(id).voided()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM held_money WHERE confirmation_id = ?", Long.class, id)).isZero();
    }

    @Test
    @DisplayName("an ordinary SETTLED has no reason; and order saying 'paid' for money payment holds changes nothing (the two disagree: logged, not 'fixed')")
    void settledIsPlainAndHeldIsNotOverturned() {
        UUID paid = confirmed(UUID.randomUUID());
        UUID held = confirmed(UUID.randomUUID());
        outcomes.unsettled(held, "WRONG_AMOUNT");

        outcomes.settled(paid);
        outcomes.settled(held);

        assertThat(row(paid)).containsEntry("outcome", "SETTLED").containsEntry("outcome_reason", null);
        assertThat(row(held)).containsEntry("outcome", "HELD").containsEntry("outcome_reason", "WRONG_AMOUNT");
        assertThat(events(held, "payment.held")).isEqualTo(1);
    }

    @Test
    @DisplayName("a voided confirmation is not held: it stays PENDING, no money is owed and no payment.held is announced")
    void aVoidedConfirmationIsNotHeld() {
        UUID id = confirmed(UUID.randomUUID());
        confirmations.voidConfirmation(id, "keyed against the wrong order", SAM);

        outcomes.unsettled(id, "ORDER_UNKNOWN");

        assertThat(row(id)).containsEntry("outcome", "PENDING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM held_money WHERE confirmation_id = ?", Long.class, id)).isZero();
        assertThat(events(id, "payment.held")).isZero();
    }

    @Test
    @DisplayName("a void that commits while order's 'could not pay' is waiting for the confirmation's row is NOT missed: the money is not held (the void is looked for after the lock is taken)")
    void aVoidLandingWhileTheAnswerWaits() throws Exception {
        UUID id = confirmed(UUID.randomUUID());
        TransactionTemplate tx = new TransactionTemplate(transactions);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch voidNow = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        // A: a person voiding, holding the row, with the void not yet committed.
        Future<?> voiding = pool.submit(() -> tx.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT id FROM payment_confirmation WHERE id = ? FOR UPDATE", UUID.class, id);
            locked.countDown();
            try {
                voidNow.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            jdbc.update("INSERT INTO payment_confirmation_void (id, confirmation_id, voided_by, reason, voided_at) VALUES (?, ?, 'user:kc-sam', 'keyed against the wrong order', now())",
                    UUID.randomUUID(), id);
            jdbc.update("UPDATE payment_confirmation SET reference_key = NULL WHERE id = ?", id);
        }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        // B: order's answer arrives and waits for the row.
        Future<?> answering = pool.submit(() -> outcomes.unsettled(id, "WRONG_AMOUNT"));
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline && jdbc.queryForObject(
                "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND query LIKE '%FROM payment_confirmation c%'", Long.class) == 0) {
            Thread.sleep(50);
        }
        voidNow.countDown();
        voiding.get(10, TimeUnit.SECONDS);
        answering.get(10, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM held_money WHERE confirmation_id = ?", Long.class, id)).as("voided money is not owed").isZero();
        assertThat(row(id)).containsEntry("outcome", "PENDING");
        assertThat(List.of(events(id, "payment.held"))).containsExactly(0L);
    }
}
