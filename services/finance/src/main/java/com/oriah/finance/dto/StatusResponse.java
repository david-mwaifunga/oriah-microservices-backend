package com.oriah.finance.dto;

import java.time.Instant;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Whether the books can be trusted yet: where each producer's catch-up (finance reading that service's whole history) stands, and how many errors and warnings are open. It
 * carries counts and ids only, never an amount.
 */
@Schema(name = "FinanceStatus")
public record StatusResponse(List<Producer> producers, boolean catchUpComplete, int openErrors, int openWarnings, long skippedFacts, long ledgerEntries) {

    /** One producer's catch-up. */
    @Schema(name = "CatchUpProducer")
    public record Producer(String producer, long replayedThrough, Long completedThrough, Instant completedAt, boolean complete) {
    }
}
