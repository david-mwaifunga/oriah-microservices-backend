package com.oriah.payment.gateway;

/**
 * Strategy interface for charging a card. Today there's one
 * implementation ({@link FakePaymentGateway}); a future
 * {@code StripePaymentGateway} plugs in here without touching callers.
 */
public interface PaymentGateway {

    /**
     * Attempt to charge the given card. Returns SUCCESS or a FAILURE
     * with a human-readable reason. Pure function — no DB writes, no
     * side effects beyond the (mocked) external API call.
     */
    ChargeResult charge(String cardNumber, java.math.BigDecimal amount, String currency);

    sealed interface ChargeResult permits Success, Failure {}
    record Success(String gatewayReference) implements ChargeResult {}
    record Failure(String reason) implements ChargeResult {}
}
