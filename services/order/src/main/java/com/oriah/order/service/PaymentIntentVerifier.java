package com.oriah.order.service;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import com.oriah.order.client.PaymentClient;
import com.oriah.order.model.Order;

import feign.FeignException;
import lombok.extern.slf4j.Slf4j;

/**
 * Establishes that a {@code paymentIntentId} a client handed us really is a
 * settled payment for <em>this</em> order, before the order is allowed to move
 * to PAID.
 *
 * <h2>The hole this closes</h2>
 *
 * {@code POST /orders/{id}/pay} verified that the order belonged to the caller
 * and then attached whatever UUID the body contained. Its own javadoc recorded
 * the gap -- "Caller responsibility: ensure the PaymentIntent is actually
 * SUCCEEDED before calling this" -- and that responsibility sat with an
 * unauthenticated-in-practice client, so a single request with a random UUID
 * produced a PAID order for free.
 *
 * <h2>Three conditions, one answer</h2>
 *
 * <ul>
 *   <li>the intent is SUCCEEDED -- a PENDING intent is a charge that has not
 *       happened, and a FAILED one is a charge that was declined;</li>
 *   <li>it names THIS order -- an intent carries the order it was created for,
 *       so this is what stops a shopper settling one cheap order and
 *       presenting that intent against every other order they own;</li>
 *   <li>the currency matches -- 250 of a weaker unit is not 250 of the order's
 *       currency, and comparing numbers alone would accept it.</li>
 * </ul>
 *
 * Ownership is not on that list because it is enforced upstream: the read is
 * made with the shopper's own forwarded token against payment's owner-scoped
 * endpoint, which answers 404 for another customer's intent.
 *
 * <h2>The AMOUNT must equal the order's total -- once that total is authoritative</h2>
 *
 * {@code intent.amount == order.total}, compared with {@code compareTo} so
 * {@code 250} and {@code 250.00} are one amount. A missing amount is a refusal,
 * never a match. Over and under are both refused: an underpayment is money
 * missing, and an overpayment is money taken that the order does not record.
 *
 * <p>This waited for V4. The order's total used to be its subtotal alone
 * ("no shipping/discount yet") while checkout charged delivery on top, so an
 * equality check would have refused real payments after the card was charged.
 * Without it a shopper could mint a 1.00 intent naming their own 250.00 order
 * and have it accepted. Order now prices delivery itself and records
 * {@code total = subtotal + shipping - discount}, marked
 * {@code totals_authoritative} -- and only a marked order is held to the
 * amount. An order placed before V4 keeps its old total, which left delivery
 * out, so the charge legitimately differs from it; it stays payable as before.
 * * <p>Every refusal raises the SAME message. Reporting which condition failed
 * would turn this into an oracle over other customers' payments -- "wrong
 * order" confirms an intent exists and is settled, while "not found" does not.
 * The amount and order are things the caller already knows about their own
 * order, so they lose nothing they are entitled to.
 *
 * <h2>Unreachable is not the same as refused</h2>
 *
 * A refusal means the claim was checked and is false (409). A failure to reach
 * payment means it was never checked (503). Collapsing the second into the
 * first would tell a shopper their good payment was invalid during an outage;
 * collapsing the first into the second would hide a fraud attempt as a glitch.
 * Both leave the order unpaid, which is the only safe direction: this fails
 * closed.
 */
@Component
@Slf4j
public class PaymentIntentVerifier {

    /**
     * One message for every refusal. See the class javadoc: a message that
     * named the failing condition would leak the existence and state of
     * payment intents.
     */
    static final String REFUSED =
            "The payment intent could not be verified as a settled payment for this order.";

    private final PaymentClient payment;

    public PaymentIntentVerifier(PaymentClient payment) {
        this.payment = payment;
    }

    /**
     * @throws IllegalStateException   the claim is false -- mapped to 409 by the
     *                                 shared advice
     * @throws ResponseStatusException 503, payment could not be consulted
     */
    public void verifySettledFor(Order order, UUID paymentIntentId) {
        PaymentClient.IntentResponse intent;
        try {
            intent = payment.findIntent(paymentIntentId);
        } catch (FeignException e) {
            // 4xx is payment answering, and the answer is no. 404 covers both
            // "no such intent" and "not this shopper's intent", which payment
            // keeps indistinguishable on purpose.
            if (e.status() >= 400 && e.status() < 500) {
                log.warn("Refusing order={} pay: payment answered {} for intent={}",
                        order.id(), e.status(), paymentIntentId);
                throw new IllegalStateException(REFUSED);
            }
            // 5xx, a connection failure, or a read timeout: never checked.
            log.error("Could not verify intent={} for order={} (payment status={})",
                    paymentIntentId, order.id(), e.status(), e);
            throw unavailable(e);
        } catch (RuntimeException e) {
            // Discovery failure, no instance available, decode error. Same
            // reasoning: unchecked, so unavailable rather than refused.
            log.error("Could not verify intent={} for order={}", paymentIntentId, order.id(), e);
            throw unavailable(e);
        }

        if (intent == null
                || intent.status() == null || !"SUCCEEDED".equals(intent.status())
                || intent.orderId() == null || !intent.orderId().equals(order.id())
                || intent.currency() == null || !intent.currency().equals(order.currency())
                || !amountMatches(order, intent)) {
            // Logged with the detail that the RESPONSE withholds, so an
            // operator can tell a fraud attempt from a mismatched total.
            log.warn("Refusing order={} pay: intent={} status={} orderId={} amount={} {} "
                            + "against order total={} {}",
                    order.id(), paymentIntentId,
                    intent == null ? null : intent.status(),
                    intent == null ? null : intent.orderId(),
                    intent == null ? null : intent.amount(),
                    intent == null ? null : intent.currency(),
                    order.total(), order.currency());
            throw new IllegalStateException(REFUSED);
        }

        log.info("Verified intent={} as a settled {} {} payment for order={}",
                paymentIntentId, intent.amount(), intent.currency(), order.id());
    }

    /**
     * Exactly the order's total, for an order whose total is authoritative.
     * An order placed before V4 is not held to it -- see the class javadoc.
     */
    private static boolean amountMatches(Order order, PaymentClient.IntentResponse intent) {
        if (!order.totalsAuthoritative()) {
            return true;
        }
        return intent.amount() != null && intent.amount().compareTo(order.total()) == 0;
    }

    /**
     * 503: payment could not be asked, so the claim was never checked.
     *
     * <p>The words say only what order knows. Checkout calls this endpoint
     * AFTER payment has taken the money, so "the order has not been charged"
     * would be untrue -- and this answer becomes the logged cause of
     * checkout's PAYMENT CAPTURED WITHOUT ORDER, read by whoever reconciles
     * that payment.
     *
     * <p>No {@code Retry-After}, on purpose (unlike order's 503s when it
     * cannot place an order). Checkout sends a 503 again, twice more, unless
     * it carries Retry-After, which tells it to stop and leave it for later.
     * Here the money is already taken, and there is no later: once checkout
     * gives up, a person has to reconcile the payment by hand. So this answer
     * must keep checkout trying (checkout's application.yaml, retry instance
     * {@code order-pay}). {@code PayVerificationTest} pins both.
     */
    private static ResponseStatusException unavailable(Exception cause) {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Payment could not be verified right now, so the order has not been marked paid; "
                        + "please retry.", cause);
    }
}
