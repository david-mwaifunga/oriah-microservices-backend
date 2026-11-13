package com.oriah.catalog.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One row of a variant's price history.
 *
 * @param source     SET (a person set it) or MIGRATED (the variant was priced before history existed)
 * @param assessment what the below-cost rule found when it was saved: OK, BELOW, NO_COST_KNOWN, CURRENCY_MISMATCH, or NOT_ASSESSED
 *                   for a migrated row that was never compared with a cost
 * @param reason     why a price below cost was accepted, in the person's words
 * @param costBasis  what the person was shown when they saved it (the floor, the lots and their revisions, the default box, the
 *                   currencies, when it was read); absent on a migrated row
 */
public record PriceEntryResponse(
        UUID id,
        BigDecimal unitPrice,
        String currency,
        Instant effectiveFrom,
        Instant recordedAt,
        String setBy,
        String setByName,
        String source,
        String assessment,
        String reason,
        Object costBasis) {
}
