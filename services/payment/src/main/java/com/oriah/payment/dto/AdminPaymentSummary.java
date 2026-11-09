package com.oriah.payment.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Exact ledger totals for the admin dashboard.
 *
 * <h2>Why counts and sums are not treated the same way</h2>
 *
 * <p>A count is safe to read off a page: {@code Page.totalElements} is a COUNT
 * query and is exact however small the page is. A SUM is not. Adding up the
 * rows the browser happens to have produces a revenue figure that is correct
 * until there is more than one page and then silently under-reports money,
 * with nothing on screen to suggest it. So the aggregation happens in the
 * database and arrives here already finished.
 *
 * <h2>Why {@code captured} is a list</h2>
 *
 * <p>Every intent carries its own currency, and 100 USD plus 100 EUR is not
 * 200 of anything. Totals are therefore grouped by currency rather than
 * collapsed into one number that would be meaningless the first time a second
 * currency appeared. Today the list has a single USD entry.
 *
 * <p>Only captured money is summed. A failed or pending intent has an amount,
 * but it is an amount nobody has, and a dashboard that added it to revenue
 * would be reporting a wish.
 *
 * <h2>Why {@code chargingCount}</h2>
 *
 * <p>CHARGING is where an intent waits while the outcome of its charge is not
 * known yet: the gateway's answer was lost, and the reconciler has still to
 * settle it. The money may have been taken, so it is not revenue yet -- but
 * it must be in a column of its own, or the counts stop adding up to
 * {@code totalCount} and a payment that may have moved money is in none.
 */
public record AdminPaymentSummary(
        long totalCount,
        long capturedCount,
        long failedCount,
        long pendingCount,
        long chargingCount,
        List<CurrencyTotal> captured
) {
    /** Captured money in one currency. */
    public record CurrencyTotal(String currency, BigDecimal amount) {}
}
