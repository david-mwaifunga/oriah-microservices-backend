package com.oriah.payment.dto;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * What a caller may ask payment to collect.
 *
 * <p>The bounds here exist so the DATABASE never decides. {@code amount} is
 * {@code NUMERIC(12, 2) CHECK (amount > 0)}, and a request that exceeded the
 * column reached Postgres, violated it, and came back as
 * {@code 500 An unexpected error occurred} -- telling the caller nothing it
 * could act on and recording an ordinary bad request as a server fault.
 * Verified live before these annotations existed. A column is a backstop; if
 * it ever fires again, a 500 is then the honest answer.
 */
public record CreateIntentRequest(
        @NotNull UUID orderId,

        /**
         * Floor and ceiling both.
         *
         * <p>The ceiling is the column's real capacity rather than a round
         * number: {@code NUMERIC(12, 2)} holds ten integer digits and two
         * decimal places, so 9,999,999,999.99 is the largest value that fits
         * and {@code CreateIntentValidationTest.theCeilingIsInclusive} pins
         * the boundary itself. Changing the column means changing this, and
         * that test is what will say so.
         */
        @NotNull
        @DecimalMin("0.01")
        @DecimalMax("9999999999.99")
        BigDecimal amount,

        /**
         * Required, and exactly three upper-case letters.
         *
         * <p>It was {@code @Size(min = 3, max = 3)} and nothing else:
         * nullable, any case, any three characters. All three gaps were
         * verified against the running stack.
         *
         * <p>Omitted, it became {@code "USD"} -- a hardcoded default in a
         * platform priced in Kwacha, written in two places that had to agree.
         * It is required now instead, because nothing has to guess: checkout
         * always sends the bag's currency and every other construction site
         * passes one explicitly. A service that guesses which currency money
         * is in is worse than one that refuses to proceed.
         *
         * <p>Case matters more than it looks. order-service compares this
         * intent's currency to the order's with {@code equals}, so a
         * lower-case intent could not settle an upper-case order; and
         * {@code AdminPaymentSummary} groups captured totals by currency, so
         * {@code "zmw"} and {@code "ZMW"} split one figure into two rows that
         * each read as the whole.
         *
         * <p>Deliberately NOT checked against the ISO register. That would
         * couple payment to a list which changes without us, and the shape is
         * what stops the mismatches that actually bite.
         *
         * <p>{@code @Size} is kept alongside the pattern even though the
         * pattern already fixes the length, because the two annotations do not
         * publish the same thing. Dropping @Size replaced
         * {@code maxLength: 3} with {@code minLength: 1} in the OpenAPI
         * schema, so a generated client stopped knowing the field has a
         * maximum -- a looser contract published alongside a stricter server.
         * The OpenAPI diff gate caught it; it is the reason that gate exists.
         */
        @NotBlank
        @Size(min = 3, max = 3)
        @Pattern(regexp = "^[A-Z]{3}$",
                message = "must be a three-letter upper-case currency code")
        String currency
) {}
