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
 * <h2>The AMOUNT is deliberately not compared, and that is a known gap</h2>
 *
 * The obvious fourth condition -- {@code intent.amount == order.total} -- is
 * absent because it is currently false in the legitimate flow, and shipping it
 * would reject real checkouts after the card had already been charged.
 *
 * <p>CheckoutService charges {@code afterDiscount + shippingCost} while
 * {@code Order.recomputeTotals} sets {@code total = subtotal} and says so in a
 * comment: "no shipping/discount yet". The two therefore differ for any order
 * with a discount or a shipping cost. An equality check would turn those into
 * a 409 from this endpoint, which checkout converts into
 * {@code PaymentCapturedWithoutOrderException} -- money taken, order unpaid,
 * manual reconciliation. A tolerance band was considered and rejected: a fudge
 * factor on a money comparison is not a security control.
 *
 * <p>What remains open as a result: payment's {@code POST /intents} is a
 * ROLE_USER endpoint whose amount is {@code @DecimalMin("0.01")} and otherwise
 * unconstrained, so a shopper can mint a 0.01 intent naming their own order,
 * confirm it, and have it accepted here. That is strictly harder than the hole
 * being closed -- which needed one request and no payment at all -- but it is
 * the same money hole wearing a smaller number.
 *
 * <p>Closing it needs the order total to become authoritative, which means
 * pricing authority moving server-side: order cannot simply accept a total
 * from its caller, because a client-supplied total is the identical bug. That
 * is the pricing phase's job and it is already the top hazard on its map;
 * {@code PayVerificationTest.underpaymentIsAcceptedUntilTotalsAreAuthoritative}
 * characterises the gap so that closing it turns a test red here rather than
 * silently.
 *
 * <p>Every refusal raises the SAME message. Reporting which condition failed
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

        // No amount comparison -- see "The AMOUNT is deliberately not compared"
        // in the class javadoc. It is false in the legitimate flow today.
        if (intent == null
                || intent.status() == null || !"SUCCEEDED".equals(intent.status())
                || intent.orderId() == null || !intent.orderId().equals(order.id())
                || intent.currency() == null || !intent.currency().equals(order.currency())) {
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

    private static ResponseStatusException unavailable(Exception cause) {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Payment could not be verified right now. The order has not been charged "
                        + "or marked paid; please retry.", cause);
    }
}
