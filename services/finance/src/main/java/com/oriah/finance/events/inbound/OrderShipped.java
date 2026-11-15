package com.oriah.finance.events.inbound;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * {@code order.shipped}, as finance reads it: which order, and the dispatch (when the parcel went, the fare Oriah paid the courier, who paid the courier). Present
 * {@code farePaid} means a fare was paid (0.00: the courier charged nothing); absent means none was due. Nothing else is read: no recipient, address or reference.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderShipped(UUID orderId, Dispatch dispatch) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Dispatch(Instant dispatchedAt, BigDecimal farePaid, String payer) {
    }
}
