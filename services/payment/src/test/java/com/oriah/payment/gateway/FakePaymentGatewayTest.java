package com.oriah.payment.gateway;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.oriah.payment.TestcontainersConfiguration;
import com.oriah.payment.gateway.PaymentGateway.Captured;
import com.oriah.payment.gateway.PaymentGateway.ChargeResult;
import com.oriah.payment.gateway.PaymentGateway.Declined;
import com.oriah.payment.gateway.PaymentGateway.NotFound;
import com.oriah.payment.gateway.PaymentGateway.Voided;
import com.oriah.payment.gateway.PaymentGateway.VoidResult;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The fake gateway behaves like a real one in the ways that decide whether
 * money is taken twice or lost:
 *
 * <ul>
 *   <li>it remembers: the same charge reference always gets the same answer,
 *       so a repeated charge is never a second capture;</li>
 *   <li>it can be asked afterwards what became of a charge (lookup), which is
 *       how a charge whose answer never arrived gets settled;</li>
 *   <li>a charge that never arrived can be voided, and a voided reference can
 *       never be captured -- the void and a late charge race, and exactly one
 *       of them wins;</li>
 *   <li>its memory is its own: it is written in its own transaction, so it
 *       survives a caller that rolls back, and a restart.</li>
 * </ul>
 *
 * The magic cards are Stripe's: 4242... captures, 4000...0002 is declined,
 * 4000...9995 has insufficient funds, anything else is an invalid test card.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"eureka.client.enabled=false"})
@ExtendWith(OutputCaptureExtension.class)
class FakePaymentGatewayTest {

    private static final String GOOD_CARD = "4242424242424242";
    private static final String DECLINED_CARD = "4000000000000002";
    private static final BigDecimal AMOUNT = new BigDecimal("4500.00");

    @Autowired
    private PaymentGateway gateway;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
    }

    // ------------------------------------------------------------------
    // charge
    // ------------------------------------------------------------------

    @Test
    @DisplayName("4242... is captured, with the gateway's reference and the time it decided")
    void aGoodCardIsCaptured() {
        UUID ref = UUID.randomUUID();
        Instant before = Instant.now().minusSeconds(1);

        ChargeResult result = gateway.charge(ref, GOOD_CARD, AMOUNT, "ZMW");

        assertThat(result).isInstanceOf(Captured.class);
        Captured captured = (Captured) result;
        assertThat(captured.gatewayReference()).startsWith("fake_ch_");
        assertThat(captured.at()).isAfter(before).isBeforeOrEqualTo(Instant.now());
        assertThat(jdbc.queryForMap("""
                SELECT outcome, gateway_reference, amount, currency FROM fake_gateway_charge
                WHERE charge_reference = ?""", ref))
                .containsEntry("outcome", "CAPTURED")
                .containsEntry("gateway_reference", captured.gatewayReference())
                .containsEntry("amount", AMOUNT)
                .containsEntry("currency", "ZMW");
    }

    @Test
    @DisplayName("each charge logs one line that smoke-test.ps1 can count by its amount")
    void eachChargeLogsALineTheSmokeTestCounts(CapturedOutput output) {
        // smoke-test.ps1's Charge-Count finds charges in the container log by
        // exactly "FakePaymentGateway.charge amount=<amount at scale 2> ". If
        // the line changes shape, its checks count zero against zero and pass
        // whatever the gateway did.
        gateway.charge(UUID.randomUUID(), GOOD_CARD, new BigDecimal("777.70"), "ZMW");

        assertThat(output.getAll()).contains("FakePaymentGateway.charge amount=777.70 ");
    }

    @Test
    @DisplayName("whitespace in the card number is tolerated")
    void spacesInTheCardAreTolerated() {
        assertThat(gateway.charge(UUID.randomUUID(), "4242 4242 4242 4242", AMOUNT, "ZMW"))
                .isInstanceOf(Captured.class);
    }

    @Test
    @DisplayName("the decline cards are declined, each with its reason")
    void declineCardsAreDeclinedWithTheirReason() {
        assertThat(gateway.charge(UUID.randomUUID(), DECLINED_CARD, AMOUNT, "ZMW"))
                .isInstanceOfSatisfying(Declined.class, d -> assertThat(d.reason()).isEqualTo("card declined"));
        assertThat(gateway.charge(UUID.randomUUID(), "4000000000009995", AMOUNT, "ZMW"))
                .isInstanceOfSatisfying(Declined.class, d -> assertThat(d.reason()).isEqualTo("insufficient funds"));
        assertThat(gateway.charge(UUID.randomUUID(), "1234567890123456", AMOUNT, "ZMW"))
                .isInstanceOfSatisfying(Declined.class, d -> assertThat(d.reason()).isEqualTo("invalid test card"));
    }

    @Test
    @DisplayName("charging the same reference again is the same charge: same answer, nothing new captured")
    void aChargeIsIdempotentByReference() {
        UUID ref = UUID.randomUUID();
        ChargeResult first = gateway.charge(ref, GOOD_CARD, AMOUNT, "ZMW");

        // Even with a card that would be declined: the reference has its answer.
        ChargeResult again = gateway.charge(ref, DECLINED_CARD, AMOUNT, "ZMW");

        assertThat(again).isEqualTo(first);
        assertThat(rowsFor(ref)).isEqualTo(1);
    }

    @Test
    @DisplayName("its answer is its own: a caller that rolls back does not undo the charge")
    void aChargeSurvivesTheCallersRollback() {
        UUID ref = UUID.randomUUID();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            gateway.charge(ref, GOOD_CARD, AMOUNT, "ZMW");
            status.setRollbackOnly();
        });

        assertThat(gateway.lookup(ref)).isInstanceOf(Captured.class);
    }

    // ------------------------------------------------------------------
    // lookup
    // ------------------------------------------------------------------

    @Test
    @DisplayName("lookup answers what became of a reference, or that it never arrived")
    void lookupTellsWhatHappened() {
        UUID captured = UUID.randomUUID();
        UUID declined = UUID.randomUUID();
        UUID voided = UUID.randomUUID();
        ChargeResult capture = gateway.charge(captured, GOOD_CARD, AMOUNT, "ZMW");
        ChargeResult decline = gateway.charge(declined, DECLINED_CARD, AMOUNT, "ZMW");
        VoidResult voiding = gateway.voidUnsettled(voided);

        assertThat(gateway.lookup(captured)).isEqualTo(capture);
        assertThat(gateway.lookup(declined)).isEqualTo(decline);
        assertThat(gateway.lookup(voided)).isEqualTo(voiding);
        assertThat(gateway.lookup(UUID.randomUUID())).isInstanceOf(NotFound.class);
    }

    @Test
    @DisplayName("outcomes survive a restart: a new gateway reads the same answers from the table")
    void outcomesSurviveARestart() {
        UUID ref = UUID.randomUUID();
        ChargeResult before = gateway.charge(ref, GOOD_CARD, AMOUNT, "ZMW");

        // A new instance has nothing in memory: everything it knows, it reads.
        PaymentGateway restarted = new FakePaymentGateway(dataSource, transactionManager, Clock.systemUTC());

        assertThat(restarted.lookup(ref)).isEqualTo(before);
        assertThat(restarted.charge(ref, GOOD_CARD, AMOUNT, "ZMW")).isEqualTo(before);
        assertThat(rowsFor(ref)).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // voidUnsettled
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a reference voided before its charge arrives can never be captured")
    void aVoidedReferenceIsNeverCaptured() {
        UUID ref = UUID.randomUUID();
        VoidResult voided = gateway.voidUnsettled(ref);
        assertThat(voided).isInstanceOf(Voided.class);

        ChargeResult lateCharge = gateway.charge(ref, GOOD_CARD, AMOUNT, "ZMW");

        assertThat(lateCharge).isEqualTo(voided);
        assertThat(jdbc.queryForObject(
                "SELECT outcome FROM fake_gateway_charge WHERE charge_reference = ?", String.class, ref))
                .isEqualTo("VOIDED");
    }

    @Test
    @DisplayName("voiding a charge that was already captured answers the capture, and voids nothing")
    void voidingACaptureAnswersTheCapture() {
        UUID ref = UUID.randomUUID();
        ChargeResult captured = gateway.charge(ref, GOOD_CARD, AMOUNT, "ZMW");

        assertThat(gateway.voidUnsettled(ref)).isEqualTo(captured);
        assertThat(gateway.lookup(ref)).isEqualTo(captured);
    }

    @Test
    @DisplayName("voiding a charge that was already declined answers the decline")
    void voidingADeclineAnswersTheDecline() {
        UUID ref = UUID.randomUUID();
        ChargeResult declined = gateway.charge(ref, DECLINED_CARD, AMOUNT, "ZMW");

        assertThat(gateway.voidUnsettled(ref)).isEqualTo(declined);
    }

    @Test
    @DisplayName("a charge and a void of one reference at the same moment: exactly one outcome, both see it")
    void chargeAndVoidRaceToOneOutcome() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            int captures = 0;
            int voids = 0;
            for (int round = 0; round < 20; round++) {
                UUID ref = UUID.randomUUID();
                CyclicBarrier start = new CyclicBarrier(2);
                Future<ChargeResult> charge = pool.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    return gateway.charge(ref, GOOD_CARD, AMOUNT, "ZMW");
                });
                Future<VoidResult> voiding = pool.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    return gateway.voidUnsettled(ref);
                });

                ChargeResult charged = charge.get(30, TimeUnit.SECONDS);
                VoidResult voided = voiding.get(30, TimeUnit.SECONDS);

                assertThat(charged).as("round %d: both callers see the one outcome", round).isEqualTo(voided);
                assertThat(rowsFor(ref)).isEqualTo(1);
                assertThat(gateway.lookup(ref)).isEqualTo(charged);
                if (charged instanceof Captured) {
                    captures++;
                } else {
                    assertThat(charged).isInstanceOf(Voided.class);
                    voids++;
                }
            }
            assertThat(captures + voids).isEqualTo(20);
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------

    private int rowsFor(UUID ref) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM fake_gateway_charge WHERE charge_reference = ?", Integer.class, ref);
    }
}
