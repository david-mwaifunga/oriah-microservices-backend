package com.oriah.catalog.dto;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import com.oriah.catalog.model.ProductKind;

/**
 * Wire-format payload for creating a product. Kept separate from the
 * {@code Product} entity so the API contract can evolve independently of
 * the persistence model. Bean Validation annotations are enforced when
 * the controller method parameter is marked {@code @Valid}.
 *
 * @param title       required, 1-200 characters
 * @param description optional, max 2000 characters
 * @param tags        optional list of free-form tags (no per-tag size limit yet;
 *                    we triangulate that with a test when the wireframe demands it)
 */
public record ProductCreateRequest(


        @NotBlank(message = "title must not be blank")
        @Size(max = 200, message = "title must be at most 200 characters")
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
        UUID primaryCategoryId,

        /**
         * What this entry is for: {@code SELLABLE} (something Oriah sells) or
         * {@code PACKAGING} (a box, pouch or ribbon a sale goes out in).
         *
         * <p>Optional, and absent means SELLABLE, so every existing client keeps
         * working. Fixed for the life of the product. Packaging is stocked and
         * costed like anything else but is never published and never carries a
         * price: its variants are created at 0.00.
         *
         * <p>An enum, so a value that is not one of the two is a 400 from the
         * body parser rather than a constraint violation from the database.
         */
        ProductKind kind,

        /**
         * The axes this product's variants vary on, in display order: {@code
         * ["Ring Size"]}, or {@code ["Strap", "Case Size"]}. Absent or empty means
         * the product does not vary: one variant with no options.
         *
         * <p>Declared BEFORE the variants are added, because each variant's option
         * keys must match this set exactly -- which is what lets the storefront
         * build a size selector without guessing which keys a product uses. Until
         * this field existed nothing in the API could set the axes, so every
         * product held exactly one variant.
         */
        List<String> optionNames
) {}
