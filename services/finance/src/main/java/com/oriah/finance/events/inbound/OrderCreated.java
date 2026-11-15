package com.oriah.finance.events.inbound;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** {@code order.created}, as finance reads it: only that the order exists and how it was taken. The owner is never read. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderCreated(UUID orderId, String channel) {
}
