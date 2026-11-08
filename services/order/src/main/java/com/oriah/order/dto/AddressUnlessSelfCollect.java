package com.oriah.order.dto;

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
 * A delivery address is needed unless the shopper collects
 * (MVP_PLAN Phase 0, item 2).
 *
 * <p>Checked on the whole request because it joins two fields, but reported on
 * {@code shippingAddressId}, so the caller's {@code fieldErrors} name the field
 * to fill in. A missing option is left to that field's own {@code @NotBlank}
 * rather than reported twice.
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = AddressUnlessSelfCollect.Validator.class)
public @interface AddressUnlessSelfCollect {

    String message() default "is required unless the delivery option is " + Quote.SELF_COLLECT;

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<AddressUnlessSelfCollect, PlaceOrderRequest> {

        @Override
        public boolean isValid(PlaceOrderRequest request, ConstraintValidatorContext context) {
            if (request == null || request.deliveryOption() == null
                    || Quote.SELF_COLLECT.equals(request.deliveryOption())
                    || request.shippingAddressId() != null) {
                return true;
            }
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
                    .addPropertyNode("shippingAddressId")
                    .addConstraintViolation();
            return false;
        }
    }
}
