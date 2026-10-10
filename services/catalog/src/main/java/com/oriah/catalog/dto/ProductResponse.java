package com.oriah.catalog.dto;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

import com.oriah.catalog.model.Availability;

/**
 * Wire-format response for a single product. Carries only what we want to
 * expose publicly — fields the entity holds but the API shouldn't surface
 * (audit timestamps, soft-delete flags, internal flags) stay off this record.
 *
 * <p>Mapping lives in {@link com.oriah.catalog.mapper.ProductMapper}, not on
 * this record. The enriched overload takes a SERVICE-layer type, so having it
 * here made com.oriah.catalog.dto depend on com.oriah.catalog.service -- the
 * wire format depending on the orchestration layer, which is backwards for a
 * boundary type. ProductMapperTest asserts reflectively that no service type
 * reappears in this record's signatures.
 *
 * <p>{@code availability} is populated on the GET-by-id endpoint (where
 * we make the inventory call) and is {@code null} on list endpoints
 * (where calling inventory N times would be too expensive). Jackson
 * omits null fields via {@code @JsonInclude(NON_NULL)} so list responses
 * don't carry an empty key.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProductResponse(
        UUID id,
        String title,
        String description,
        List<String> tags,
        Availability availability
) {
}
