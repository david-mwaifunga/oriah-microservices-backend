package com.oriah.finance.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * Reporting a month ("I have reported this month"). {@code acknowledgeIncomplete} accepts that the books show a problem (an open error, a catch-up still running, a cost not
 * tied to a product) and reports the month anyway; the problems accepted are kept with the issue. A second issue of a month that has changed since the first needs a note.
 */
@Schema(name = "IssueRequest")
public record IssueRequest(
        @Schema(description = "Accept the problems the books show and report the month anyway", example = "false", types = {"boolean", "null"}) Boolean acknowledgeIncomplete,
        @Size(min = 5, max = 200, message = "note is 5 to 200 characters") @Schema(description = "Why, when this is a second issue of a month that has changed", types = {"string", "null"}) String note) {
}
