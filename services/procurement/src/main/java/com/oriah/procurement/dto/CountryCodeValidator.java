package com.oriah.procurement.dto;

import com.oriah.procurement.model.Supplier;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** Delegates to the aggregate's own list of countries, so there is one list. */
public class CountryCodeValidator implements ConstraintValidator<ValidCountry, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return value == null || value.isBlank() || Supplier.isCountryCode(value);
    }
}
