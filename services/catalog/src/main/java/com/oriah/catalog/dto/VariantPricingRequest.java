package com.oriah.catalog.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

/**
 * Ask the catalogue what a set of variants costs.
 *
 * <h2>Why this is a batch, and why it is a POST</h2>
 *
 * The caller is cart-service, resolving every line of a bag at once, plus
 * checkout resolving the same lines at preview and again at confirm. A
 * per-variant endpoint would make a ten-line bag ten calls on every read --
 * and would also be unable to tell cart the difference between "no such
 * variant" and "exists but is not published", because both are a 404.
 * {@link VariantPricingResponse} returns a status per variant instead.
 *
 * <p>POST rather than GET because the id list exceeds a sane URL length well
 * before the cap below. The operation is read-only and changes nothing; that
 * is stated on the operation so nobody mistakes the verb for a mutation.
 *
 * @param variantIds capped at 100. The cap is not arbitrary -- cart enforces
 *                   the same limit on lines per cart, so a bag that cannot be
 *                   priced in one request cannot exist. Letting an unbounded
 *                   list through would make one request able to scan the whole
 *                   variant table.
 */
public record VariantPricingRequest(
        @NotEmpty(message = "variantIds must not be empty")
        @Size(max = 100, message = "at most 100 variantIds per request")
        List<UUID> variantIds
) {
}
