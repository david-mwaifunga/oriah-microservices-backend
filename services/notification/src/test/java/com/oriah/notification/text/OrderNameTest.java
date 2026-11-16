package com.oriah.notification.text;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How a message names an order. A person cannot read a 36-character id back over the phone, so before the order is paid it is the first eight
 * characters in capitals, and once it is paid it is the receipt number the shop itself uses.
 */
class OrderNameTest {

    private static final UUID ORDER = UUID.fromString("8a1f4b2e-3c5d-4e6f-9a0b-1c2d3e4f5a6b");

    @Test
    @DisplayName("once the receipt number is known, that is the order's name")
    void isTheReceiptNumberOnceKnown() {
        assertThat(OrderName.of(ORDER, "OR-000123")).isEqualTo("OR-000123");
    }

    @Test
    @DisplayName("before that it is the first eight characters of the id, in capitals")
    void isEightCapitalsBefore() {
        assertThat(OrderName.of(ORDER, null)).isEqualTo("8A1F4B2E");
        assertThat(OrderName.of(ORDER, " ")).isEqualTo("8A1F4B2E");
        assertThat(OrderName.code(ORDER)).isEqualTo("8A1F4B2E");
    }
}
