package com.oriah.finance.events.inbound;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * {@code consignment.period_costs}, as finance reads it: the bills for the month one allocation revision of a consignment changed. The reason a bill was corrected is a
 * person's own words and is left unread on purpose.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PeriodCosts(UUID consignmentId, String consignmentReference, int revision, String currency, Instant receivedAt, Instant amendedAt, List<Bill> costs,
                          List<Bill> replaces, Instant recordedAt) {

    /** One bill: its place in that revision's cost lines, what it was for, how much and the day it counts on. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Bill(int position, String costType, BigDecimal amount, LocalDate effectiveOn) {
    }
}
