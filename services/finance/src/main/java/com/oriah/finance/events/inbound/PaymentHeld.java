package com.oriah.finance.events.inbound;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** {@code payment.held}, as finance reads it: money that could not pay a sale and is owed back, from the day it arrived (absent on an event written before the field). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PaymentHeld(UUID confirmationId, UUID orderId, String reason, BigDecimal owedGross, String currency, Instant heldAt, Instant arrivedAt) {
}
