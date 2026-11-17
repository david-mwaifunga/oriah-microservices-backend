package com.oriah.payment.events;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.oriah.events.EventJson;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The bytes of the events a ZynlePay payment writes (Phase 13), pinned against the contract files order, finance and notification read.
 *
 * <p>{@code payment.confirmed} keeps its version: a confirmation written from a verified provider payment carries two more members at the END
 * ({@code provider}, {@code providerPaymentId}), and one staff keyed carries neither, so its bytes are what they were in Phase 7.
 */
class ProviderEventContractTest {

    private static final UUID ORDER = UUID.fromString("0f9a4c2e-6b1d-4e8f-a3c5-7d9e1b2f4a60");
    private static final UUID ATTEMPT = UUID.fromString("a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d");
    private static final UUID PROVIDER_CONFIRMATION = UUID.fromString("7e90b1c3-5b7d-4e90-b1c3-df5b7d9f10d6");

    private static String contract(String name) throws IOException {
        return Files.readString(Path.of("src/test/resources/contracts", name), StandardCharsets.UTF_8).strip();
    }

    @Test
    @DisplayName("a staff confirmation's bytes are unchanged: no provider member at all")
    void aStaffConfirmationCarriesNoProviderMember() throws IOException {
        String json = EventJson.write(new PaymentConfirmedEvent(UUID.fromString("6d8fa0b2-4a6c-4d8f-a0b2-ce4a6c8e0fc5"), ORDER, "MTN", "MP241105.0915.A12345",
                new BigDecimal("180.00"), new BigDecimal("3.40"), new BigDecimal("176.60"), "ZMW", Instant.parse("2026-11-05T09:15:00Z"), null, "user:kc-sara",
                Instant.parse("2026-11-05T09:20:11.5Z"), 1, null, null));

        assertThat(json).isEqualTo(contract("payment-confirmed-v1.json")).doesNotContain("provider");
    }

    @Test
    @DisplayName("a confirmation from a verified ZynlePay payment says so, at the end, on every announcement")
    void aProviderConfirmationNamesItsProviderAndAttempt() throws IOException {
        // attempt 2: an announcement repeated by the watcher has the same shape as the first.
        String json = EventJson.write(new PaymentConfirmedEvent(PROVIDER_CONFIRMATION, ORDER, "MTN", "OZ7K2M9Q4T8X1B5D", new BigDecimal("180.00"),
                new BigDecimal("4.50"), new BigDecimal("175.50"), "ZMW", Instant.parse("2026-11-05T09:15:40Z"), null, "system:zynlepay",
                Instant.parse("2026-11-05T09:15:41.25Z"), 2, "ZYNLEPAY", ATTEMPT));

        assertThat(json).isEqualTo(contract("payment-confirmed-v1-provider.json"));
    }

    @Test
    @DisplayName("payment.provider_payment_updated: a verified payment")
    void aVerifiedProviderPayment() throws IOException {
        String json = EventJson.write(new ProviderPaymentUpdatedEvent(ATTEMPT, ORDER, "kc-alice", "MOBILE_MONEY", "MTN", "VERIFIED", null, null,
                new BigDecimal("180.00"), "ZMW", PROVIDER_CONFIRMATION, Instant.parse("2026-11-05T09:14:55Z"), Instant.parse("2026-11-05T09:15:41.25Z")));

        assertThat(json).isEqualTo(contract("payment-provider-payment-updated-v1.json"));
    }

    @Test
    @DisplayName("payment.provider_payment_updated: an attempt refused before anything was sent has no amount, and no member is written as null")
    void anAttemptThatFailedBeforeItWasSent() throws IOException {
        String json = EventJson.write(new ProviderPaymentUpdatedEvent(ATTEMPT, ORDER, "kc-alice", "CARD", "CARD", "FAILED", "ORDER_REFUSED", null, null, null,
                null, Instant.parse("2026-11-05T09:14:55Z"), Instant.parse("2026-11-05T09:14:56Z")));

        assertThat(json).isEqualTo(contract("payment-provider-payment-updated-v1-not-sent.json")).doesNotContain("null");
    }

    @Test
    @DisplayName("neither event can carry a phone number, a provider reference or the owner's evidence: the records have no such member")
    void theEventsHaveNoMemberForAPhoneAReferenceOrEvidence() {
        assertThat(java.util.Arrays.stream(ProviderPaymentUpdatedEvent.class.getRecordComponents()).map(c -> c.getName().toLowerCase()))
                .noneMatch(n -> n.contains("msisdn") || n.contains("phone") || n.contains("reference") || n.contains("evidence") || n.contains("redirect")
                        || n.contains("transaction"));
    }
}
