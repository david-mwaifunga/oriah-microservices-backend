package com.oriah.payment.confirmation;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
import com.oriah.payment.dto.ConfirmPaymentRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Payment is the detector of money with no outcome (Phase 7): a confirmation stays PENDING until order says it paid the sale or could not, and if
 * order was down or a message went astray nothing else would ever notice. So while a confirmation has waited longer than the settlement window (five
 * minutes) with no answer, the watcher ANNOUNCES IT AGAIN: the same confirmation id in a new payment.confirmed event, which order treats as a repeat
 * if it has already seen it. The announcements back off, and stop at a limit, past which a person has the pending list.
 *
 * <p>The passes are called with the time they should judge by, so no test waits: the schedule itself is proved in {@code WatcherScheduleTest}.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"eureka.client.enabled=false"})
class SettlementWatcherTest {

    private static final Staff SAM = new Staff("user:kc-sam", "Sam Phiri", false);

    @Autowired
    private SettlementWatcher watcher;

    @Autowired
    private ConfirmationService confirmations;

    @Autowired
    private SettlementOutcomes outcomes;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    private UUID confirmed(String gross) {
        return confirmations.confirm(new ConfirmPaymentRequest(UUID.randomUUID(), "MTN", "MP" + UUID.randomUUID().toString().replace("-", "").substring(0, 10),
                new BigDecimal(gross), new BigDecimal("10.00"), null, null, null, null), "key-" + UUID.randomUUID(), SAM).view().id();
    }

    private List<Map<String, Object>> events(UUID id) {
        return jdbc.queryForList("SELECT event_type, seq, actor, occurred_at, payload::text AS payload FROM outbox WHERE aggregate_type = 'payment_confirmation' "
                + "AND aggregate_id = ? ORDER BY seq", id.toString());
    }

    private int attempts(UUID id) {
        return jdbc.queryForObject("SELECT emit_attempts FROM payment_confirmation WHERE id = ?", Integer.class, id);
    }

    private static Instant after(Duration d) {
        return Instant.now().plus(d);
    }

    @Test
    @DisplayName("a confirmation with no answer after the settlement window is announced again: the same confirmation id in a new event, dated to the arrival, by the watcher, saying it is the second announcement")
    void announcedAgain() {
        UUID id = confirmed("1000.00");
        List<Map<String, Object>> first = events(id);

        watcher.reemitDue(after(Duration.ofMinutes(6)));

        List<Map<String, Object>> events = events(id);
        assertThat(events).extracting(e -> e.get("event_type")).containsExactly("payment.confirmed", "payment.confirmed");
        assertThat(events.get(1).get("seq")).isEqualTo(2L);
        assertThat(events.get(1).get("actor")).isEqualTo("system:payment-watcher");
        assertThat(events.get(1).get("occurred_at")).as("still dated to the arrival, not to the announcement").isEqualTo(first.getFirst().get("occurred_at"));
        assertThat((String) events.get(1).get("payload")).contains("\"confirmationId\":\"" + id + "\"").contains("\"attempt\":2").contains("\"gross\":1000.00");
        assertThat(attempts(id)).isEqualTo(2);
    }

    @Test
    @DisplayName("a confirmation younger than the window is left alone: order is probably just about to answer")
    void notYetStuck() {
        UUID id = confirmed("1000.00");

        int announced = watcher.reemitDue(after(Duration.ofMinutes(4)));

        assertThat(events(id)).hasSize(1);
        assertThat(attempts(id)).isEqualTo(1);
        assertThat(announced).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("announcements back off: a second pass right after announces nothing, and the next one is further out each time")
    void backsOff() {
        UUID id = confirmed("1000.00");
        Instant sixMinutes = after(Duration.ofMinutes(6));

        watcher.reemitDue(sixMinutes);
        watcher.reemitDue(sixMinutes.plusSeconds(30));
        assertThat(events(id)).as("not again 30 seconds later").hasSize(2);

        watcher.reemitDue(sixMinutes.plus(Duration.ofMinutes(6)));
        assertThat(events(id)).as("the backoff after the first repeat is a window long").hasSize(3);

        watcher.reemitDue(sixMinutes.plus(Duration.ofMinutes(6)).plus(Duration.ofMinutes(6)));
        assertThat(events(id)).as("and the one after that is twice as long").hasSize(3);

        watcher.reemitDue(sixMinutes.plus(Duration.ofMinutes(6)).plus(Duration.ofMinutes(11)));
        assertThat(events(id)).hasSize(4);
    }

    @Test
    @DisplayName("once order has answered (settled or held) or the confirmation was voided, it is not announced again")
    void onlyWhatHasNoAnswer() {
        UUID settled = confirmed("100.00");
        UUID held = confirmed("200.00");
        UUID voided = confirmed("300.00");
        outcomes.settled(settled);
        outcomes.unsettled(held, "WRONG_AMOUNT");
        confirmations.voidConfirmation(voided, "keyed against the wrong order", SAM);
        int eventsOfHeld = events(held).size();

        watcher.reemitDue(after(Duration.ofHours(1)));

        assertThat(events(settled)).hasSize(1);
        assertThat(events(held)).hasSize(eventsOfHeld);
        assertThat(events(voided)).extracting(e -> e.get("event_type")).containsExactly("payment.confirmed", "payment.voided");
    }

    @Test
    @DisplayName("it stops at the limit (ten announcements in all) and leaves the confirmation to a person: still pending, flagged stuck, never announced an eleventh time")
    void stopsAtTheLimit() {
        UUID id = confirmed("1000.00");
        Instant when = after(Duration.ofMinutes(6));

        for (int pass = 0; pass < 30; pass++) {
            watcher.reemitDue(when);
            when = when.plus(Duration.ofHours(3));
        }

        assertThat(attempts(id)).isEqualTo(10);
        assertThat(events(id)).hasSize(10);
        assertThat(confirmations.pending(when)).filteredOn(p -> p.id().equals(id)).singleElement().satisfies(p -> assertThat(p.stuck()).isTrue());
    }

    @Autowired
    private io.micrometer.core.instrument.MeterRegistry meters;

    /** A confirmation keyed an hour ago (inserted as such: a confirmation's time can never be changed), still with no outcome. */
    private UUID keyedAnHourAgo() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO payment_confirmation (id, order_id, method, reference, reference_key, gross, fee, currency, arrived_at, confirmed_by, confirmed_at,
                                                  idempotency_key, request_fingerprint)
                VALUES (?, ?, 'CASH', NULL, NULL, 100, 0, 'ZMW', now() - interval '1 hour', 'user:kc-sam', now() - interval '1 hour', ?, 'fp')""",
                id, UUID.randomUUID(), "old-" + id);
        return id;
    }

    private double gauge() {
        return meters.get("payment.confirmations.stuck").gauge().value();
    }

    @Test
    @DisplayName("the stuck count is the gauge payment.confirmations.stuck: it rises by one for money an hour old with no outcome, falls when order answers, ignores a voided one and a fresh one, and survives garbage collection")
    void theGauge() throws Exception {
        double before = gauge();
        UUID old = keyedAnHourAgo();
        UUID voided = keyedAnHourAgo();
        confirmations.voidConfirmation(voided, "keyed against the wrong order", SAM);
        confirmed("1000.00");   // fresh: not stuck

        for (int i = 0; i < 5; i++) {
            System.gc();
            Thread.sleep(50);
        }
        double stuck = gauge();
        outcomes.settled(old);
        double afterAnswer = gauge();

        assertThat(stuck).as("a number, after garbage collection: the meter keeps what it reads").isEqualTo(before + 1);
        assertThat(afterAnswer).isEqualTo(before);
    }

    @Test
    @DisplayName("the stuck count is how many confirmations have waited past the window with no answer, for a gauge and an alert")
    void theStuckCount() {
        UUID id = confirmed("1000.00");
        long before = watcher.stuckCount(Instant.now());

        long later = watcher.stuckCount(after(Duration.ofMinutes(6)));
        outcomes.settled(id);
        long afterAnswer = watcher.stuckCount(after(Duration.ofMinutes(6)));

        assertThat(later).isGreaterThanOrEqualTo(before + 1);
        assertThat(afterAnswer).isEqualTo(later - 1);
    }
}
