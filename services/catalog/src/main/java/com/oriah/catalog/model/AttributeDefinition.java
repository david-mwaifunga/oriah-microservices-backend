package com.oriah.catalog.model;

import java.util.List;

/**
 * One type-specific spec field: a watch's movement, a ring's metal purity.
 *
 * <p>These definitions are what let the PDP stop lying. The spec table it
 * renders today is entirely static markup claiming "Solid 18k gold" and "In
 * our atelier" for every product, so a watch page asserts it is solid 18k
 * gold. Driving that table off the product's own type instead means a watch
 * shows movement and water resistance because that is what a watch declares.
 *
 * @param key           stable identifier, also the JSONB key in
 *                      {@code products.attributes}
 * @param label         what the admin form and the PDP spec table display
 * @param kind          how the value is validated and coerced
 * @param required      whether a product of this type must carry it to publish
 * @param unit          display suffix such as "mm" or "m"; null when unitless
 * @param allowedValues the closed set for {@link AttributeKind#ENUM}; empty
 *                      for every other kind
 */
public record AttributeDefinition(
        String key,
        String label,
        AttributeKind kind,
        boolean required,
        String unit,
        List<String> allowedValues) {

    public AttributeDefinition {
        allowedValues = allowedValues == null ? List.of() : List.copyOf(allowedValues);
    }

    static AttributeDefinition text(String key, String label, boolean required) {
        return new AttributeDefinition(key, label, AttributeKind.TEXT, required, null, List.of());
    }

    static AttributeDefinition number(String key, String label, boolean required, String unit) {
        return new AttributeDefinition(key, label, AttributeKind.NUMBER, required, unit, List.of());
    }

    static AttributeDefinition enumerated(String key, String label, boolean required, String... values) {
        return new AttributeDefinition(key, label, AttributeKind.ENUM, required, null, List.of(values));
    }

    static AttributeDefinition flag(String key, String label) {
        return new AttributeDefinition(key, label, AttributeKind.BOOLEAN, false, null, List.of());
    }
}
