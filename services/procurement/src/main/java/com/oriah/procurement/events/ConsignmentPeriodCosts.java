package com.oriah.procurement.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * {@code consignment.period_costs} (Phase 10: finance's feed): the bills for the month that one allocation revision of a consignment changed. Aggregate
 * {@code consignment}, topic {@code oriah.procurement}, one per revision that changes them, occurred at the receipt (revision 1) or the correction.
 *
 * <p>A {@code PERIOD} cost is not part of what a piece cost: it reaches profit as an EXPENSE of a month. Revision 1 books each bill on the day it was incurred
 * ({@code incurredOn}, else the Lusaka day of the receipt). A correction (revision 2 and up) counts from the day it is made: it takes back the previous revision's
 * bills ({@code replaces}) and books this revision's ({@code costs}), both on the Lusaka day of {@code amendedAt}, so a month already reported keeps its figure.
 * Only the amounts and types decide whether anything changed: a correction of a bill's date alone books nothing. A bill of K0.00 is no bill and is never listed.
 * Nothing personal travels with it.
 *
 * <p>Consumers key a row by {@code (consignmentId, revision, position)}: the position is the cost line's place in that revision's list.
 *
 * @param revision   the allocation revision this describes: 1 at receipt
 * @param amendedAt  when it was corrected; null at receipt
 * @param reason     why it was corrected (the person's own words); null at receipt
 * @param costs      this revision's bills
 * @param replaces   the previous revision's bills, taken back; empty at receipt
 * @param recordedAt when the revision was recorded
 */
public record ConsignmentPeriodCosts(
        UUID consignmentId,
        String consignmentReference,
        int revision,
        String currency,
        Instant receivedAt,
        Instant amendedAt,
        String reason,
        List<Bill> costs,
        List<Bill> replaces,
        Instant recordedAt) {

    public static final String TYPE = "consignment.period_costs";
    public static final String AGGREGATE = "consignment";

    /** One bill for the month: what it was for, how much, and the day it counts on. */
    public record Bill(int position, String costType, BigDecimal amount, LocalDate effectiveOn) {
    }
}
