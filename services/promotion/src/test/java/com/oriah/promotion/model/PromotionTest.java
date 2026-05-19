package com.oriah.promotion.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PromotionTest {

    @Test
    @DisplayName("FIXED discount: $10 off a $100 cart")
    void fixedDiscount() {
        Promotion p = new Promotion("TEN_OFF", PromotionType.FIXED, new BigDecimal("10.00"), null, null, 0);
        assertThat(p.computeDiscount(new BigDecimal("100.00"))).isEqualByComparingTo("10.00");
    }

    @Test
    @DisplayName("FIXED amount larger than subtotal clamps to subtotal (never negative total)")
    void fixedDiscountClamps() {
        Promotion p = new Promotion("BIG", PromotionType.FIXED, new BigDecimal("500.00"), null, null, 0);
        assertThat(p.computeDiscount(new BigDecimal("50.00"))).isEqualByComparingTo("50.00");
    }

    @Test
    @DisplayName("PERCENTAGE 15% of $100 = $15.00")
    void percentageDiscount() {
        Promotion p = new Promotion("FIFTEEN", PromotionType.PERCENTAGE, new BigDecimal("15.00"), null, null, 0);
        assertThat(p.computeDiscount(new BigDecimal("100.00"))).isEqualByComparingTo("15.00");
    }

    @Test
    @DisplayName("constructor rejects amount <= 0")
    void rejectsZeroAmount() {
        assertThatThrownBy(() -> new Promotion("X", PromotionType.FIXED, BigDecimal.ZERO, null, null, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("constructor rejects PERCENTAGE > 100")
    void rejectsOver100Percent() {
        assertThatThrownBy(() -> new Promotion("X", PromotionType.PERCENTAGE, new BigDecimal("150"), null, null, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("isRedeemable enforces expiry, redemption cap, enabled")
    void redemptionGuards() {
        Promotion expired = new Promotion("EXP", PromotionType.FIXED, new BigDecimal("5"), null,
                Instant.now().minus(1, ChronoUnit.DAYS), 0);
        assertThat(expired.isRedeemable(Instant.now())).isFalse();

        Promotion p = new Promotion("OK", PromotionType.FIXED, new BigDecimal("5"), null, null, 1);
        assertThat(p.isRedeemable(Instant.now())).isTrue();
        p.redeem();
        assertThat(p.isRedeemable(Instant.now())).isFalse(); // exhausted

        Promotion off = new Promotion("OFF", PromotionType.FIXED, new BigDecimal("5"), null, null, 0);
        off.disable();
        assertThat(off.isRedeemable(Instant.now())).isFalse();
    }

    @Test
    @DisplayName("code is uppercased on construction")
    void codeIsUppercased() {
        assertThat(new Promotion("hello", PromotionType.FIXED, new BigDecimal("1"), null, null, 0).code())
                .isEqualTo("HELLO");
    }
}
