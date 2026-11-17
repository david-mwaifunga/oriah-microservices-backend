package com.oriah.order.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

/**
 * Payment asks the order to take an attempt to collect its total through ZynlePay.
 *
 * @param providerPaymentId the attempt, as payment knows it
 * @param supersedes        an earlier attempt that is finished in payment's own records, so the order may let it go for this one before the event
 *                          saying so has arrived; absent otherwise
 */
public record ProviderPaymentHoldRequest(@NotNull UUID providerPaymentId, UUID supersedes) {
}
