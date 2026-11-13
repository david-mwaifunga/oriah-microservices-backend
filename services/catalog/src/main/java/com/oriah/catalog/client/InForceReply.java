package com.oriah.catalog.client;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.oriah.catalog.promotion.PromotionDefinition;

/** What promotion's in-force read answers: the instant it was asked about and the promotions in force then. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InForceReply(Instant at, List<PromotionDefinition> promotions) {
}
