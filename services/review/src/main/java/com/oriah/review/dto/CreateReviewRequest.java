package com.oriah.review.dto;

import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateReviewRequest(
        @NotNull UUID orderId,
        @NotNull @Min(1) @Max(5) Integer rating,
        @Size(max = 120) String title,
        @Size(max = 4000) String body
) {}
