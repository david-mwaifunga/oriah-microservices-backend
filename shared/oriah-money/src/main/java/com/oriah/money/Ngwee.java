package com.oriah.money;

import java.math.BigDecimal;

/**
 * The money rule every Oriah service shares: an amount is a whole number of
 * ngwee (K0.01), and no larger than the NUMERIC(12,2) columns that store it.
 *
 * <p>Why refuse rather than round: Postgres would store 0.001 as 0.00
 * without a word, so a price, cost or fee typed one digit too long would
 * silently become a different amount -- and in a system whose purpose is
 * correct profit, an amount nobody chose. Refusing puts the mistake in front
 * of the person who made it.
 *
 * <p>The rule is about value, not notation: {@code 1.500} is K1.50 exactly
 * and passes. Bounds -- positive, non-negative -- are each field's own rule.
 *
 * <p>Two halves, one rule: {@link NgweeAmount} at the API edge (a 400 with a
 * field error) and {@link #require} in every domain constructor that takes
 * money (the aggregate defends itself, whoever calls it).
 */
public final class Ngwee {

    /** Whole digits a stored amount may have: NUMERIC(12,2) leaves 10. */
    public static final int WHOLE_DIGITS = 10;

    /** Decimal places a stored amount may have: one ngwee is K0.01. */
    public static final int DECIMAL_PLACES = 2;

    private Ngwee() {
    }

    /**
     * @param amount the amount to check
     * @param what   what it is, for the message ("unit price", "shipping")
     * @return {@code amount} itself, unchanged, so a constructor can assign
     *         the result directly
     * @throws IllegalArgumentException if it is null, finer than one ngwee, or
     *         has more than {@value #WHOLE_DIGITS} whole digits
     */
    // Messages print the amount with toString(), never toPlainString(): an
    // attacker-sized exponent (1E+2147483647) would make toPlainString build a
    // two-billion-digit string and run the JVM out of memory.
    public static BigDecimal require(BigDecimal amount, String what) {
        if (amount == null) {
            throw new IllegalArgumentException(what + " is required");
        }
        if (decimalPlaces(amount) > DECIMAL_PLACES) {
            throw new IllegalArgumentException(what + " must be in whole ngwee (at most "
                    + DECIMAL_PLACES + " decimal places), got " + amount);
        }
        if (wholeDigits(amount) > WHOLE_DIGITS) {
            throw new IllegalArgumentException(what + " is too large (at most " + WHOLE_DIGITS
                    + " whole digits), got " + amount);
        }
        return amount;
    }

    /**
     * The same rule as {@link #require}, as a yes/no for {@link NgweeAmount}'s
     * validator, so the API edge and the domain can never disagree about an
     * amount. Null is "not my question" (that is {@code @NotNull}'s).
     */
    public static boolean isWholeNgwee(BigDecimal amount) {
        return amount == null
                || (decimalPlaces(amount) <= DECIMAL_PLACES && wholeDigits(amount) <= WHOLE_DIGITS);
    }

    /** Decimal places that carry value: 1.500 has 1. */
    private static int decimalPlaces(BigDecimal amount) {
        return Math.max(0, amount.stripTrailingZeros().scale());
    }

    /**
     * In {@code long}: precision minus a scale near {@code Integer.MIN_VALUE}
     * (1E+2147483647 has scale -2147483647) overflows an {@code int} to a
     * negative number, which would read as "no whole digits" and pass.
     */
    private static long wholeDigits(BigDecimal amount) {
        BigDecimal value = amount.stripTrailingZeros();
        return Math.max(0L, (long) value.precision() - value.scale());
    }
}
