package com.oriah.order.service;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.oriah.order.client.CustomerClient;
import com.oriah.order.exception.UpstreamRejectedException;
import com.oriah.order.exception.UpstreamUnavailableException;

import feign.FeignException;
import lombok.extern.slf4j.Slf4j;

/**
 * Whether the address an order names is one of the caller's own, asked of
 * customer by order itself. Checkout asks the same question, but a shopper can
 * call order directly, so order cannot rely on it having been asked.
 *
 * <ul>
 *   <li>the id is not in the caller's list -> 400, in checkout's own words: the
 *       shopper chose an address that is not theirs, and can choose again;</li>
 *   <li>customer does not answer, or answers 5xx -> 503: nothing is placed,
 *       and a retry is safe;</li>
 *   <li>customer refuses our request (4xx), or answers with no list at all ->
 *       500: our bug or bad data, never the shopper's 400, and never a 503
 *       that invites a retry which cannot succeed.</li>
 * </ul>
 *
 * <h2>Why a 4xx from customer is ours, not the shopper's</h2>
 *
 * Order sends customer the very token it has just accepted for this request,
 * to an endpoint that answers 200 -- an empty list -- even for a shopper with
 * no customer profile yet. So a 401, 403 or 404 there means the two services
 * disagree about tokens, roles or paths: a fault in how the shop is set up.
 * Answering "that address is not yours" would blame the shopper for it, and
 * a 503 would tell them to keep retrying something that will fail every time.
 * Order already treats a refusal from catalog or shipping this way
 * ({@link ShippingQuotes}), and checkout treats a refusal from customer the
 * same.
 *
 * <p>Whichever way it fails, the order is not placed. An address nobody could
 * check is never stored.
 */
@Service
@Slf4j
public class AddressOwnership {

    /** checkout's message for the same refusal, word for word (CheckoutService.resolveDestination). */
    static final String NOT_YOURS = "That shipping address is not one of yours.";

    private final CustomerClient customer;

    public AddressOwnership(CustomerClient customer) {
        this.customer = customer;
    }

    /**
     * @param addressId the address the order names; {@code null} -- a
     *                  self-collect order with nowhere to deliver -- asks
     *                  nothing of customer
     * @return the caller's address as customer answered it (its lines are copied onto a delivered order); null when none was named
     * @throws IllegalArgumentException     it is not one of the caller's -- 400
     * @throws UpstreamUnavailableException customer could not be asked -- 503
     * @throws UpstreamRejectedException    customer refused or answered nothing -- 500
     */
    public CustomerClient.Address requireCallersOwn(UUID addressId) {
        if (addressId == null) {
            return null;
        }
        List<CustomerClient.Address> theirs;
        try {
            theirs = customer.myAddresses();
        } catch (FeignException e) {
            if (e.status() >= 400 && e.status() < 500) {
                log.error("customer REFUSED order's address lookup with {}", e.status(), e);
                throw new UpstreamRejectedException("customer refused order's address lookup with " + e.status());
            }
            log.warn("Could not check addressId={}: customer status={}", addressId, e.status());
            throw new UpstreamUnavailableException("customer");
        }
        if (theirs == null) {
            throw new UpstreamRejectedException("customer answered with no address list");
        }
        return theirs.stream().filter(a -> addressId.equals(a.id())).findFirst().orElseThrow(() -> {
            log.warn("Refusing order: addressId={} is not one of the caller's", addressId);
            return new IllegalArgumentException(NOT_YOURS);
        });
    }
}
