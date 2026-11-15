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
    public record Memo(BigDecimal deliveryGivenUp, BigDecimal heldMoneyAtMonthEnd, int lateKeyedSales, AgreedDeliveries agreedDeliveries, List<StatementLine> statementChecks, Overhead overhead) {
    }

    /** Deliveries agreed by phone against the shop's own quote at the time: what was conceded. {@code notRecorded} counts agreed deliveries whose quote was not kept. */
    @Schema(name = "ProfitReportAgreedDeliveries")
    public record AgreedDeliveries(int count, BigDecimal agreed, BigDecimal quoted, BigDecimal conceded, int notRecorded) {
    }

    /** A provider's own statement fees for the month against what finance recorded for that provider; the difference is exact (K0.00 is a match). */
    @Schema(name = "ProfitReportStatementLine")
    public record StatementLine(String method, BigDecimal statementFees, BigDecimal recordedFees, BigDecimal difference) {
    }

    /** The month's expenses over the pieces sold, for information beside a suggested price: nothing is spread across products. */
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
                         int openWarnings, boolean catchUpComplete, BigDecimal notTiedToProduct, boolean doNotRelyYet) {
    }
}
