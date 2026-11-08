package com.oriah.order.client;

import java.math.BigDecimal;
import java.util.UUID;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Reads a payment intent from payment-service so this service never has to
 * take the client's word that an order was paid.
 *
 * <h2>Why order-service calls payment at all</h2>
 *
 * {@code POST /orders/{id}/pay} takes a {@code paymentIntentId} in the request
 * body and used to attach it and move the order to PAID without checking
 * anything about it. The order's owner was verified; the intent never was. So
 * any authenticated shopper could post their own order id with a random UUID
 * and receive a PAID order, having paid nothing.
 *
 * <p>Locking the endpoint to the orchestrator was not available as a fix:
 * checkout-service reaches it through {@code FeignTokenForwarder}, which
 * forwards the <em>shopper's own</em> JWT, so an orchestrated call and a
 * hand-rolled one are the same subject with the same role. The only thing that
 * distinguishes them is whether the claim in the body is true, which is what
 * this client exists to establish.
 *
 * <h2>No fallback, deliberately</h2>
 *
 * There is no {@code fallback} and {@code spring.cloud.openfeign.circuitbreaker}
 * stays disabled, so a failed call throws instead of returning a default. A
 * fallback here would have to invent an answer to "was this paid?", and the
 * only available default that keeps the shopper moving is the wrong one. The
 * caller turns a thrown call into 503 and leaves the order unpaid.
 *
 * <p>The disabled circuit breaker also matters for the JWT: Spring Cloud's
 * OpenFeign/Resilience4j integration dispatches the call to a ForkJoinPool
 * worker, and {@code SecurityContextHolder} is a ThreadLocal, so the forwarded
 * Authorization header would be dropped and every call would 401. Checkout
 * documents the same trap in its own config.
 */
// The SHOPPER's token, forwarded: payment's owner check needs who is paying.
// Attached to this client alone (FeignTokenForwarder is not a global
// @Configuration any more), so shipping never receives it.
@FeignClient(name = "payment", configuration = com.oriah.order.config.FeignTokenForwarder.class)
public interface PaymentClient {

    /**
     * Payment's own owner-scoped read. It answers 404 both for an intent that
     * does not exist and for one belonging to somebody else, which is why
     * ownership needs no separate check here: the shopper's token is forwarded,
     * so payment answers as that shopper and an intent they do not own simply
     * is not found.
     */
    @GetMapping("/api/v1/payments/intents/{id}")
    IntentResponse findIntent(@PathVariable("id") UUID id);

    /**
     * Payment serves more fields than this ({@code failureReason},
     * {@code createdAt}, {@code confirmedAt}). Only the four that bear on
     * "is this a settled payment for this order" are bound.
     *
     * <p>{@code ignoreUnknown} is on the record rather than left to Spring
     * Boot's mapper configuration so tolerance is a property of the contract:
     * a service that sets
     * {@code spring.jackson.deserialization.fail-on-unknown-properties=true}
     * for its request bodies cannot turn an additive field in payment's
     * response into a failed verification here.
     *
     * <p>{@code status} is bound as a String, not as an enum this service
     * would have to keep in step with payment's. An unrecognised value then
     * fails the SUCCEEDED comparison and the payment is refused, which is the
     * safe direction; binding to an enum would throw on a value payment adds
     * later and produce a 503 for a payment that may be perfectly good.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record IntentResponse(UUID id, UUID orderId, BigDecimal amount, String currency, String status) {}
}
