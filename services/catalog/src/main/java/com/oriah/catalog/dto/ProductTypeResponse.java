package com.oriah.catalog.dto;

import java.util.List;

/**
 * A product type and the spec fields it declares.
 *
 * <p>This endpoint is what lets the PDP stop lying. Its spec table is
 * currently static markup asserting "Solid 18k gold" and "In our atelier" for
 * every product, so a watch page claims the watch is solid 18k gold. Given the
 * type's declared attributes, the PDP renders what the product actually has --
 * movement and water resistance for a watch, metal and carat weight for a
 * ring -- and the admin form renders the matching inputs from the same source.
 *
 * @param attributes in display order; both the form and the spec table render
 *                   them in the order given
 */
public record ProductTypeResponse(
        String code,
        String displayName,
        int position,
        List<AttributeDefinitionResponse> attributes
) {

    /**
     * @param kind          TEXT, NUMBER, ENUM or BOOLEAN -- tells the client
     *                      which control to render and how to format the value
     * @param unit          display suffix such as "mm"; null when unitless
     * @param allowedValues the closed set for ENUM; empty otherwise
     */
    public record AttributeDefinitionResponse(
            String key,
            String label,
            String kind,
            boolean required,
            String unit,
            List<String> allowedValues
    ) {
    }
}
