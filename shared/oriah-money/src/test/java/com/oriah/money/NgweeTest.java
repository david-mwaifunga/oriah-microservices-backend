package com.oriah.money;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The domain half of the rule: a constructor taking money refuses anything
 * finer than one ngwee (K0.01) or larger than a NUMERIC(12,2) column holds,
 * rather than letting Postgres round it silently on the way in.
 */
class NgweeTest {

    @Test
    @DisplayName("a tenth of a ngwee is refused, never rounded")
    void refusesFractionsOfANgwee() {
        assertThatThrownBy(() -> Ngwee.require(new BigDecimal("0.001"), "unit price"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unit price")
                .hasMessageContaining("0.001");
        assertThatThrownBy(() -> Ngwee.require(new BigDecimal("125.505"), "shipping"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("whole ngwee pass, whatever their written scale")
    void acceptsWholeNgwee() {
        BigDecimal price = new BigDecimal("1250.50");
        assertThat(Ngwee.require(price, "price")).isSameAs(price);
        assertThat(Ngwee.require(new BigDecimal("1.5"), "price")).isEqualByComparingTo("1.50");
        assertThat(Ngwee.require(new BigDecimal("12"), "price")).isEqualByComparingTo("12.00");
        // Trailing zeros carry no ngwee: 1.500 is K1.50 exactly.
        assertThat(Ngwee.require(new BigDecimal("1.500"), "price")).isEqualByComparingTo("1.50");
        assertThat(Ngwee.require(BigDecimal.ZERO, "discount")).isZero();
        // Bounds (positive, non-negative) are each field's own rule, not this one.
        assertThat(Ngwee.require(new BigDecimal("-3.25"), "refund")).isEqualByComparingTo("-3.25");
    }

    @Test
    @DisplayName("the largest amount a NUMERIC(12,2) column holds passes; one more digit is refused")
    void refusesMoreThanTenWholeDigits() {
        assertThat(Ngwee.require(new BigDecimal("9999999999.99"), "total")).isNotNull();
        assertThatThrownBy(() -> Ngwee.require(new BigDecimal("10000000000.00"), "total"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("total");
    }

    @Test
    @DisplayName("an amount with an enormous exponent is refused, not waved through by an int overflow")
    void refusesAnAmountWhoseDigitCountWouldOverflow() {
        // 1E+2147483647 has precision 1 and scale -2147483647: precision - scale
        // in int arithmetic wraps to a negative number and reads as "0 digits".
        // A JSON body can carry it; it must be a refusal, not a pass.
        BigDecimal huge = new BigDecimal("1E+2147483647");
        assertThatThrownBy(() -> Ngwee.require(huge, "amount"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too large");
        assertThat(Ngwee.isWholeNgwee(huge)).isFalse();

        // The other end: a tiny exponent is refused as finer than a ngwee, and
        // its message must not try to write out two billion zeros either.
        BigDecimal tiny = new BigDecimal("1E-2147483647");
        assertThatThrownBy(() -> Ngwee.require(tiny, "amount"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("whole ngwee");
        assertThat(Ngwee.isWholeNgwee(tiny)).isFalse();
    }

    @Test
    @DisplayName("a missing amount is refused")
    void refusesNull() {
        assertThatThrownBy(() -> Ngwee.require(null, "amount"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("amount");
    }
}
