package com.oriah.money;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.CONSTRUCTOR;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.TYPE_USE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * The API-edge half of {@link Ngwee}: put it on every money field of a
 * request, beside that field's own bounds ({@code @NotNull},
 * {@code @DecimalMin}). A request carrying 0.001 is then a 400 with a field
 * error, instead of an amount Postgres rounds to 0.00 on the way in.
 *
 * <p>The limits are {@code @Digits(integer = 10, fraction = 2)}'s, applied by
 * <em>value</em> through {@link Ngwee#isWholeNgwee}: Hibernate's
 * {@code @Digits} counts decimals as written, so it would refuse 1.500 (K1.50
 * exactly) while {@link Ngwee#require} accepts it. One check, used at both the
 * edge and in the domain, cannot disagree with itself. A null passes;
 * whether a field is required is that field's {@code @NotNull}.
 *
 * <p>Each service's {@code MoneyEdgeGuard} test (this module's test-jar)
 * fails if a request DTO has a money field without it.
 */
@Documented
@Constraint(validatedBy = NgweeAmountValidator.class)
@Target({ FIELD, METHOD, PARAMETER, ANNOTATION_TYPE, TYPE_USE, CONSTRUCTOR })
@Retention(RUNTIME)
public @interface NgweeAmount {

    String message() default "must be an amount in whole ngwee: at most 2 decimal places and 10 whole digits";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
