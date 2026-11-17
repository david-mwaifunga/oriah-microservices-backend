package com.oriah.payment.provider;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Which wallet a Zambian mobile number belongs to, by its prefix. It decides the method the money is recorded under (MTN, AIRTEL, ZAMTEL), the
 * tariff and the words the customer reads; the shopper types only the number.
 */
class WalletsTest {

    private static final Wallets WALLETS = new Wallets(Map.of("MTN", List.of("96", "76"), "AIRTEL", List.of("97", "77"), "ZAMTEL", List.of("95", "75")));

    @ParameterizedTest(name = "{0} is {1}")
    @DisplayName("the two digits after +260 name the wallet")
    @CsvSource({"+260961234567, MTN", "+260761234567, MTN", "+260971234567, AIRTEL", "+260771234567, AIRTEL", "+260951234567, ZAMTEL",
            "+260751234567, ZAMTEL"})
    void theWallet(String msisdn, String wallet) {
        assertThat(WALLETS.of(msisdn)).contains(wallet);
    }

    @Test
    @DisplayName("a landline, a number of another country or a prefix nobody listed is no wallet")
    void noWallet() {
        assertThat(WALLETS.of("+260211234567")).isEmpty();
        assertThat(WALLETS.of("+260941234567")).isEmpty();
        assertThat(WALLETS.of("+27821234567")).isEmpty();
        assertThat(WALLETS.of("0961234567")).isEmpty();        // not in the one form numbers are kept in
        assertThat(WALLETS.of(null)).isEmpty();
    }

    @Test
    @DisplayName("what is sent to ZynlePay is the digits without the plus")
    void theDigitsZynlePayIsSent() {
        assertThat(Wallets.digits("+260961234567")).isEqualTo("260961234567");
    }

    @Test
    @DisplayName("shown to staff and to the shopper, a number keeps its first five and last three characters")
    void masked() {
        assertThat(Wallets.masked("+260961234567")).isEqualTo("+2609*****567");
        assertThat(Wallets.masked(null)).isNull();
    }

    @Test
    @DisplayName("a prefix listed under two wallets, or a wallet that is not one of the three, is refused when the settings are read")
    void settingsThatCannotBeRight() {
        assertThatThrownBy(() -> new Wallets(Map.of("MTN", List.of("96"), "AIRTEL", List.of("96")))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("96");
        assertThatThrownBy(() -> new Wallets(Map.of("VODAFONE", List.of("94")))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("VODAFONE");
        assertThatThrownBy(() -> new Wallets(Map.of("MTN", List.of("9")))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("'9'");
    }
}
