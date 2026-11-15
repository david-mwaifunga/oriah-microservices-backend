package com.oriah.finance.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One tax entry. {@code status}: {@code ACTIVE}, {@code REVERSED} (taken back by {@code reversedBy}) or {@code REVERSAL} (it is the taking-back: {@code amount} is the amount it
 * took back, positive).
 */
@Schema(name = "TaxEntry")
public record TaxEntryResponse(UUID id, String taxType, String taxTypeLabel, String forMonth, BigDecimal amount, LocalDate paidOn, String reference, String reason, String status,
                               UUID reversedBy, UUID reverses, UUID correctionGroup, boolean inIssuedMonth, String createdBy, Instant createdAt, Instant recordedAt) {
}
