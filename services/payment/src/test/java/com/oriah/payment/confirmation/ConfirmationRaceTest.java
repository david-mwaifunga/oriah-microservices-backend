package com.oriah.payment.confirmation;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.oriah.payment.TestcontainersConfiguration;
import com.oriah.payment.claim.ClaimService;
import com.oriah.payment.dto.ConfirmPaymentRequest;
import com.oriah.payment.exception.PaymentProblem;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The same money keyed twice at once (Phase 7): a double click, a retry after a timeout, two people at two desks. However the requests interleave, the
 * outcome is one a person taking them one after the other could have produced: the SAME Idempotency-Key is one confirmation (the first answered 201 and
 * the rest 200, however many), the same reference under different keys is one confirmation and a refusal (409), and a confirmation racing the rejection
 * of a claim for the same reference is never half of each: either the rejection is refused because money is recorded, or the confirmation comes after
 * the rejection (a rejection does not use up the reference).
 *
 * <p>Service level, with real threads and a real Postgres: what is being proved is the locks, not the HTTP.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"eureka.client.enabled=false"})
class ConfirmationRaceTest {

    private static final Staff SAM = new Staff("user:kc-sam", "Sam Phiri", false);

    @Autowired
    private ConfirmationService confirmations;

    @Autowired
    private ClaimService claims;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    private static String reference() {
        return "MP" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
    }

    private static ConfirmPaymentRequest request(UUID order, String method, String reference) {
        return new ConfirmPaymentRequest(order, method, reference, new BigDecimal("500.00"), new BigDecimal("5.00"), null, null, null, null);
    }

    /** Runs the same number of callers at once, released together, and returns what each did: its result or the exception it threw. */
    private static <T> List<Object> together(List<Callable<T>> callers) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(callers.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        for (Callable<T> caller : callers) {
            futures.add(pool.submit(() -> {
                go.await();
                try {
                    return caller.call();
                } catch (Exception e) {
                    return e;
                }
            }));
        }
        go.countDown();
        List<Object> results = new ArrayList<>();
        for (Future<Object> future : futures) {
            results.add(future.get());
        }
        pool.shutdown();
        return results;
    }

    private long rows(String where, Object... args) {
        return jdbc.queryForObject("SELECT count(*) FROM payment_confirmation WHERE " + where, Long.class, args);
    }

    private long announcements(UUID id) {
        return jdbc.queryForObject("SELECT count(*) FROM outbox WHERE aggregate_type = 'payment_confirmation' AND aggregate_id = ? AND event_type = 'payment.confirmed'",
                Long.class, id.toString());
    }

    @Test
    @DisplayName("the same request sent four times at once with one Idempotency-Key, for a reference that is a key of its own: one confirmation, one 'created', three repeats, one announcement")
    void theSameKeyAtOnce() throws Exception {
        UUID order = UUID.randomUUID();
        String reference = reference();
        String key = "key-" + UUID.randomUUID();

        List<Object> results = together(List.of(
                () -> confirmations.confirm(request(order, "MTN", reference), key, SAM),
                () -> confirmations.confirm(request(order, "MTN", reference), key, SAM),
                () -> confirmations.confirm(request(order, "MTN", reference), key, SAM),
                () -> confirmations.confirm(request(order, "MTN", reference), key, SAM)));

        assertThat(results).as("no caller is told 'reference already used' for its own request").allSatisfy(r -> assertThat(r).isInstanceOf(ConfirmationService.Confirmed.class));
        assertThat(results).filteredOn(r -> ((ConfirmationService.Confirmed) r).created()).hasSize(1);
        assertThat(results).extracting(r -> ((ConfirmationService.Confirmed) r).view().id()).containsOnly(((ConfirmationService.Confirmed) results.getFirst()).view().id());
        assertThat(rows("order_id = ?", order)).isEqualTo(1);
        assertThat(announcements(((ConfirmationService.Confirmed) results.getFirst()).view().id())).isEqualTo(1);
    }

    @Test
    @DisplayName("the same cash request at once: the same, one confirmation (cash has no reference key to wait on)")
    void theSameKeyAtOnceForCash() throws Exception {
        UUID order = UUID.randomUUID();
        String key = "key-" + UUID.randomUUID();

        List<Object> results = together(List.of(
                () -> confirmations.confirm(request(order, "CASH", null), key, SAM),
                () -> confirmations.confirm(request(order, "CASH", null), key, SAM),
                () -> confirmations.confirm(request(order, "CASH", null), key, SAM)));

        assertThat(results).allSatisfy(r -> assertThat(r).isInstanceOf(ConfirmationService.Confirmed.class));
        assertThat(results).filteredOn(r -> ((ConfirmationService.Confirmed) r).created()).hasSize(1);
        assertThat(rows("order_id = ?", order)).isEqualTo(1);
    }

    @Test
    @DisplayName("one reference keyed by four people at once, each with a key of their own: exactly one is recorded and the other three are told 'reference-already-used'")
    void theSameReferenceAtOnce() throws Exception {
        String reference = reference();
        List<UUID> orders = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

        List<Callable<ConfirmationService.Confirmed>> callers = new ArrayList<>();
        for (UUID order : orders) {
            callers.add(() -> confirmations.confirm(request(order, "MTN", reference), "key-" + UUID.randomUUID(), SAM));
        }
        List<Object> results = together(callers);

        assertThat(results).filteredOn(r -> r instanceof ConfirmationService.Confirmed).hasSize(1);
        assertThat(results).filteredOn(r -> r instanceof PaymentProblem).hasSize(3).allSatisfy(r -> assertThat(((PaymentProblem) r).code()).isEqualTo("reference-already-used"));
        assertThat(rows("reference_key = ?", "MTN:" + reference)).isEqualTo(1);
    }

    @Test
    @DisplayName("a confirmation and the rejection of a claim for the same reference at once, over and over: never a rejection of a claim that money was already confirmed for")
    void confirmingWhileRejecting() throws Exception {
        for (int round = 0; round < 8; round++) {
            UUID order = UUID.randomUUID();
            String reference = reference();
            UUID claim = UUID.randomUUID();
            claims.record(claim, order, "MTN", reference, java.time.Instant.now(), null);

            List<Object> results = together(List.of(
                    () -> confirmations.confirm(request(order, "MTN", reference), "key-" + UUID.randomUUID(), SAM),
                    () -> claims.reject(claim, "NOT_FOUND_AT_PROVIDER", "checked the MTN agent portal: nothing there", SAM)));

            Object confirmation = results.get(0);
            Object rejection = results.get(1);
            assertThat(confirmation).as("a confirmation is never refused here: a rejection does not use up the reference").isInstanceOf(ConfirmationService.Confirmed.class);
            long confirmedOutbox = jdbc.queryForObject("SELECT outbox_id FROM outbox WHERE aggregate_type = 'payment_confirmation' AND aggregate_id = ? "
                    + "AND event_type = 'payment.confirmed'", Long.class, ((ConfirmationService.Confirmed) confirmation).view().id().toString());
            if (rejection instanceof PaymentProblem refused) {
                assertThat(refused.code()).isEqualTo("money-recorded-for-claim");
            } else {
                long rejectedOutbox = jdbc.queryForObject("SELECT outbox_id FROM outbox WHERE aggregate_type = 'payment_claim' AND aggregate_id = ? "
                        + "AND event_type = 'payment.rejected'", Long.class, claim.toString());
                assertThat(rejectedOutbox).as("round " + round + ": the rejection came first, so the confirmation came after it").isLessThan(confirmedOutbox);
            }
        }
    }
}
