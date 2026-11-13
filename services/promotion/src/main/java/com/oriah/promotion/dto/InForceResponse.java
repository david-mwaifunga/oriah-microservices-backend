package com.oriah.promotion.dto;

import java.time.Instant;
import java.util.List;

import com.oriah.promotion.service.PromotionService.PromotionView;

/** The promotions in force at an instant: the instant asked about (now when none was given) and the definitions, oldest first. */
public record InForceResponse(Instant at, List<PromotionView> promotions) {
}
