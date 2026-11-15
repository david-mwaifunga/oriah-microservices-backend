package com.oriah.finance.dto;

import java.time.Instant;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/** One time a month was reported, without the stored report itself. */
@Schema(name = "ReportIssueSummary")
public record IssueSummary(int issueNo, String month, Instant asOf, Instant issuedAt, String issuedBy, String note, List<String> incompleteReasons) {
}
