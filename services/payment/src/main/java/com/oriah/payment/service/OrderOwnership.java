package com.oriah.payment.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.oriah.observability.ResourceNotFoundException;
import com.oriah.payment.client.OrderClient;

import feign.FeignException;
import lombok.extern.slf4j.Slf4j;

/**
 * Whether an intent may be made for this order: it must be the caller's own,
 * and the intent must be for its total, in its currency. Asked of order, with
 * the caller's own token, before payment writes anything.
 *
 * <ul>
 *   <li>order answers 404 -- the order is not the caller's, or does not exist,
 *       and order deliberately does not say which -> 404 here too, in the
 *       shared not-found words, so payment adds no hint either;</li>
 *   <li>the order is no longer waiting for payment -- cancelled, or paid and
 *       beyond -> 409: an intent for it could still be confirmed and the
 *       card charged, and order would then refuse the payment;</li>
 *   <li>the amount or currency is not the order's total -> 400: an intent
 *       order would never accept as payment, which would block the order's
 *       real payment while it stood;</li>
 *   <li>order does not answer in time, cannot be reached, answers 5xx, or its
 *       answer breaks off on the way -> 503: nobody checked, nothing is
 *       written, and a retry is safe;</li>
 *   <li>order refuses payment's request (any other 4xx), redirects it, or
 *       answers with no order payment can read -> 500: our set-up is wrong,
 *       never the shopper's fault, and the same on every retry.</li>
 * </ul>
 */
@Service
@Slf4j
public class OrderOwnership {

    private final OrderClient orders;

    public OrderOwnership(OrderClient orders) {
        this.orders = orders;
    }

    /**
     * @throws ResourceNotFoundException     not the caller's order, or no such order -- 404
     * @throws IllegalStateException         the order is no longer waiting for payment -- 409
     * @throws IllegalArgumentException      not the order's total or currency -- 400
     * @throws OrderUnavailableException     order could not be asked -- 503
     * @throws OrderLookupRejectedException  order refused, redirected, or answered with no usable order -- 500
     */
    public void requireCallersOwn(UUID orderId, BigDecimal amount, String currency) {
        OrderClient.Order order;
        try {
            order = orders.order(orderId);
        } catch (FeignException e) {
            if (e.status() == 404) {
                log.info("Refusing intent: order {} is not the caller's, or does not exist", orderId);
                throw new ResourceNotFoundException("Order", "id", orderId.toString());
            }
            if (neverAnswered(e)) {
                log.warn("Could not check order {}: order status={}", orderId, e.status(), e);
                throw new OrderUnavailableException(e);
            }
            // order answered, in full, and the answer is no order payment can
            // use: a refusal (401, 403, any other 4xx), a redirect, or a 200
            // whose body is not an order. The same answer will come back on
            // every attempt, so it is never a 503.
            log.error("order answered payment's lookup of order {} with {} and no order payment can use",
                    orderId, e.status(), e);
            throw new OrderLookupRejectedException("order answered payment's lookup of order " + orderId
                    + " with " + e.status() + " and no order payment can use", e);
        }
        if (order == null || order.status() == null || order.total() == null || order.currency() == null) {
            throw new OrderLookupRejectedException("order answered with no status, total and currency for order "
                    + orderId, null);
        }

        // Before the amount: for an order that can no longer be paid at all,
        // whether the amount was right is beside the point.
        if (!order.awaitsPayment()) {
            log.info("Refusing intent for order {}: it is {}", orderId, order.status());
            throw new IllegalStateException("Order " + orderId + " is " + order.status()
                    + ", so it is no longer waiting for payment and cannot be paid.");
        }

        // compareTo, not equals: 4500 and 4500.00 are one amount. Over and
        // under are both refused -- too little is money missing, too much is
        // money taken that the order does not record.
        boolean sameCurrency = currency.equals(order.currency());
        boolean sameAmount = !order.totalIsPayable() || amount.compareTo(order.total()) == 0;
        if (!sameCurrency || !sameAmount) {
            log.info("Refusing intent for order {}: {} {} against the order's total {} {}",
                    orderId, amount, currency, order.total(), order.currency());
            throw new IllegalArgumentException("This payment is for " + amount.toPlainString() + " " + currency
                    + ", but the order's total is " + order.total().toPlainString() + " " + order.currency()
                    + ". A payment must be for exactly the order's total, in its currency.");
        }
    }

    /**
     * Whether order's answer never reached payment whole -- the only failures
     * worth a 503, because only they can come out differently next time:
     *
     * <ul>
     *   <li>no answer at all: refused, reset, timed out (Feign's status -1);</li>
     *   <li>order failing, or no instance of it registered (5xx);</li>
     *   <li>a 200 whose body broke off on the way. Feign reports that with the
     *       200 it had already read, and the network's IOException as the
     *       cause.</li>
     * </ul>
     *
     * A body that arrived whole but is not an order is NOT one of these:
     * Jackson's parse errors are not IOExceptions, so they fall to the 500.
     * {@code CreateIntentOwnershipTest} pins both sides.
     */
    private static boolean neverAnswered(FeignException e) {
        int status = e.status();
        if (status < 0 || status >= 500) {
            return true;
        }
        boolean succeeded = status >= 200 && status < 300;
        for (Throwable cause = e.getCause(); succeeded && cause != null; cause = cause.getCause()) {
            if (cause instanceof IOException) {
                return true;
            }
        }
        return false;
    }
}
