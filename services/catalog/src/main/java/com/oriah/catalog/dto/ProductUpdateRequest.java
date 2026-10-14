package com.oriah.catalog.dto;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.constraints.Size;

/**
 * Partial-update payload for {@code PATCH /api/v1/admin/products/{id}}.
 *
 * <p>All fields are nullable. Semantics:
 * <ul>
 *   <li>{@code null} (or field absent in JSON) → no change to that field.</li>
 *   <li>Non-null value → replace the existing value.</li>
 * </ul>
 *
 * <p>Note that {@code @Size(min = 1)} only fires when the value is non-null,
 * so a missing title passes validation. A blank/empty title is rejected.
 * Clearing description or tags is not supported via PATCH in this cycle
 * (would require Merge Patch semantics with {@code JsonNullable<T>} or a
 * dedicated DELETE endpoint).
 */
public record ProductUpdateRequest(

        @Size(min = 1, max = 200, message = "title must be 1-200 characters")
        String title,

        @Size(max = 2000, message = "description must be at most 2000 characters")
        String description,

        List<String> tags,

        /**
         * Which kind of thing this is: {@code JEWELLERY} or {@code WATCH}.
         *
         * <p>Optional, and absent means JEWELLERY. The column was added with
         * {@code DEFAULT 'JEWELLERY'} and the default then dropped, so every
         * existing row is one -- requiring this now would break every caller
         * for no gain, and a shop that sells mostly jewellery should not have
         * to say so on every request.
         *
         * <p>Validated against {@link com.oriah.catalog.model.ProductTypeRegistry}
         * rather than bound as an enum: the column carries a foreign key to
         * {@code product_types}, so an unchecked value would reach Postgres and
         * surface as a constraint violation rendered 500, when it is plainly a
         * bad request.
         */
        String productType,

        /**
         * The type's specification -- a watch's movement and case diameter, a
         * ring's metal and purity.
         *
         * <p>Checked against the attributes the type DECLARES: required ones
         * must be present, enums must be one of their values, numbers are
         * coerced from the strings a form posts, and a key the type does not
         * declare is reported rather than dropped -- silently discarding a
         * field an operator filled in loses their work with no indication, and
         * usually means the wrong type was picked.
         *
         * <p>Not validated here with Bean Validation annotations, because what
         * is valid depends on {@code productType}: the rules live with the type
         * definitions, which is also where a new type brings its own.
         */
        Map<String, Object> attributes,

        /**
         * Which categories this product belongs to, replacing whatever it was
         * in. Null leaves them alone, so a title edit cannot silently
         * un-file a product.
         *
         * <p>An empty list is NOT null: it means "in no category", which is a
         * thing an operator may genuinely want.
         */
        List<UUID> categoryIds,

        /**
         * Which of them is the breadcrumb trail.
         *
         * <p>Must be one of {@code categoryIds} -- the domain refuses
         * otherwise, because a trail pointing somewhere the product does not
         * appear is worse than rendering no trail at all.
         */
        UUID primaryCategoryId
) {

    /**
     * A patch that touches only the descriptive fields.
     *
     * <p>Exists because this record keeps growing -- type, attributes,
     * categories, a primary -- and every addition broke every caller that
     * constructed it positionally while caring about none of them. A named
     * factory also says what the patch is FOR, where five trailing nulls say
     * only that somebody counted the arguments correctly.
     */
    public static ProductUpdateRequest describing(
            String title, String description, List<String> tags) {
        return new ProductUpdateRequest(title, description, tags, null, null, null, null);
    }
}
