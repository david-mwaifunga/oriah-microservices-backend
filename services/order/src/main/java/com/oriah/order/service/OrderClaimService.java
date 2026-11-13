package com.oriah.order.service;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.oriah.order.exception.SalesProblem;
import com.oriah.order.exception.UpstreamRejectedException;
import com.oriah.order.exception.UpstreamUnavailableException;
import com.oriah.order.model.ClaimMethod;
import com.oriah.order.model.Order;
import com.oriah.order.model.PaymentReference;

import lombok.extern.slf4j.Slf4j;

/**
 * A shopper says they paid by mobile money or bank transfer (design FINAL B2). Deliberately NOT transactional: it asks inventory between two
 * short transactions ({@link OrderClaims}), and no database connection may be held across that call.
 *
 * <ol>
 *   <li>the claim is written first, the stretch of the hold marked pending;</li>
 *   <li>inventory is asked to stretch the hold (to a day);</li>
 *   <li>done: the order stores the new time and announces the claim. Inventory silent: the claim stands, the extension stays pending, and the
 *       hold job finishes it (the shopper is not told it failed: their word is recorded, and the time is the system's to secure). Inventory says
 *       the hold is gone: the order EXPIRES and the shopper is told so.</li>
 * </ol>
 */
@Service
@Slf4j
public class OrderClaimService {

    private final OrderClaims claims;
    private final OrderHolds holds;

    public OrderClaimService(OrderClaims claims, OrderHolds holds) {
        this.claims = claims;
        this.holds = holds;
    }

    /**
     * @param method    one of the methods that leave a reference (never cash)
     * @param reference as the shopper typed it; stored and sent in its one spelling
     * @param actor     {@code user:<keycloak subject>}
     * @throws IllegalArgumentException a method that is not claimable, or a reference that is not a reference (400)
     */
    public Order submit(String owner, UUID orderId, String method, String reference, String actor) {
        ClaimMethod claimed = ClaimMethod.parse(method);
        String normalised = PaymentReference.normalise(reference);
        OrderClaims.Claimed taken = claims.begin(owner, orderId, claimed, normalised);
        if (!taken.needsExtension()) {
            return taken.order();
        }
        try {
            return claims.extended(orderId, holds.extend(taken.order()), actor);
        } catch (OrderHolds.HoldGoneException gone) {
            claims.lapsed(orderId, actor);
            throw new SalesProblem(HttpStatus.CONFLICT, "hold-expired",
                    "the time this order held its pieces for has passed: place it again");
        } catch (UpstreamUnavailableException | UpstreamRejectedException notDone) {
            // The claim is recorded; the extension is pending and the hold job finishes it. Said here once, at WARN; the job says the rest.
            log.warn("Order {}'s hold could not be stretched just now ({}): the claim stands and the extension is pending", orderId, notDone.getMessage());
            return taken.order();
        }
    }
}
