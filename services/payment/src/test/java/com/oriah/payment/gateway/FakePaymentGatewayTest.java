package com.oriah.payment.gateway;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FakePaymentGatewayTest {

    private final FakePaymentGateway gateway = new FakePaymentGateway();

    @Test
    @DisplayName("4242... returns Success with a gateway reference")
    void successCard() {
        var result = gateway.charge("4242424242424242", new BigDecimal("100.00"), "USD");
        assertThat(result).isInstanceOf(PaymentGateway.Success.class);
        assertThat(((PaymentGateway.Success) result).gatewayReference()).startsWith("fake_ch_");
    }

    @Test
    @DisplayName("whitespace in the card number is tolerated")
    void successCardWithSpaces() {
        var result = gateway.charge("4242 4242 4242 4242", new BigDecimal("1.00"), "USD");
        assertThat(result).isInstanceOf(PaymentGateway.Success.class);
    }

    @Test
    @DisplayName("4000...0002 declines with 'card declined'")
    void declinedCard() {
        var result = gateway.charge("4000000000000002", new BigDecimal("1.00"), "USD");
        assertThat(result).isInstanceOf(PaymentGateway.Failure.class);
        assertThat(((PaymentGateway.Failure) result).reason()).isEqualTo("card declined");
    }

    @Test
    @DisplayName("4000...9995 fails with 'insufficient funds'")
    void insufficientFunds() {
        var result = gateway.charge("4000000000009995", new BigDecimal("1.00"), "USD");
        assertThat(result).isInstanceOf(PaymentGateway.Failure.class);
        assertThat(((PaymentGateway.Failure) result).reason()).isEqualTo("insufficient funds");
    }

    @Test
    @DisplayName("any other card number is invalid")
    void invalidCard() {
        var result = gateway.charge("1234567890123456", new BigDecimal("1.00"), "USD");
        assertThat(result).isInstanceOf(PaymentGateway.Failure.class);
        assertThat(((PaymentGateway.Failure) result).reason()).isEqualTo("invalid test card");
    }
}
