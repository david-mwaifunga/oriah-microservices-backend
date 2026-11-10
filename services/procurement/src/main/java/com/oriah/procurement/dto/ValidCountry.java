package com.oriah.procurement.dto;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * An ISO 3166-1 alpha-2 country code such as ZM, CN or AE (case does not matter;
 * it is stored in upper case). Blank and null are valid <em>here</em>: whether a
 * country is required is {@code @NotBlank}'s job, so one mistake gives one field
 * error, not two.
 *
 * <p>Exists as a constraint, rather than leaving the rule to the aggregate alone,
 * so that a wrong country is a {@code fieldErrors} entry beside the other fields'
 * problems and a form can highlight it, instead of a lone 400 with a sentence.
 * The aggregate enforces the same rule, so it holds however a supplier is made.
 */
@Documented
@Constraint(validatedBy = CountryCodeValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidCountry {

    String message() default "must be an ISO 3166-1 alpha-2 country code such as ZM, CN or AE";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
