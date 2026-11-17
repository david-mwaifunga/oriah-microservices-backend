package com.oriah.payment.provider;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ZynlePay's answers carry no fee, so the fee recorded on a payment is worked out from the tariff the shop agreed: a percentage of the amount plus a
 * fixed part, to the ngwee, half up. The tariff is copied onto the attempt when it starts, so a change of tariff never re-prices a payment already begun.
 */
class FeeTariffTest {

    private static BigDecimal k(String amount) {
        return new BigDecimal(amount.strip());
    }

    @ParameterizedTest(name = "K{0} at {1}% + K{2} costs K{3}")
    @DisplayName("the fee is the percentage of the amount, rounded half up to the ngwee, plus the fixed part")
    @CsvSource({
            "180.00, 2.50, 0.00, 4.50",     // an MTN payment of K180.00: 180.00 x 2.5% = 4.50
            "180.00, 3.50, 0.00, 6.30",     // the same by card
            "33.33,  2.50, 0.00, 0.83",     // 0.83325 rounds down
            "1.00,   0.50, 0.00, 0.01",     // 0.005 is exactly half: up
            "10.10,  2.50, 0.00, 0.25",     // 0.2525
            "200.00, 1.00, 0.50, 2.50",     // 2.00 + 0.50 fixed
            "180.00, 0.00, 0.00, 0.00"})
    void theFee(String amount, String percent, String fixed, String fee) {
        assertThat(new FeeTariff(k(percent), k(fixed)).fee(k(amount))).isEqualByComparingTo(fee);
        assertThat(new FeeTariff(k(percent), k(fixed)).fee(k(amount)).scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("a tariff that cannot be right is refused when it is read, not silently capped on a payment")
    void aTariffThatCannotBeRightIsRefused() {
        assertThatThrownBy(() -> new FeeTariff(k("-0.10"), k("0.00"))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("percent");
        assertThatThrownBy(() -> new FeeTariff(k("20.01"), k("0.00"))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("percent");
        assertThatThrownBy(() -> new FeeTariff(k("2.50"), k("-1.00"))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("fixed");
        assertThatThrownBy(() -> new FeeTariff(null, k("0.00"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("an amount smaller than its own fee does not fit the tariff: such a payment is not started")
    void anAmountSmallerThanItsFeeDoesNotFit() {
        FeeTariff tariff = new FeeTariff(k("2.50"), k("5.00"));

        assertThat(tariff.fits(k("5.00"))).isFalse();      // fee 0.13 + 5.00 = 5.13
        assertThat(tariff.fits(k("5.13"))).isFalse();      // fee 0.13 + 5.00 = 5.13: nothing would be left
        assertThat(tariff.fits(k("6.00"))).isTrue();
    }

    @Test
    @DisplayName("the basis says which tariff the fee came from, in words the owner can check against the agreement")
    void theBasis() {
        assertThat(new FeeTariff(k("2.5"), k("0")).basis()).isEqualTo("TARIFF 2.500% + 0.00");
    }
}
