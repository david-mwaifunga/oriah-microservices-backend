package com.oriah.catalog.dto;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A category, optionally with its children, so one request can render a whole
 * navigation tree instead of one request per level.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CategoryResponse(
        UUID id,
        String slug,
        String name,
        String description,
        int position,
        List<CategoryResponse> children
) {
}
