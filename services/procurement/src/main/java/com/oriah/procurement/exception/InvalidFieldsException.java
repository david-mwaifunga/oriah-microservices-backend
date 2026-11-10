package com.oriah.procurement.exception;

import java.util.List;

import com.oriah.observability.OriahExceptionHandlerSupport.FieldErrorView;

/**
 * A request that breaks the rules on one or more fields, for the places where the
 * framework's own body validation cannot be used. Answered as the platform's 400
 * {@code validation} problem with a {@code fieldErrors} entry per field, the same
 * shape {@code @Valid} gives, so a form shows both the same way.
 *
 * <p>Needed by the edit (PATCH), where "is this field in the request" decides
 * whether it is checked at all, which an annotation on a field cannot know.
 */
public class InvalidFieldsException extends RuntimeException {

    private final transient List<FieldErrorView> fieldErrors;

    public InvalidFieldsException(List<FieldErrorView> fieldErrors) {
        super("Request validation failed");
        this.fieldErrors = List.copyOf(fieldErrors);
    }

    public List<FieldErrorView> fieldErrors() {
        return fieldErrors;
    }
}
