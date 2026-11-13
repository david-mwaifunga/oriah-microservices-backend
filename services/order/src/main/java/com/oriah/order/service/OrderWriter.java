package com.oriah.order.service;

import java.time.Instant;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.oriah.order.client.InventoryClient;
import com.oriah.order.events.OrderEventLog;
import com.oriah.order.exception.SalesProblem;
import com.oriah.order.model.HoldState;
import com.oriah.order.model.Order;
import com.oriah.order.model.OrderStatus;
import com.oriah.order.repository.OrderRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * The database halves of a placement, and nothing else: each is one short, local transaction (no HTTP in here, ever).
 *
 * <h2>Why this is its own bean</h2>
 *
 * Pricing calls catalog and shipping, and the hold calls inventory; none may hold a database connection while it waits --
 * {@code OrderService}'s own rule. A non-transactional method calling a {@code @Transactional} one on the SAME bean is self-invocation, which
 * bypasses the proxy, so the transactional steps live here and {@code OrderService.placeOrder} crosses the proxy for each.
 * {@code PlaceOrderPricingTest.pricingHappensOutsideTheTransaction} and {@code PlaceOrderSaleTest} assert the boundary from the inside.
 *
 * <h2>The order is written BEFORE inventory is asked</h2>
 *
 * {@link #begin} writes the order PENDING (no event: nobody has been told). If inventory then answers, {@link #markHeld} makes it HELD and
 * announces {@code order.created}; if inventory refuses, {@link #discardPending} deletes it; if inventory does not answer, the row stays and the
 * shopper's repeat finishes it with the SAME reservation id, which inventory answers once. Writing first is what stops a hold nobody owns:
 * a crash after inventory holds can no longer strand pieces for an order that was never written.
 *
 * <h2>Once, however often it is asked</h2>
 *
 * A shopper's Idempotency-Key makes a placement happen once; so does the quote's fingerprint while the order is live (a reloaded page sends a
 * new key with the same bag and gets the same order back). The same key with ANOTHER quote is refused: a key is not a way to change an order.
 * Both are looked up, and the caps counted, under one advisory lock per shopper, so two placements racing cannot both pass.
 *
 * <h2>The caps</h2>
 *
 * A shopper may hold at most {@code oriah.sales.max-live-orders} orders and {@code oriah.sales.max-held-pieces} pieces at once (owner question
 * Q12): a hold is stock nobody else can buy, and a hold costs the shopper nothing, so it must be bounded.
 */
@Service
@Slf4j
public class OrderWriter {

    /** Statuses in which an order is still waiting for money, and so still holds its pieces. */
    private static final EnumSet<OrderStatus> LIVE = EnumSet.of(OrderStatus.CREATED, OrderStatus.PAYMENT_SUBMITTED);

    private final OrderRepository repository;
    private final OrderEventLog events;
    private final JdbcTemplate jdbc;
    private final int maxLiveOrders;
    private final int maxHeldPieces;

    public OrderWriter(OrderRepository repository, OrderEventLog events, JdbcTemplate jdbc,
                       @Value("${oriah.sales.max-live-orders:3}") int maxLiveOrders,
                       @Value("${oriah.sales.max-held-pieces:20}") int maxHeldPieces) {
        this.repository = repository;
        this.events = events;
        this.jdbc = jdbc;
        this.maxLiveOrders = maxLiveOrders;
        this.maxHeldPieces = maxHeldPieces;
    }

    /** What {@link #begin} did: an order to finish placing (hold its pieces), or one that already stands and is simply answered. */
    public record Begun(Order order, boolean needsHold) {
    }

    /**
     * @param priced the lines as CATALOG prices them, less the promotion's discount per piece, and the delivery as SHIPPING priced it -- never
     *               as the caller stated them. The currency is catalog's too.
     * @param idempotencyKey the caller's key for this placement, or null
     */
    @Transactional
    public Begun begin(String keycloakId, UUID shippingAddressId, OrderPricingService.PricedOrder priced, String idempotencyKey) {
        // One lock per shopper, held to the commit: the lookups below and the caps read what a racing placement is about to write. Its key is
        // its own name, distinct from the outbox's (which the event log takes last).
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> {
        }, "order-sale:" + keycloakId);

        String digest = priced.quoteDigest();
        if (idempotencyKey != null) {
            Order keyed = repository.findByKeycloakIdAndIdempotencyKey(keycloakId, idempotencyKey).orElse(null);
            if (keyed != null) {
                if (!digest.equals(keyed.quoteDigest())) {
                    throw new SalesProblem(HttpStatus.CONFLICT, "idempotency-key-reused",
                            "this Idempotency-Key was used for a different order: use a new key for a new order");
                }
                return standing(keyed);
            }
        }
        Order live = repository.findFirstByKeycloakIdAndQuoteDigestAndStatusIn(keycloakId, digest, LIVE).orElse(null);
        if (live != null) {
            return standing(live);
        }

        enforceLimits(keycloakId, priced.pieces());

        Order order = new Order(keycloakId, shippingAddressId, priced.currency(), priced.deliveryOption(), priced.shippingAmount(),
                priced.deliveryTownId(), priced.payer());
        priced.lines().forEach(line -> order.addItem(line.variantId(), line.productId(), line.title(), line.quantity(),
                line.unitPrice(), line.discountPerPiece(), line.promotionId()));
        order.startHold(digest, idempotencyKey);
        Order saved = repository.saveAndFlush(order);
        log.info("Placed order id={} keycloakId={} subtotal={} total={} {} items={} (PENDING: inventory not yet asked)",
                saved.id(), keycloakId, saved.subtotal(), saved.total(), saved.currency(), saved.items().size());
        return new Begun(saved, true);
    }

    /** An order that already stands: held (just answer it), or still PENDING (a placement that was never finished: finish it). */
    private static Begun standing(Order existing) {
        return new Begun(existing, existing.holdState() == HoldState.PENDING);
    }

    private void enforceLimits(String keycloakId, int newPieces) {
        Map<String, Object> held = jdbc.queryForMap("""
                SELECT count(*) AS orders, coalesce(sum(q.pieces), 0) AS pieces
                  FROM orders o
                  LEFT JOIN (SELECT order_id, sum(quantity) AS pieces FROM order_items GROUP BY order_id) q ON q.order_id = o.id
                 WHERE o.keycloak_id = ? AND (o.status IN ('CREATED', 'PAYMENT_SUBMITTED') OR o.settling_confirmation_id IS NOT NULL)""", keycloakId);
        long orders = ((Number) held.get("orders")).longValue();
        long pieces = ((Number) held.get("pieces")).longValue();
        if (orders >= maxLiveOrders) {
            throw new SalesProblem(HttpStatus.CONFLICT, "hold-limit-reached",
                    "you already hold " + orders + " orders (" + pieces + " pieces) waiting for payment, and at most " + maxLiveOrders
                            + " may be held at once: pay for one, or let one lapse, first")
                    .with("heldOrders", orders).with("heldPieces", pieces).with("maxLiveOrders", maxLiveOrders);
        }
        if (pieces + newPieces > maxHeldPieces) {
            throw new SalesProblem(HttpStatus.CONFLICT, "hold-limit-reached",
                    "you already hold " + pieces + " pieces waiting for payment, and at most " + maxHeldPieces
                            + " may be held at once: this order would make " + (pieces + newPieces))
                    .with("heldOrders", orders).with("heldPieces", pieces).with("maxHeldPieces", maxHeldPieces);
        }
    }

    /**
     * Inventory holds the pieces: the order is HELD until the time inventory named, and {@code order.created} is appended -- ONE transaction, so
     * the order is never announced without its hold, nor held without being announced.
     *
     * @param actor who placed it, {@code user:<keycloak subject>}
     */
    @Transactional
    public Order markHeld(UUID orderId, InventoryClient.Reservation reservation, String actor) {
        Order order = repository.findById(orderId).orElseThrow(() -> new IllegalStateException("order " + orderId + " vanished before its hold was recorded"));
        if (order.holdState() != HoldState.PENDING) {
            // A repeat of the same placement finished it first. Nothing more to announce.
            return order;
        }
        Map<UUID, UUID> boxes = new HashMap<>();
        if (reservation.lines() != null) {
            reservation.lines().stream().filter(l -> "BOX".equals(l.kind())).forEach(l -> boxes.put(l.saleLineId(), l.variantId()));
        }
        Instant heldAt = reservation.heldAt() != null ? reservation.heldAt() : Instant.now();
        order.markHeld(heldAt, reservation.expiresAt(), boxes);
        events.placed(order, actor);
        log.info("Order id={} is HELD until {} (reservation {})", order.id(), order.holdExpiresAt(), order.reservationId());
        return order;
    }

    /** Inventory refused the hold: the PENDING order was never announced, so it simply never existed. Deleted, and only while still PENDING. */
    @Transactional
    public void discardPending(UUID orderId) {
        repository.findById(orderId).filter(o -> o.holdState() == HoldState.PENDING).ifPresent(o -> {
            repository.delete(o);
            log.info("Order id={} was refused its hold and removed (never announced)", orderId);
        });
    }
}
