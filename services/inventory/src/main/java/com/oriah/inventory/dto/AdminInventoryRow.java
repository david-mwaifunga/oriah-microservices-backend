package com.oriah.inventory.dto;

import java.util.UUID;

/**
 * One row of the admin inventory list.
 *
 * <p>Carries the product {@code title} as well as the numbers, because a page
 * of bare UUIDs is not something anyone can act on. The title comes from
 * {@code ProductRef}, which the Kafka consumer keeps in step with catalog, and
 * is <strong>nullable on purpose</strong>: a variant whose product event has
 * not arrived yet still has stock worth seeing, so it lists with a null title
 * rather than being hidden by an inner join.
 *
 * <p>{@code available} is {@code onHand - reserved - committed - quarantined}, computed rather than
 * stored. It is included so callers do not each re-derive it and disagree.
 */
public record AdminInventoryRow(
        UUID variantId,
        String title,
        int onHand,
        int reserved,
        int available,
        // Boxes set aside for products (packaging only; 0 for goods), and what is free to promise or reserve: the same figure as
        // `available`, under the name the box counts use. onHand == free + committed + reserved + quarantined.
        int committed,
        int free,
        // Returned pieces waiting to be looked at (Phase 12): in stock, not for sale, so not in `available` or `free`. 0 for boxes.
        int quarantined
) {}
