package com.oriah.finance.events.inbound;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * {@code payment.voided}, as finance reads it: the confirmation taken back, what had become of its money (PENDING, or HELD), when, and (from the field's introduction) the day the
 * money arrived. The reference, the reason and the person are left unread on purpose.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PaymentVoided(UUID confirmationId, UUID orderId, String method, BigDecimal gross, BigDecimal fee, BigDecimal net, String currency, String outcome,
                            Instant voidedAt, Instant arrivedAt) {
}
