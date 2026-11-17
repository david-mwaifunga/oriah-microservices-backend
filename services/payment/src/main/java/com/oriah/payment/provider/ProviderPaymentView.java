package com.oriah.payment.provider;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * An attempt to pay through ZynlePay, as the shopper who started it sees it. It says what to do next and when to look again, and nothing a
 * shopper's page has no business holding: not the whole number, not a reference, not ZynlePay's ids, not the fee.
 *
 * @param nextStep            {@code APPROVE_ON_PHONE} (a prompt was sent to the phone), {@code OPEN_CARD_PAGE} (send the customer to
 *                            {@code redirectUrl}), {@code WAIT} (nothing to do but look again), {@code TRY_AGAIN} (it failed; a new attempt may be
 *                            started) or {@code DONE} (the money is recorded; the order will show as paid)
 * @param checkAgainInSeconds how long to wait before reading this again; absent once the attempt is over
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProviderPaymentView(UUID id, UUID orderId, String channel, String method, String status, String failureCode, BigDecimal amount, String currency,
                                  String phoneMasked, String redirectUrl, String nextStep, Integer checkAgainInSeconds, Instant startedAt,
                                  Instant verifiedAt, UUID confirmationId) {
}
