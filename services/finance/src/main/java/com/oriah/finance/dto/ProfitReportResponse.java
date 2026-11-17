package com.oriah.finance.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One month's profit report, in the client's own words, as finance knew the month at {@code asOf}. Every amount is Zambian Kwacha. The thirteen {@code lines} are the report;
 * what sits under a line (the write-offs by reason, the expenses by heading with how much was posted by the system and how much typed in) is beside it; the {@code memo} holds
 * what is shown but never counted (money owed to customers, delivery given away, the provider statements); {@code status} says whether the month can be relied on.
 */
@Schema(name = "ProfitReport")
public record ProfitReportResponse(String month, Instant asOf, String currency, List<Line> lines, List<WrittenOff> writtenOff, List<ExpenseLine> expenses, Memo memo, Disclosure disclosure,
                                   Status status) {

    /** One numbered line of the report. {@code key} is the stable name; {@code label} the words the owner reads. */
    @Schema(name = "ProfitReportLine")
    public record Line(int number, String key, String label, BigDecimal amount) {
    }

    @Schema(name = "ProfitReportWrittenOff")
    public record WrittenOff(String reason, BigDecimal amount) {
    }

    /** An expense heading: what the system posted from other services' records, what was typed in, and both together. */
    @Schema(name = "ProfitReportExpense")
    public record ExpenseLine(String category, String label, BigDecimal posted, BigDecimal typedIn, BigDecimal total) {
    }

    @Schema(name = "ProfitReportMemo")
    public record Memo(BigDecimal deliveryGivenUp, BigDecimal heldMoneyAtMonthEnd, int lateKeyedSales, AgreedDeliveries agreedDeliveries, List<StatementLine> statementChecks, Overhead overhead,
                       Refunds refunds, int lateKeyedSalesSinceCancelled) {
    }

    /**
     * Cancellations of paid orders and returns counted in the month (Phase 12), in plain figures. What was taken back is already inside lines 1, 2 and 8 (each as a
     * negative on the day of the correction); it is repeated here so a month that reads low, or below nothing, says why. {@code ofWhichFromEarlierMonths} is the part
     * of it (goods less discounts plus delivery) that belongs to sales of earlier months. {@code owedAtMonthEnd} is what customers were still owed when the month
     * closed. {@code sendChargesPaid} is what providers charged Oriah to send money back, and {@code recoveredFromCustomers} the part of it customers bore.
     */
    @Schema(name = "ProfitReportRefunds")
    public record Refunds(BigDecimal goodsTakenBackAtList, BigDecimal discountsTakenBack, BigDecimal deliveryRefunded, BigDecimal ofWhichFromEarlierMonths, BigDecimal owedAtMonthEnd,
                          BigDecimal sendChargesPaid, BigDecimal recoveredFromCustomers, BigDecimal heldMoneyRepaid) {
    }

    /** Deliveries agreed by phone against the shop's own quote at the time: what was conceded. {@code notRecorded} counts agreed deliveries whose quote was not kept. */
    @Schema(name = "ProfitReportAgreedDeliveries")
    public record AgreedDeliveries(int count, BigDecimal agreed, BigDecimal quoted, BigDecimal conceded, int notRecorded, int sinceCancelled, BigDecimal sinceCancelledAgreed) {
    }

    /** A provider's own statement fees for the month against what finance recorded for that provider; the difference is exact (K0.00 is a match). */
    @Schema(name = "ProfitReportStatementLine")
    public record StatementLine(String method, BigDecimal statementFees, BigDecimal recordedFees, BigDecimal difference) {
    }

    /**
     * The month's expenses over the pieces sold, for information beside a suggested price: nothing is spread across products. {@code piecesSold} is the pieces sold in
     * the month less the pieces taken back in it (a cancellation, a return).
     */
    @Schema(name = "ProfitReportOverhead")
    public record Overhead(BigDecimal expenses, long piecesSold, BigDecimal perPiece) {
    }

    /** How much of the month's cost comes from estimated opening-stock costs, so the early months read as approximate. */
    @Schema(name = "ProfitReportDisclosure")
    public record Disclosure(boolean approximate, BigDecimal estimatedCost, BigDecimal costThisMonth, BigDecimal estimatedShare, int estimatedSaleLines, BigDecimal estimatedValueStillHeld,
                             String banner, List<EstimatedLine> standing) {
    }

    @Schema(name = "ProfitReportEstimatedLine")
    public record EstimatedLine(UUID consignmentLineId, String consignmentReference, String sku, int quantity, BigDecimal landedTotal) {
    }

    @Schema(name = "ProfitReportStatus")
    public record Status(boolean issued, Integer lastIssueNo, Instant lastIssuedAt, boolean changedSinceIssue, long changedEntries, BigDecimal changedEffectOnProfit, int openErrors,
                         int openWarnings, boolean catchUpComplete, BigDecimal notTiedToProduct, boolean doNotRelyYet, int refundsOwedWithoutDebt) {
    }
}
