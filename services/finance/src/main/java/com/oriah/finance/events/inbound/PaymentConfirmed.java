package com.oriah.finance.events.inbound;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * {@code payment.confirmed}, as finance reads it: which confirmation, for which order, how much, what fee payment charged, and the day the money ARRIVED. The reference, the
 * reason it was backdated and the staff member who keyed it are left unread on purpose: they are free text or personal, and finance books money, not people.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PaymentConfirmed(UUID confirmationId, UUID orderId, String method, BigDecimal gross, BigDecimal fee, BigDecimal net, String currency, Instant arrivedAt,
                               Instant confirmedAt, int attempt) {
}
