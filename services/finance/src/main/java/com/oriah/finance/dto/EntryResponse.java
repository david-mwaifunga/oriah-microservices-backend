package com.oriah.finance.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/** One row of the ledger behind a figure of the report. Amounts are in the account's natural direction; a reversal is negative. */
@Schema(name = "LedgerEntry")
public record EntryResponse(long entryId, String key, String account, String category, String reason, BigDecimal amount, LocalDate effectDay, Instant recordedAt, String source,
                            UUID productId, UUID orderId, UUID confirmationId, Long movementId, boolean estimated, String actor) {
}
