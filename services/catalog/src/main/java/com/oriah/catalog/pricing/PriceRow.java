package com.oriah.catalog.pricing;

import java.math.BigDecimal;
import java.time.Instant;

/** One row of a variant's price history, as the rules see it: when it takes effect, in what order it was written, and the price. */
public record PriceRow(long seq, Instant effectiveFrom, BigDecimal unitPrice) {
}
