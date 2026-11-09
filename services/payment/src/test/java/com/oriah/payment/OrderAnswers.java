package com.oriah.payment;

import java.math.BigDecimal;
import java.util.UUID;

import com.oriah.payment.client.OrderClient;

/**
 * What order answers about one of the caller's own orders, for tests whose
 * subject is something else -- confirming, the one-live-intent rule, input
 * validation. Creating an intent asks order first, so those tests need order
 * to say "yes, yours, for this total".
 *
 * <p>Whether payment asks order at all, with whose token, and what it does
 * with every other answer, is {@code CreateIntentOwnershipTest}'s subject, and
 * there order is a real HTTP server.
 */
public final class OrderAnswers {

    private OrderAnswers() {
    }

    /**
     * The caller's order, placed since order's V4, so its total is the amount
     * it is to be paid: self-collect, no discount, still waiting for payment.
     */
    public static OrderClient.Order yours(UUID orderId, String total, String currency) {
        return new OrderClient.Order(orderId, OrderClient.Order.AWAITING_PAYMENT, new BigDecimal(total), currency,
                BigDecimal.ZERO, BigDecimal.ZERO);
    }
}
