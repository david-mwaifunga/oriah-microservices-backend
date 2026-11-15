package com.oriah.order.service;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.oriah.order.client.CustomerClient;
import com.oriah.order.client.InventoryClient;
import com.oriah.quote.Quote;
import com.oriah.order.dto.PlaceOrderRequest;
import com.oriah.order.events.OrderEventLog;
import com.oriah.order.exception.SalesProblem;
import com.oriah.order.exception.UpstreamUnavailableException;
import com.oriah.order.model.HoldState;
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
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public OrderService(OrderRepository repository,
                        OrderEventLog events,
                        OrderPricingService pricing,
                        OrderWriter writer,
                        OrderHolds holds,
                        AddressOwnership addresses,
                        org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.repository = repository;
        this.events = events;
        this.pricing = pricing;
        this.writer = writer;
        this.holds = holds;
        this.addresses = addresses;
        this.jdbc = jdbc;
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
        CustomerClient.Address address = addresses.requireCallersOwn(req.shippingAddressId());
        OrderPricingService.PricedOrder priced = pricing.price(req);
        OrderWriter.Begun begun = writer.begin(keycloakId, req.shippingAddressId(), priced, idempotencyKey,
                Quote.SELF_COLLECT.equals(req.deliveryOption()) ? null : OrderWriter.DeliveryDetails.of(req.recipientName(), req.recipientPhone(), address));
        if (!begun.needsHold()) {
            // The same placement was made before (the same key, or the same bag still waiting for payment): that order, not a second hold.
            return new Placement(begun.order(), false);
        }
        return new Placement(finishPlacement(begun.order(), actor), true);
    }

    /**
     * Takes the hold of an order written PENDING and announces it: the second half of every placement, a shopper's and a recorded sale's alike (Phase 9
     * extracted it unchanged). Not transactional, like {@link #placeOrder}: inventory is asked outside any transaction.
     *
     * @param pending the order as {@link OrderWriter} wrote it (or a placement that was never finished, found again by its repeat)
     * @param actor   who placed it, {@code user:<keycloak subject>}
     * @return the order, HELD and announced
     * @throws SalesProblem inventory refused the hold (no stock, no box): the PENDING order is removed, it never existed
     */
    public Order finishPlacement(Order pending, String actor) {
        InventoryClient.Reservation reservation;
        try {
            reservation = holds.hold(pending);
        } catch (SalesProblem refused) {
            // Inventory said no (no stock, no box): the PENDING order was never announced, so it never existed.
            writer.discardPending(pending.id());
            throw refused;
        }
        // Any other failure (inventory silent, an error of ours) leaves the PENDING order for the repeat, or the hold job, to finish.
        try {
            return writer.markHeld(pending.id(), reservation, actor);
        } catch (OrderWriter.PlacementAbandonedException abandoned) {
            // The hold job gave this placement up while inventory was answering. Nothing is placed; the hold just taken is given back (the job's own
            // release may have come before it was taken), and the caller asks again.
            log.info("Placement of order {} was given up by the hold job while it was being finished: {}", pending.id(), abandoned.getMessage());
            try {
                holds.release(reservation.reservationId());
            } catch (RuntimeException ignored) {
                log.warn("The hold {} of a given-up placement could not be given back; it lapses by itself", reservation.reservationId());
            }
            throw new UpstreamUnavailableException("the order service");
        }
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
                .filter(o -> o.isOwnedBy(owner))
                .map(o -> move(requireNotBeingSettled(o), OrderStatus.CANCELLED, actor));
    }

    /**
     * Free delivery for one order (Phase 7, design 3.4): delivery becomes K0, the total falls by exactly what delivery would have cost, and who, why, when and
     * how much are kept. Only an order still waiting for a payment reference and whose pieces are held, with no money being applied. No event is appended:
     * the design lists none, and what changed is on the order itself ({@code order.paid} announces the sale at the total it was paid).
     *
     * @param actor {@code user:<keycloak subject>} of the member of staff holding DELIVERY_WAIVER
     * @throws com.oriah.observability.ResourceNotFoundException no such order (404)
     * @throws SalesProblem 409 {@code delivery-waiver-not-allowed}, {@code payment-being-applied}, {@code delivery-already-waived} or {@code no-delivery-to-waive}
     */
    @Transactional
    public Order waiveDelivery(UUID orderId, String reason, String actor) {
        Order order = repository.findById(orderId)
                .orElseThrow(() -> new com.oriah.observability.ResourceNotFoundException("Order", "id", orderId.toString()));
        try {
            order.waiveDelivery(actor, reason, java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        } catch (IllegalStateException refused) {
            throw switch (refused.getMessage()) {
                case "being-applied" -> new SalesProblem(HttpStatus.CONFLICT, "payment-being-applied",
                        "a confirmed payment is being applied to this order right now: look at it again in a moment");
                case "already-waived" -> new SalesProblem(HttpStatus.CONFLICT, "delivery-already-waived",
                        "this order's delivery was already made free: " + order.deliveryWaivedBy() + " did it");
                case "nothing-to-waive" -> new SalesProblem(HttpStatus.CONFLICT, "no-delivery-to-waive",
                        "this order has no delivery charge to waive (a pickup, a courier the recipient pays, or free already)");
                default -> new SalesProblem(HttpStatus.CONFLICT, "delivery-waiver-not-allowed",
                        "delivery can be waived only on an order still waiting for a payment reference whose pieces are held; this one is "
                                + order.status() + (order.holdState() == HoldState.PENDING ? " and still being placed" : ""));
            };
        }
        log.info("Order id={} delivery waived ({}) by {}: {}", order.id(), order.deliveryWaivedAmount(), actor, reason);
        return order;
    }

    /**
     * While confirmed money is being applied to an order, nothing else may change it: a cancel in the middle would race the sale. The money is
     * recorded and the sale settles or fails in moments; look again then.
     */
    private static Order requireNotBeingSettled(Order order) {
        if (order.holdState() == HoldState.PENDING) {
            // Written but not yet announced: its hold is still being taken, or given up by the hold job. Cancelling now would announce an order nobody was
            // told about and let the placement in flight take a hold no order owns.
            throw new SalesProblem(HttpStatus.CONFLICT, "placement-in-progress",
                    "this order is still being placed: look at it again in a moment");
        }
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
            if (OrderStatus.isDispatchStep(targetStatus)) {
                // Packing, sending and delivering keep the dispatch record the books and the refund line are read from (Phase 8): a person asking for the
                // status alone would skip the fare, the date and the record.
                throw new SalesProblem(HttpStatus.CONFLICT, "use-the-dispatch-steps", "an order is packed, shipped and delivered through its dispatch steps: "
                        + "POST /api/v1/admin/orders/" + o.id() + "/dispatch/pack, /ship or /deliver");
            }
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
        if (target == OrderStatus.CANCELLED) {
            // The status reaches the database first: a recorded (phone) order that delivers must keep its recipient and address until it is cancelled
            // (orders_phone_delivery_whole), so the nulling below may only run once the row says CANCELLED.
            repository.saveAndFlush(order);
            // An order that will never be sent keeps no recipient or address (the correction history, if any, is the audit). The entity never writes these
            // columns, so it is SQL, in this transaction; the entity forgets them too so the answer to the caller shows none.
            jdbc.update("""
                    UPDATE orders SET recipient_name = NULL, recipient_phone = NULL, address_line1 = NULL, address_line2 = NULL, address_city = NULL,
                        address_state = NULL, address_postal_code = NULL, address_country = NULL WHERE id = ?""", order.id());
            order.forgetDelivery();
        }
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
