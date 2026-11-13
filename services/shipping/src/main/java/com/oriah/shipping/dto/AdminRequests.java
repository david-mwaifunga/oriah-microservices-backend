package com.oriah.shipping.dto;

import java.math.BigDecimal;
import java.util.UUID;

import com.oriah.money.NgweeAmount;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * What admin sends to keep delivery prices (Phase 7). Every change that edits something that already exists says the version it was made
 * against, so two admins editing the same thing cannot overwrite each other. Money is a whole-ngwee amount; counts and versions are whole
 * numbers (a fraction where a whole number belongs is a 400, not rounded).
 */
public final class AdminRequests {

    private AdminRequests() {
    }

    public record AddTownRequest(
            @NotNull(message = "provinceId is required") UUID provinceId,
            @NotBlank(message = "name is required") @Size(max = 80, message = "name is at most 80 characters") String name) {
    }

    /** Switch a town or an option on or off. */
    public record SwitchRequest(
            @NotNull(message = "active is required") Boolean active,
            @NotNull(message = "version is required") Long version) {
    }

    public record AddOptionRequest(
            @NotBlank(message = "name is required") @Size(max = 80, message = "name is at most 80 characters") String name,
            @NotBlank(message = "kind is required") String kind) {
    }

    public record CreatePriceRequest(
            @NotNull(message = "townId is required") UUID townId,
            @NotBlank(message = "optionCode is required") String optionCode,
            @NotNull(message = "amount is required") @NgweeAmount BigDecimal amount,
            /** Oriah collects the fee with the order. Defaults to true. */
            Boolean allowsOriahCollects,
            /** The recipient may pay the courier on arrival (the order then carries K0). Defaults to false. */
            Boolean allowsRecipientPays,
            Integer etaDaysMin,
            Integer etaDaysMax) {
    }

    public record EditPriceRequest(
            @NotNull(message = "amount is required") @NgweeAmount BigDecimal amount,
            Boolean allowsOriahCollects,
            Boolean allowsRecipientPays,
            Integer etaDaysMin,
            Integer etaDaysMax,
            @NotNull(message = "version is required") Long version) {
    }

    public record PublishRequest(@NotNull(message = "version is required") Long version) {
    }

    /** The free-delivery threshold: an amount above zero, or null to switch it off. */
    public record ThresholdRequest(
            @NgweeAmount BigDecimal freeDeliveryThreshold,
            @NotNull(message = "version is required") Long version,
            @Size(max = 200, message = "reason is at most 200 characters") String reason) {
    }
}
