package com.oriah.catalog.dto;

import java.math.BigDecimal;

/** A suggested price: the landed cost (the figure the warning uses) plus a target profit percentage of it. Only ever a suggestion. */
public record SuggestionView(BigDecimal targetProfitPercent, BigDecimal landedCost, BigDecimal suggestedPrice) {
}
