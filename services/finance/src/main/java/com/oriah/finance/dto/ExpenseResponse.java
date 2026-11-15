package com.oriah.finance.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One expense as the books hold it. {@code origin} says where it came from: {@code KEYED} (typed in) or {@code POSTED} (the system posted it from another service's record).
 * {@code status}: {@code ACTIVE}, {@code REVERSED} (taken back by {@code reversedBy}) or {@code REVERSAL} (it is the taking-back; its amount is negative).
 */
@Schema(name = "Expense")
public record ExpenseResponse(
        UUID id, String category, String categoryLabel, BigDecimal amount, LocalDate expenseDate, String description, UUID linkedOrderId, UUID linkedConfirmationId, String provider,
        String reason, String kind, String origin, String status, UUID reversedBy, UUID correctionGroup, boolean inIssuedMonth, String createdBy, Instant createdAt, Instant recordedAt) {
}
