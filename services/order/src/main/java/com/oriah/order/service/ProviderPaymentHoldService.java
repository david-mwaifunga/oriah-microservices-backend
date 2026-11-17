package com.oriah.order.service;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.oriah.order.exception.SalesProblem;
import com.oriah.order.exception.UpstreamRejectedException;
import com.oriah.order.exception.UpstreamUnavailableException;
import com.oriah.order.model.Order;

import lombok.extern.slf4j.Slf4j;

/**
 * Payment asks the order to take a payment through ZynlePay (Phase 13): one short transaction that takes the attempt, the call to inventory that
 * holds the pieces long enough for the customer to approve (with no transaction open), and one short transaction that records it.
 *
 * <p><strong>The answer is 200 only once the pieces are really held for it.</strong> Payment sends the request to ZynlePay on that answer; money
 * must not be asked for while the pieces could lapse under it. If inventory does not answer, the order keeps the attempt and says 503: the same
 * attempt asked again tries the stretch again, and the hold job finishes it meanwhile.
 */
@Service
@Slf4j
public class ProviderPaymentHoldService {

    private final ProviderPaymentHolds attempts;
    private final OrderHolds holds;
    private final Clock clock;
    private final Duration hold;

    public ProviderPaymentHoldService(ProviderPaymentHolds attempts, OrderHolds holds, Clock clock,
                                      @Value("${oriah.sales.provider-payment-hold:PT45M}") Duration hold) {
        this.attempts = attempts;
        this.holds = holds;
        this.clock = clock;
        this.hold = hold;
    }

    /**
     * @throws SalesProblem                 409, as {@link ProviderPaymentHolds#begin}, and {@code hold-expired} when the hold is gone at inventory
     * @throws UpstreamUnavailableException 503: inventory did not answer; the attempt is kept and the same call may be made again
     */
    public Order take(String owner, UUID orderId, UUID attemptId, UUID supersedes, String actor) {
        ProviderPaymentHolds.Taken taken = attempts.begin(owner, orderId, attemptId, supersedes);
        if (!taken.needsExtension()) {
            return taken.order();
        }
        try {
            return attempts.extended(orderId, attemptId, holds.extend(taken.order(), clock.instant().plus(hold)))
                    .orElseThrow(ProviderPaymentHolds::tooShort);
        } catch (OrderHolds.HoldGoneException gone) {
            attempts.lapsed(orderId, actor);
            throw new SalesProblem(HttpStatus.CONFLICT, "hold-expired", "the time this order held its pieces for has passed: place it again");
        } catch (UpstreamRejectedException notDone) {
            log.warn("Order {}'s hold could not be stretched for provider payment {} ({}): the attempt is kept, the extension is pending", orderId, attemptId,
                    notDone.getMessage());
            throw new UpstreamUnavailableException("inventory");
        }
    }

    /** The time a hold is stretched to for a payment through ZynlePay, from now: also what the hold job stretches a pending one to. */
    public java.time.Instant until() {
        return clock.instant().plus(hold);
    }
}
