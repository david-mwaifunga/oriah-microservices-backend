package com.oriah.finance.dto;

import java.math.BigDecimal;
import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;

/** One entry of a provider's statement fees. */
@Schema(name = "StatementCheck")
public record StatementCheckResponse(Long id, String month, String method, BigDecimal statementFees, String note, String enteredBy, Instant enteredAt) {
}
