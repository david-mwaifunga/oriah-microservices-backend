package com.oriah.catalog.pricing;

import java.math.BigDecimal;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A suggested price: the landed cost (the same highest-held figure the warning uses) plus a target profit that is a
 * percentage OF the landed cost (the client, round 2: "percentage profit of the full landing price"). Rounded UP to the
 * ngwee so the target is never missed by a rounding, and only ever a suggestion.
 */
class PriceSuggestionTest {

    @Test
    @DisplayName("landed cost K1,528.99 with a 40% target suggests K2,140.59 (2,140.586 rounded up to the ngwee)")
    void roundsUpToTheNgwee() {
        assertThat(PriceSuggestion.of(new BigDecimal("1528.99"), new BigDecimal("40"))).contains(new BigDecimal("2140.59"));
    }

    @Test
    @DisplayName("any part of an ngwee goes UP, not to the nearest: K100.03 at 10% is 110.033, which is K110.04 (half-up would say K110.03)")
    void anyFractionGoesUp() {
        assertThat(PriceSuggestion.of(new BigDecimal("100.03"), new BigDecimal("10"))).contains(new BigDecimal("110.04"));
        assertThat(PriceSuggestion.of(new BigDecimal("100.04"), new BigDecimal("10"))).as("110.044").contains(new BigDecimal("110.05"));
    }

    @Test
    @DisplayName("an exact figure stays exact: K100.00 at 12.5% is K112.50, and 0% suggests the landed cost itself")
    void exactStaysExact() {
        assertThat(PriceSuggestion.of(new BigDecimal("100.00"), new BigDecimal("12.50"))).contains(new BigDecimal("112.50"));
        assertThat(PriceSuggestion.of(new BigDecimal("100.00"), BigDecimal.ZERO)).contains(new BigDecimal("100.00"));
    }

    @Test
    @DisplayName("half an ngwee goes up, not to the nearest: K0.01 at 50% is K0.02")
    void halfAnNgweeGoesUp() {
        assertThat(PriceSuggestion.of(new BigDecimal("0.01"), new BigDecimal("50"))).contains(new BigDecimal("0.02"));
    }

    @Test
    @DisplayName("the answer always has two decimals, so it reads as money")
    void twoDecimals() {
        assertThat(PriceSuggestion.of(new BigDecimal("100"), new BigDecimal("10")).orElseThrow().scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("no landed cost, no suggestion: nothing is invented for a piece whose cost is not known")
    void noCostNoSuggestion() {
        assertThat(PriceSuggestion.of(null, new BigDecimal("40"))).isEqualTo(Optional.empty());
        assertThat(PriceSuggestion.of(new BigDecimal("100.00"), null)).isEqualTo(Optional.empty());
    }

    @Test
    @DisplayName("a percentage must be 0 to 1000 with at most two decimals, or it is refused")
    void percentRange() {
        assertThat(PriceSuggestion.of(new BigDecimal("100.00"), new BigDecimal("1000"))).contains(new BigDecimal("1100.00"));
        assertThatThrownBy(() -> PriceSuggestion.of(new BigDecimal("100.00"), new BigDecimal("-0.01"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PriceSuggestion.of(new BigDecimal("100.00"), new BigDecimal("1000.01"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PriceSuggestion.of(new BigDecimal("100.00"), new BigDecimal("12.345"))).isInstanceOf(IllegalArgumentException.class);
    }
}
