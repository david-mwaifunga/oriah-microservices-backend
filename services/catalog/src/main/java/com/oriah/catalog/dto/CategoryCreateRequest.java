package com.oriah.catalog.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * A new node in the storefront's taxonomy.
 *
 * <p>No slug. It is derived from the name, for the same reason a product's is:
 * a client-chosen slug is a client choosing a URL, and two of them collide in
 * a way only the unique index notices -- at which point the operator sees a
 * constraint violation rather than a sentence.
 *
 * @param parentId null for a top-level category. Validated against the table
 *                 rather than left to the foreign key, so an unknown id is a
 *                 400 naming the field instead of a constraint violation
 *                 rendered 500.
 */
public record CategoryCreateRequest(
        @NotBlank(message = "name must not be blank")
        @Size(max = 120, message = "name must be at most 120 characters")
        String name,

        @Size(max = 500, message = "description must be at most 500 characters")
        String description,

        UUID parentId
) {}
