package com.oriah.catalog.model;

import java.util.List;
import java.util.Map;

/**
 * One or more type-specific attributes failed validation.
 *
 * <h2>Why it extends IllegalArgumentException</h2>
 *
 * The shared error contract already maps {@code IllegalArgumentException} to a
 * 400 problem+json body, so this renders as a Bad Request from the moment it
 * exists rather than as a 500 until a handler catches up. The structured
 * {@link #fieldErrors()} are carried now and rendered into the response's
 * {@code fieldErrors} property when the admin write endpoints land, which is
 * where a per-field highlight in a dynamically generated form is actually
 * consumed.
 *
 * <h2>Why every error, not the first</h2>
 *
 * A watch has five spec fields and the admin form renders them together.
 * Failing on the first bad one would make an operator fix and resubmit five
 * times to discover five problems. So validation collects.
 */
public class AttributeValidationException extends IllegalArgumentException {

    private final transient List<Map.Entry<String, String>> fieldErrors;

    public AttributeValidationException(List<Map.Entry<String, String>> fieldErrors) {
        super(summarise(fieldErrors));
        this.fieldErrors = List.copyOf(fieldErrors);
    }

    /** Field name to message, in declaration order. */
    public List<Map.Entry<String, String>> fieldErrors() {
        return fieldErrors;
    }

    private static String summarise(List<Map.Entry<String, String>> errors) {
        return errors.stream()
                .map(e -> e.getKey() + ": " + e.getValue())
                .reduce((a, b) -> a + "; " + b)
                .orElse("attribute validation failed");
    }
}
