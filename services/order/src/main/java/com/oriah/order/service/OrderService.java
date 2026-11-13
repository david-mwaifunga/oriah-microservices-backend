package com.oriah.order.service;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.oriah.order.client.InventoryClient;
import com.oriah.order.dto.PlaceOrderRequest;
import com.oriah.order.events.OrderEventLog;
import com.oriah.order.exception.SalesProblem;
import com.oriah.order.model.Order;
import com.oriah.order.model.OrderStatus;
import com.oriah.order.repository.OrderRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Every write here names its {@code actor} -- {@code user:<keycloak subject>}
 * of whoever asked: the customer for place, claim and cancel, the member of
 * staff for an admin transition -- and records the change in the event log in
 * the same transaction ({@link OrderEventLog}). The actor is separate from the
 * order's owner because they are not always the same person: staff move a
 * customer's order, and will place phone orders on a customer's behalf.
 */
@Service
@Slf4j
public class OrderService {

    private final OrderRepository repository;
    // Records every status change made here; placement records its own, in
    // OrderWriter, with the persist it belongs to.
    private final OrderEventLog events;
    private final OrderPricingService pricing;
    private final OrderWriter writer;
    private final OrderHolds holds;
    private final AddressOwnership addresses;

    public OrderService(OrderRepository repository,
                        OrderEventLog events,
                        OrderPricingService pricing,
                        OrderWriter writer,
                        OrderHolds holds,
                        AddressOwnership addresses) {
        this.repository = repository;
        this.events = events;
        this.pricing = pricing;
        this.writer = writer;
        this.holds = holds;
        this.addresses = addresses;
    }

    /**
     * Places an order at CATALOG's prices, and takes a HOLD on its pieces.
     *
     * <h2>Deliberately NOT @Transactional</h2>
     *
     * It was, and it stored {@code req.items()} verbatim -- the hole this
     * closes. Pricing requires outbound calls (catalog, shipping) and so does
     * the hold (inventory), and this method must not hold a database
     * connection while they happen: holding one across a network call to
     * another service is how a slow dependency becomes an exhausted
     * connection pool.
     *
     * <p>So the work is split across two other beans and this method only
     * orders them. Both calls cross a Spring proxy, which is the point:
     * pricing and persisting as two methods on THIS bean would have been
     * self-invocation, the {@code @Transactional} would have done nothing, and
     * the only symptom would have been a transaction that silently was not
     * one.
     *
     * <h2>What the caller still controls, and what they do not</h2>
     *
     * They choose the variants and the quantities. They do not choose the
     * price, the title or the currency: those come from catalog. A price or
     * currency they STATE is checked against catalog's and refuses on
     * disagreement -- see {@link OrderPricingService} for why checking beats
     * ignoring -- so their figure can only ever cause a refusal, never a lower
     * charge.
     *
     * <p>The order's total is authoritative since V4 (subtotal + delivery -
     * discount), and confirmed money must equal exactly that total to pay the
     * sale ({@code SettlementService}).
     *
     * <h2>The address is the caller's, checked first</h2>
     *
     * The shopper chooses the address, but only from their own: customer is
     * asked, with their token, before anything is priced -- so an address that
     * is not theirs, or one nobody could check, costs no call to catalog or
     * shipping and saves nothing ({@link AddressOwnership}). Like pricing, it
     * is a network call, and it happens here, outside any transaction.
     *
     * @param actor who placed it, {@code user:<keycloak subject>}
     */
    public Placement placeOrder(String keycloakId, PlaceOrderRequest req, String idempotencyKey, String actor) {
        addresses.requireCallersOwn(req.shippingAddressId());
        OrderPricingService.PricedOrder priced = pricing.price(req);
        OrderWriter.Begun begun = writer.begin(keycloakId, req.shippingAddressId(), priced, idempotencyKey);
        if (!begun.needsHold()) {
            // The same placement was made before (the same key, or the same bag still waiting for payment): that order, not a second hold.
            return new Placement(begun.order(), false);
        }
        InventoryClient.Reservation reservation;
        try {
            reservation = holds.hold(begun.order());
        } catch (SalesProblem refused) {
            // Inventory said no (no stock, no box): the PENDING order was never announced, so it never existed.
            writer.discardPending(begun.order().id());
            throw refused;
        }
        // Any other failure (inventory silent, an error of ours) leaves the PENDING order for the shopper's repeat, or the hold job, to finish.
        return new Placement(writer.markHeld(begun.order().id(), reservation, actor), true);
    }

    /**
     * What a placement came to: the order, and whether THIS call made it (201) or the same placement had already been made (200).
     */
    public record Placement(Order order, boolean created) {
    }

    /**
     * The owner cancels their own order. Ownership is checked inside the same
     * transaction as the change, so what was checked is what is changed.
     *
     * @return the order, or empty when it does not exist or is not the owner's
     */
    @Transactional
    public Optional<Order> cancel(String owner, UUID orderId, String actor) {
        return repository.findById(orderId)
                .filter(o -> o.keycloakId().equals(owner))
                .map(o -> move(requireNotBeingSettled(o), OrderStatus.CANCELLED, actor));
    }

    /**
     * While confirmed money is being applied to an order, nothing else may change it: a cancel in the middle would race the sale. The money is
     * recorded and the sale settles or fails in moments; look again then.
     */
    private static Order requireNotBeingSettled(Order order) {
        if (order.settlingConfirmationId() != null) {
            throw new SalesProblem(HttpStatus.CONFLICT, "payment-being-applied",
                    "a confirmed payment is being applied to this order right now: look at it again in a moment");
        }
        return order;
    }

    /**
     * Drive the FSM. Caller-allowed transitions (USER): CREATED -> CANCELLED,
     * through {@link #cancel}. Admin-allowed transitions (ORDER_ADMIN):
     * everything else. Authorization is enforced at the controller layer via
     * {@code @PreAuthorize}.
     *
     * @param actor the member of staff, {@code user:<keycloak subject>}
     */
    @Transactional
    public Optional<Order> transition(UUID orderId, OrderStatus targetStatus, String actor) {
        // The order is looked up FIRST: one that does not exist is a 404 whatever is asked of it.
        return repository.findById(orderId).map(o -> {
            if (!OrderStatus.adminMayRequest(targetStatus)) {
                // Only money makes an order PAID (the settlement saga), only a lapsed hold or a rejected claim makes it EXPIRED, only the shopper's claim
                // makes it PAYMENT_SUBMITTED. A person asking for one of them would skip the money, the stock and the receipt.
                throw new IllegalStateException("Order " + o.id() + " cannot be moved to " + targetStatus + " by a person: "
                        + (targetStatus == OrderStatus.PAID ? "only a confirmed payment makes an order paid"
                        : "it is set by the shopper's claim, or by the hold lapsing or a rejected claim"));
            }
            return move(requireNotBeingSettled(o), targetStatus, actor);
        });
    }

    private Order move(Order order, OrderStatus target, String actor) {
        OrderStatus prev = order.status();
        order.transitionTo(target);
        events.moved(order, prev, actor);
        log.info("Order id={} {} -> {} by {}", order.id(), prev, target, actor);
        return order;
    }

    @Transactional(readOnly = true)
    public Optional<Order> findById(UUID id) {
        return repository.findById(id);
    }

    @Transactional(readOnly = true)
    public Page<Order> findMine(String keycloakId, Pageable pageable) {
        return repository.findByKeycloakIdOrderByPlacedAtDesc(keycloakId, pageable);
    }

    @Transactional(readOnly = true)
    public Page<Order> findAll(Pageable pageable) {
        return repository.findAll(pageable);
    }
}
