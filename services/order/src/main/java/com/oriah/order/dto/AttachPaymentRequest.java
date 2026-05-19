package com.oriah.order.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

public record AttachPaymentRequest(@NotNull UUID paymentIntentId) {}
