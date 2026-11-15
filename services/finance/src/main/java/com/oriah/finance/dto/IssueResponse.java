package com.oriah.finance.dto;

import java.time.Instant;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/** A month as it was reported: the whole report and the table by product, stored, with who reported it, when, and the problems that were accepted. */
@Schema(name = "ReportIssue")
public record IssueResponse(int issueNo, String month, Instant asOf, Instant issuedAt, String issuedBy, String note, List<String> incompleteReasons, Snapshot snapshot) {

    /** What was reported. */
    @Schema(name = "ReportIssueSnapshot")
    public record Snapshot(ProfitReportResponse report, ProductReportResponse products) {
    }
}
