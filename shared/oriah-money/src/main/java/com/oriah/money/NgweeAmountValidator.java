package com.oriah.money;

import java.math.BigDecimal;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** {@link NgweeAmount}'s check: exactly {@link Ngwee#isWholeNgwee}. */
public class NgweeAmountValidator implements ConstraintValidator<NgweeAmount, BigDecimal> {

    @Override
    public boolean isValid(BigDecimal amount, ConstraintValidatorContext context) {
        return Ngwee.isWholeNgwee(amount);
    }
}