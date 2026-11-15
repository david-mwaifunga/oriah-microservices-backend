package com.oriah.order.dto;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.oriah.quote.DeliveryContact;
import com.oriah.quote.Quote;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

/**
 * A delivery is handed to someone, so a delivered option needs the recipient's name and a phone number the driver can ring (Phase 8); a pickup delivers to
 * nobody, so it must carry neither. Each is judged by the rule checkout and order share ({@link DeliveryContact}) and reported on the field to fix. A blank
 * value counts as none. Checkout holds the same rule, because a 4xx from order reaches the shopper as a server fault.
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ContactUnlessSelfCollect.Validator.class)
public @interface ContactUnlessSelfCollect {

    String message() default "a delivery needs a recipient name and phone number";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<ContactUnlessSelfCollect, PlaceOrderRequest> {

        @Override
        public boolean isValid(PlaceOrderRequest request, ConstraintValidatorContext context) {
            if (request == null || request.deliveryOption() == null) {
                return true;
            }
            context.disableDefaultConstraintViolation();
            boolean valid = true;
            if (Quote.SELF_COLLECT.equals(request.deliveryOption())) {
                String why = "must be absent when the delivery option is " + Quote.SELF_COLLECT + ": nobody is delivered to";
                if (request.recipientName() != null && !request.recipientName().isBlank()) {
                    valid = fail(context, "recipientName", why);
                }
                if (request.recipientPhone() != null && !request.recipientPhone().isBlank()) {
                    valid = fail(context, "recipientPhone", why);
                }
                return valid;
            }
            try {
                DeliveryContact.name(request.recipientName());
            } catch (IllegalArgumentException e) {
                valid = fail(context, "recipientName", e.getMessage());
            }
            try {
                DeliveryContact.phone(request.recipientPhone());
            } catch (IllegalArgumentException e) {
                valid = fail(context, "recipientPhone", e.getMessage());
            }
            return valid;
        }

        private static boolean fail(ConstraintValidatorContext context, String field, String message) {
            context.buildConstraintViolationWithTemplate(message).addPropertyNode(field).addConstraintViolation();
            return false;
        }
    }
}
