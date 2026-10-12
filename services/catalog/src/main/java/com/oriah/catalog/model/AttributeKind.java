package com.oriah.catalog.model;

/**
 * How an {@link AttributeDefinition}'s value is validated and coerced.
 *
 * <p>These four cover both product types the catalogue has. The kind also
 * tells the admin form which control to render and the PDP how to format the
 * value, which is why it is a closed enum rather than a free-form type string.
 */
public enum AttributeKind {

    /** Free text, length-capped. "18k Yellow Gold". */
    TEXT,

    /**
     * Numeric, stored as a {@code BigDecimal}. Admin forms submit numbers as
     * strings, so a numeric string is coerced rather than rejected.
     */
    NUMBER,

    /** One of {@link AttributeDefinition#allowedValues}, matched exactly. */
    ENUM,

    /** True or false; a numeric-string or "true"/"false" is coerced. */
    BOOLEAN
}
