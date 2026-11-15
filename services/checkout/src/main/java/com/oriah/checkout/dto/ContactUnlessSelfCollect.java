package com.oriah.checkout.dto;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.function.Consumer;

import com.oriah.quote.DeliveryContact;
import com.oriah.quote.Quote;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

/**
 * A delivery is handed to someone, so a delivered option needs the recipient's name and a phone number the driver can ring (Phase 8: nothing else in the
 * system holds either); and collecting delivers to nobody, so a self-collect request must carry neither. Each is judged by the one rule checkout and order
 * share ({@link DeliveryContact}) and reported on the field to fix, in that rule's own plain words. A blank value counts as none. A missing option is left to
 * that field's own validation.
 *
 * <p>Checkout holds the rule as well as order because a 4xx from order reaches the shopper as a server fault: the place to tell them what to fix is here.
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ContactUnlessSelfCollect.Validator.class)
public @interface ContactUnlessSelfCollect {

    String message() default "a delivery needs a recipient name and phone number";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<ContactUnlessSelfCollect, ConfirmRequest> {

        @Override
        public boolean isValid(ConfirmRequest request, ConstraintValidatorContext context) {
            if (request == null || request.deliveryOption() == null) {
                return true;
            }
            context.disableDefaultConstraintViolation();
            boolean[] valid = {true};
            Consumer<String[]> fail = field -> {
                context.buildConstraintViolationWithTemplate(field[1]).addPropertyNode(field[0]).addConstraintViolation();
                valid[0] = false;
            };
            if (Quote.SELF_COLLECT.equals(request.deliveryOption())) {
                if (!blank(request.recipientName())) {
                    fail.accept(new String[] {"recipientName", "must be absent when the delivery option is " + Quote.SELF_COLLECT + ": nobody is delivered to"});
                }
                if (!blank(request.recipientPhone())) {
                    fail.accept(new String[] {"recipientPhone", "must be absent when the delivery option is " + Quote.SELF_COLLECT + ": nobody is delivered to"});
                }
                return valid[0];
            }
            try {
                DeliveryContact.name(request.recipientName());
            } catch (IllegalArgumentException e) {
                fail.accept(new String[] {"recipientName", e.getMessage()});
            }
            try {
                DeliveryContact.phone(request.recipientPhone());
            } catch (IllegalArgumentException e) {
                fail.accept(new String[] {"recipientPhone", e.getMessage()});
            }
            return valid[0];
        }

        private static boolean blank(String value) {
            return value == null || value.isBlank();
        }
    }
}
