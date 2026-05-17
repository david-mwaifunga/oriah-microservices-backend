package com.oriah.catalog.product;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Wire-format payload for creating a product. Kept separate from the
 * {@link Product} entity so the API contract can evolve independently of
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

        List<String> tags
) {}
