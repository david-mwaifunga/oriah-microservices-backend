package com.oriah.catalog.product;

import java.util.List;

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

        List<String> tags
) {}
