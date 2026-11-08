package com.oriah.money;

import java.math.BigDecimal;
import java.util.Set;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.NotNull;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The API-edge half of the rule, exactly as a controller's {@code @Valid}
 * applies it to a request record: 0.001 is a validation error (400 with a
 * field error), never a value that reaches the database and is rounded there.
 */
class NgweeAmountTest {

    record PriceRequest(@NotNull @NgweeAmount BigDecimal unitPrice) {}

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void start() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void stop() {
        factory.close();
    }

    @Test
    @DisplayName("a tenth of a ngwee is one violation on that field, with the plain-language message")
    void refusesFractionsOfANgwee() {
        Set<ConstraintViolation<PriceRequest>> violations =
                validator.validate(new PriceRequest(new BigDecimal("0.001")));

        assertThat(violations).hasSize(1);
        ConstraintViolation<PriceRequest> v = violations.iterator().next();
        assertThat(v.getPropertyPath()).hasToString("unitPrice");
        assertThat(v.getMessage())
                .isEqualTo("must be an amount in whole ngwee: at most 2 decimal places and 10 whole digits");
    }

    @Test
    @DisplayName("eleven whole digits are refused")
    void refusesTooLarge() {
        assertThat(validator.validate(new PriceRequest(new BigDecimal("10000000000")))).hasSize(1);
    }

    @Test
    @DisplayName("whole ngwee pass")
    void acceptsWholeNgwee() {
        assertThat(validator.validate(new PriceRequest(new BigDecimal("1250.50")))).isEmpty();
        assertThat(validator.validate(new PriceRequest(new BigDecimal("9999999999.99")))).isEmpty();
        assertThat(validator.validate(new PriceRequest(new BigDecimal("1.500")))).isEmpty();
    }
}
