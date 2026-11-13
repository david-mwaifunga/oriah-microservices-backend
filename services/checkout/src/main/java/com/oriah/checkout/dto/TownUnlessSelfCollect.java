package com.oriah.checkout.dto;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.oriah.quote.Quote;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

/**
 * Delivery is priced per TOWN (Phase 7), so a delivered quote must name its town; and collecting needs no town and no courier, so a self-collect request
 * must name neither. Checked on the whole request because it joins fields, and reported on the field to fix ({@code deliveryTownId} or {@code payer}). A
 * missing option is left to that field's own validation (required on confirm, optional on preview). Order holds the same rule on its own request.
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = TownUnlessSelfCollect.Validator.class)
public @interface TownUnlessSelfCollect {

    String message() default "the delivery town is required unless the delivery option is " + Quote.SELF_COLLECT;

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<TownUnlessSelfCollect, DeliveryChoice> {

        @Override
        public boolean isValid(DeliveryChoice choice, ConstraintValidatorContext context) {
            if (choice == null || choice.deliveryOption() == null) {
                return true;
            }
            context.disableDefaultConstraintViolation();
            boolean valid = true;
            if (Quote.SELF_COLLECT.equals(choice.deliveryOption())) {
                if (choice.deliveryTownId() != null) {
                    valid = fail(context, "deliveryTownId", "must be absent when the delivery option is " + Quote.SELF_COLLECT);
                }
                if (choice.payer() != null) {
                    valid = fail(context, "payer", "must be absent when the delivery option is " + Quote.SELF_COLLECT);
                }
            } else if (choice.deliveryTownId() == null) {
                valid = fail(context, "deliveryTownId", "is required unless the delivery option is " + Quote.SELF_COLLECT);
            }
            return valid;
        }

        private static boolean fail(ConstraintValidatorContext context, String field, String message) {
            context.buildConstraintViolationWithTemplate(message).addPropertyNode(field).addConstraintViolation();
            return false;
        }
    }
}
