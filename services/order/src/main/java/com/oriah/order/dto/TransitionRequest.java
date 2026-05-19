package com.oriah.order.dto;

import com.oriah.order.model.OrderStatus;

import jakarta.validation.constraints.NotNull;

public record TransitionRequest(@NotNull OrderStatus targetStatus) {}
