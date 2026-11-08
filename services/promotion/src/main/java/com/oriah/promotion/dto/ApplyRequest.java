package com.oriah.promotion.dto;

import com.oriah.money.NgweeAmount;
import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

public record ApplyRequest(@NotNull @DecimalMin("0.01") @NgweeAmount BigDecimal subtotal) {}
