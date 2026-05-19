package com.oriah.payment.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Caller submits the (test) card number; FakePaymentGateway routes by
 * the well-known Stripe-style numbers. No CVV/expiry — pretend the
 * client UI tokenized those into the test number.
 */
public record ConfirmIntentRequest(@NotBlank String cardNumber) {}
