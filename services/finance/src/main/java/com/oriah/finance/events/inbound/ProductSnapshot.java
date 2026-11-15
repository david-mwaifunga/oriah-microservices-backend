package com.oriah.finance.events.inbound;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** {@code product.created}, {@code product.updated} and {@code product.deleted}, as finance reads them: the product's id and, but for a deletion, its name and kind. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProductSnapshot(UUID id, String title, String kind) {
}
