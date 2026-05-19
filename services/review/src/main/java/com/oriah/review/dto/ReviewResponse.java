package com.oriah.review.dto;

import java.time.Instant;
import java.util.UUID;

import com.oriah.review.model.Review;

public record ReviewResponse(
        UUID id,
        UUID productId,
        String keycloakId,
        Integer rating,
        String title,
        String body,
        Integer helpfulCount,
        Instant createdAt
) {
    public static ReviewResponse from(Review r) {
        return new ReviewResponse(
                r.id(), r.productId(), r.keycloakId(),
                r.rating().intValue(), r.title(), r.body(),
                r.helpfulCount(), r.createdAt());
    }
}
