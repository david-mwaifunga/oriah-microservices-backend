package com.oriah.shipping.dto;

import java.math.BigDecimal;
import java.util.UUID;

import com.oriah.money.NgweeAmount;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Ask for the delivery price of one town and option, before paying. {@code goodsTotal} is the goods AFTER discount (what the free-delivery
 * threshold is judged on). {@code payer} is {@code ORIAH_COLLECTS} or {@code RECIPIENT_PAYS}; absent takes the one the price offers.
 * Self-collect needs no town.
 */
public record QuoteRequest(
        UUID townId,
        @NotBlank(message = "optionCode is required") String optionCode,
        String payer,
        @NotNull(message = "goodsTotal is required") @NgweeAmount BigDecimal goodsTotal,
        String currency) {
}
