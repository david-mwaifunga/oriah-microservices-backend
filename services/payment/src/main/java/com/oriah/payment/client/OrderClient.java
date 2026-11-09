package com.oriah.payment.client;

import java.math.BigDecimal;
import java.util.UUID;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One of the caller's own orders, read so a payment intent can only be made
 * for an order the caller owns, for that order's total.
 *
 * <h2>Why payment asks</h2>
 *
 * An order may have only one live payment intent (pending, being charged or
 * paid), so whoever makes the first one holds the order's payment. Payment
 * used to take the order id on trust: any signed-in customer who learned an
 * order id could make an intent for it, and the owner's own payment was then
 * refused for as long as that intent stood.
 *
 * <h2>Owner-scoped by the token, not by a parameter</h2>
 *
 * {@code GET /api/v1/orders/{id}} answers only with the caller's own order:
 * for anybody else's it answers 404, exactly as for an order that does not
 * exist. This client sends the CALLER's token
 * ({@link com.oriah.payment.config.FeignTokenForwarder}, attached here only),
 * so a 404 means "not yours or not there" and payment cannot tell which --
 * exactly the right amount for it to know. Payment's own identity would be
 * the wrong one: order would answer for an account that owns no orders.
 *
 * <p>This is a different request from the one in which order asks payment
 * about an intent (when an order is paid), so the two services never wait on
 * each other within one request.
 *
 * <h2>No fallback</h2>
 *
 * A fallback would have to invent an answer to "is this order yours?", and the
 * only answer that keeps a shopper moving is the one that lets a stranger block
 * the owner's payment. A failed call throws; nothing is written.
 */
@FeignClient(name = "order", configuration = com.oriah.payment.config.FeignTokenForwarder.class)
public interface OrderClient {

    @GetMapping("/api/v1/orders/{id}")
    Order order(@PathVariable("id") UUID id);

    /**
     * Only what payment reads. Order sends much more (lines, dates, the
     * address); binding it would make this client break on a change to a
     * field payment does not use. {@code ignoreUnknown} is on the record so
     * that tolerance is part of this contract, not of mapper settings.
     *
     * @param status         where the order is in its lifecycle, as order
     *                       names it (OrderStatus); only {@value #AWAITING_PAYMENT}
     *                       can still be paid (see {@link #awaitsPayment()})
     * @param total          what the order is to be paid, in {@code currency}
     * @param shippingAmount the delivery part of the total; absent on an order
     *                       placed before order's V4, whose total left delivery
     *                       out (see {@link #totalIsPayable()})
     * @param discountAmount the discount part of the total; absent on those
     *                       same older orders
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Order(UUID id, String status, BigDecimal total, String currency, BigDecimal shippingAmount,
                 BigDecimal discountAmount) {

        /**
         * The one status of order's from which an order can become PAID
         * (order's OrderStatus: CREATED -> PAID or CANCELLED). Kept as text,
         * not an enum, so a status order adds later does not break reading
         * the order -- it is simply not payable until payment is taught it.
         */
        public static final String AWAITING_PAYMENT = "CREATED";

        /**
         * Whether the order is still waiting for its payment. Any other order
         * is cancelled, or paid and on its way: an intent for it could still
         * be confirmed and the card charged, and order's /pay would then
         * refuse that payment -- money taken that no order records.
         */
        public boolean awaitsPayment() {
            return AWAITING_PAYMENT.equals(status);
        }

        /**
         * Whether the order's total is the amount it is to be paid.
         *
         * <p>True for every order placed since order's V4: its total is
         * subtotal + delivery - discount, and the database holds it to that.
         * An older order recorded neither part (unknown, never zero), and its
         * total was its subtotal alone while checkout charged delivery on top,
         * so the real charge legitimately differs from it. Order's own
         * PaymentIntentVerifier does not hold such an order to its total, so
         * neither does payment -- refusing here would leave it unpayable.
         */
        public boolean totalIsPayable() {
            return shippingAmount != null || discountAmount != null;
        }
    }
}
