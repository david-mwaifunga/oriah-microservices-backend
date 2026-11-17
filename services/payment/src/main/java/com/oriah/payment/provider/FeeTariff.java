package com.oriah.payment.provider;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * What ZynlePay charges for collecting one payment: a percentage of the amount plus a fixed part. ZynlePay's answers carry no fee, so the fee on a
 * payment is worked out from this, to the ngwee, half up, and corrected against the monthly statement in finance.
 *
 * <p>A tariff is copied onto an attempt when it starts: a tariff changed afterwards never re-prices a payment already begun. One that cannot be
 * right (a negative part, more than 20%) is refused when it is read, so a typing mistake stops the service starting instead of being capped
 * quietly on somebody's payment.
 */
public record FeeTariff(BigDecimal percent, BigDecimal fixed) {

    private static final BigDecimal MOST = new BigDecimal("20");
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    public FeeTariff {
        if (percent == null || fixed == null) {
            throw new IllegalArgumentException("a tariff needs its percent and its fixed part (either may be 0)");
        }
        if (percent.signum() < 0 || percent.compareTo(MOST) > 0) {
            throw new IllegalArgumentException("a tariff's percent is 0 to 20, was " + percent.toPlainString());
        }
        if (fixed.signum() < 0) {
            throw new IllegalArgumentException("a tariff's fixed part cannot be negative, was " + fixed.toPlainString());
        }
        percent = percent.setScale(3, RoundingMode.UNNECESSARY);
        fixed = fixed.setScale(2, RoundingMode.UNNECESSARY);
    }

    /** The fee on {@code amount}: the percentage rounded half up to the ngwee, then the fixed part. */
    public BigDecimal fee(BigDecimal amount) {
        return amount.multiply(percent).divide(HUNDRED, 2, RoundingMode.HALF_UP).add(fixed);
    }

    /** Whether something is left of {@code amount} after its fee. A payment that would be all fee is not started. */
    public boolean fits(BigDecimal amount) {
        return fee(amount).compareTo(amount) < 0;
    }

    /** Which tariff a fee came from, in words the owner can hold against the agreement: {@code TARIFF 2.500% + 0.00}. */
    public String basis() {
        return "TARIFF " + percent.toPlainString() + "% + " + fixed.toPlainString();
    }
}
