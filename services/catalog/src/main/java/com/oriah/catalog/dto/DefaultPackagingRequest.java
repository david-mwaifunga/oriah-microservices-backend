package com.oriah.catalog.dto;

import java.util.UUID;

/**
 * Choose the box a product normally goes out in.
 *
 * @param packagingVariantId an active variant of a packaging entry (a box, a pouch, a ribbon); null clears the choice
 */
public record DefaultPackagingRequest(UUID packagingVariantId) {
}
