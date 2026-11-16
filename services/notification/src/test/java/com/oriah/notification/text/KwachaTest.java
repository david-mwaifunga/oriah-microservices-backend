package com.oriah.notification.text;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How an amount is written in a message: the way the shop writes it on a receipt, "K5,875.00". Never "5875.00 ZMW", which is how a program writes it.
 */
class KwachaTest {

    @Test
    @DisplayName("five thousand eight hundred and seventy-five Kwacha is written K5,875.00")
    void writesK5875_00WithItsComma() {
        assertThat(Kwacha.write(new BigDecimal("5875.00"), "ZMW")).isEqualTo("K5,875.00");
        assertThat(Kwacha.write(new BigDecimal("1234567.5"), "ZMW")).isEqualTo("K1,234,567.50");
    }

    @Test
    @DisplayName("an amount always shows two decimals, and a small one has no comma")
    void keepsTwoDecimals() {
        assertThat(Kwacha.write(new BigDecimal("180"), "ZMW")).isEqualTo("K180.00");
        assertThat(Kwacha.write(new BigDecimal("0.5"), "ZMW")).isEqualTo("K0.50");
        assertThat(Kwacha.write(new BigDecimal("27.980"), "ZMW")).isEqualTo("K27.98");
    }

    @Test
    @DisplayName("an amount in another currency keeps its code, so it is never read as Kwacha")
    void anotherCurrencyKeepsItsCode() {
        assertThat(Kwacha.write(new BigDecimal("5875.00"), "USD")).isEqualTo("5,875.00 USD");
    }

    @Test
    @DisplayName("the writing does not depend on the language the server happens to run in")
    void theSameOnEveryServer() {
        java.util.Locale before = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY);
            assertThat(Kwacha.write(new BigDecimal("5875.00"), "ZMW")).isEqualTo("K5,875.00");
        } finally {
            java.util.Locale.setDefault(before);
        }
    }
}
