package com.oriah.catalog.dto;

import java.util.UUID;

import jakarta.validation.constraints.Size;

/**
 * A partial edit to a category. Null means "leave alone".
 *
 * <p>Renaming deliberately does NOT re-derive the slug. The slug is in
 * storefront URLs and in whatever anyone has bookmarked, so changing the
 * display label is a copy edit while silently moving the URL is not. A
 * category that genuinely needs a new address is a new category.
 *
 * @param parentId moves the category. A move that would make a category its
 *                 own ancestor is refused by the domain -- the database CHECK
 *                 only catches a category parented directly to itself, so a
 *                 two-step cycle has to be caught above it.
 */
public record CategoryUpdateRequest(
        @Size(min = 1, max = 120, message = "name must be 1-120 characters")
        String name,

        @Size(max = 500, message = "description must be at most 500 characters")
        String description,

        UUID parentId,

        Integer position
) {}
