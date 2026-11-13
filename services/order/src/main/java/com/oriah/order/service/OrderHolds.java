package com.oriah.order.service;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.oriah.order.client.InventoryClient;
import com.oriah.order.exception.SalesProblem;
import com.oriah.order.exception.UpstreamRejectedException;
import com.oriah.order.exception.UpstreamUnavailableException;
import com.oriah.order.model.Order;

import feign.FeignException;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.json.JsonMapper;

/**
 * Asks inventory to hold an order's pieces, and turns what it says into what the placement does next. Deliberately NOT transactional: it is
 * an outbound call, and {@code OrderService} states the rule on why none may hold a database connection while it waits.
 *
 * <ul>
 *   <li>held: the reservation, for {@link OrderWriter#markHeld};</li>
 *   <li>a refusal (409 {@code insufficient-stock} naming every short line, {@code no-default-box}, ...): a {@link SalesProblem} that passes
 *       inventory's own name and properties on, so the storefront sees what inventory said; the caller deletes the never-announced PENDING row;</li>
 *   <li>inventory does not answer (5xx, a timeout, nothing listening), or is mid-way through this very hold (409 {@code reservation-in-progress}):
 *       503, and the PENDING row stays, so the repeat finishes it under the SAME reservation id;</li>
 *   <li>inventory rejects what order sent (other 4xx): our bug, 500.</li>
 * </ul>
 */
@Service
@Slf4j
public class OrderHolds {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PROBLEM_PREFIX = "https://oriah.dev/problems/";

    private final InventoryClient inventory;
    private final java.time.Clock clock;
    /** The longest a hold is stretched to (a day): what a payment claim asks for; inventory caps it again from when the hold was made. */
    private final java.time.Duration maxHold;

    public OrderHolds(InventoryClient inventory, java.time.Clock clock,
                      @org.springframework.beans.factory.annotation.Value("${oriah.sales.payment-submitted-hold:PT24H}") java.time.Duration maxHold) {
        this.inventory = inventory;
        this.clock = clock;
        this.maxHold = maxHold;
    }

    /** The order's lines, as inventory is asked for them: each under the order line's own id, its box under the box-line id minted with it. */
    static InventoryClient.ReserveAsk askFor(Order order) {
        return askFor(order, order.reservationId(), false);
    }

    /**
     * The same lines under another reservation id (a paid order whose hold is gone takes a new one). {@code keepBoxes} asks for the box inventory chose
     * the first time, so the customer's box does not change under them; the sale lines are the same ids, so a line can never be sold twice.
     */
    static InventoryClient.ReserveAsk askFor(Order order, java.util.UUID reservationId, boolean keepBoxes) {
        List<InventoryClient.LineAsk> lines = order.items().stream()
                .map(i -> new InventoryClient.LineAsk(i.id(), i.variantId(), i.productId(), i.quantity(), i.boxLineId(), keepBoxes ? i.boxVariantId() : null))
                .toList();
        return new InventoryClient.ReserveAsk(reservationId, order.id(), lines, null);
    }

    /** The piece itself is no longer there to sell: someone else's sale took it, or the product is withdrawn. The money is held for the customer. */
    public static class PieceGoneException extends RuntimeException {
        public PieceGoneException(String message) {
            super(message);
        }
    }

    /** Only the BOX is missing: the sale waits for one, because a paid ring that is still free is never refunded for a missing box. */
    public static class BoxGoneException extends RuntimeException {
        public BoxGoneException(String message) {
            super(message);
        }
    }

    /**
     * Sells the hold of a paid order, dated to the day the money arrived.
     *
     * @throws HoldGoneException the hold is not there to sell: lapsed, released, or unknown (a new one must be taken)
     * @throws UpstreamUnavailableException inventory did not answer (the same call is repeated later; it answers a repeat once)
     */
    public void sell(java.util.UUID reservationId, java.time.Instant occurredAt) {
        try {
            InventoryClient.Reservation sold = inventory.consume(reservationId, new InventoryClient.ConsumeAsk(occurredAt));
            if (sold == null || !"CONSUMED".equals(sold.status())) {
                log.error("inventory answered the sale of {} with {}", reservationId, sold);
                throw new UpstreamUnavailableException("inventory");
            }
        } catch (FeignException e) {
            if (e.status() == 404) {
                throw new HoldGoneException("inventory has no hold " + reservationId);
            }
            if (e.status() == 409) {
                String type = String.valueOf(problem(e).get("type"));
                if (type.endsWith("/reservation-not-held") || type.endsWith("/reservation-expired")) {
                    throw new HoldGoneException("the hold " + reservationId + " is gone: " + type);
                }
                log.warn("inventory refused the sale of {} with a conflict it can resolve by itself ({}): will ask again", reservationId, type);
                throw new UpstreamUnavailableException("inventory");
            }
            if (e.status() >= 400 && e.status() < 500) {
                log.error("inventory REFUSED the sale of {} with {}: {}", reservationId, e.status(), e.contentUTF8());
            } else {
                log.warn("the sale of {} was not answered: inventory status={}", reservationId, e.status());
            }
            throw new UpstreamUnavailableException("inventory");
        }
    }

    /**
     * Takes a NEW hold on the paid order's lines (its first one is gone, or it expired before the money came), under {@code reservationId}: asking again
     * with the same id answers the same hold.
     *
     * @throws PieceGoneException a piece is no longer there; {@link BoxGoneException} only a box
     */
    public void holdAgain(Order order, java.util.UUID reservationId) {
        try {
            inventory.reserve(askFor(order, reservationId, true));
        } catch (FeignException e) {
            if (e.status() == 409) {
                Map<String, Object> problem = problem(e);
                String type = String.valueOf(problem.get("type"));
                if (type.endsWith("/insufficient-stock")) {
                    boolean goodsShort = problem.get("shortages") instanceof List<?> shortages
                            && shortages.stream().anyMatch(s -> s instanceof Map<?, ?> m && !"BOX".equals(m.get("kind")));
                    boolean anyShort = problem.get("shortages") instanceof List<?> all && !all.isEmpty();
                    if (goodsShort || !anyShort) {
                        throw new PieceGoneException("a piece of order " + order.id() + " can no longer be held");
                    }
                    throw new BoxGoneException("only a box for order " + order.id() + " is missing");
                }
                if (type.endsWith("/not-a-sellable-product")) {
                    throw new PieceGoneException("a product of order " + order.id() + " is no longer for sale");
                }
                if (type.endsWith("/no-default-box") || type.endsWith("/not-packaging")) {
                    throw new BoxGoneException("order " + order.id() + " has no box to go with its pieces: " + type);
                }
                log.warn("inventory could not take a new hold for order {} yet ({}): will ask again", order.id(), type);
                throw new UpstreamUnavailableException("inventory");
            }
            if (e.status() >= 400 && e.status() < 500) {
                log.error("inventory REFUSED a new hold for order {} with {}: {}", order.id(), e.status(), e.contentUTF8());
            } else {
                log.warn("a new hold for order {} was not answered: inventory status={}", order.id(), e.status());
            }
            throw new UpstreamUnavailableException("inventory");
        }
    }

    public InventoryClient.Reservation hold(Order order) {
        try {
            InventoryClient.Reservation held = inventory.reserve(askFor(order));
            if (held == null || held.expiresAt() == null || !"HELD".equals(held.status())) {
                log.error("inventory answered a hold for order {} with {}", order.id(), held);
                throw new UpstreamRejectedException("inventory answered the hold with no HELD reservation");
            }
            return held;
        } catch (FeignException e) {
            throw refusal(order, e);
        }
    }

    /** The hold is not there to stretch: it lapsed, was released, or inventory never held it. The order must not go on as if it still held its pieces. */
    public static class HoldGoneException extends RuntimeException {
        public HoldGoneException(String message) {
            super(message);
        }
    }

    /**
     * Stretches the hold for a payment claim: to a day from now, which inventory caps at a day from when the hold was made (and never shortens).
     *
     * @return the time the pieces are now held to
     * @throws HoldGoneException the hold lapsed or was released (409 {@code reservation-expired} / {@code reservation-not-held}) or is unknown (404)
     */
    public java.time.Instant extend(Order order) {
        try {
            InventoryClient.Reservation stretched = inventory.extend(order.reservationId(),
                    new InventoryClient.ExtendAsk(clock.instant().plus(maxHold)));
            if (stretched == null || stretched.expiresAt() == null) {
                log.error("inventory answered order {}'s extension with no time: {}", order.id(), stretched);
                throw new UpstreamRejectedException("inventory answered the extension with no time");
            }
            return stretched.expiresAt();
        } catch (FeignException e) {
            if (e.status() == 404 || e.status() == 409) {
                log.info("order {}'s hold {} is gone at inventory ({}): {}", order.id(), order.reservationId(), e.status(), e.contentUTF8());
                throw new HoldGoneException("the hold on order " + order.id() + " is gone");
            }
            if (e.status() >= 400 && e.status() < 500) {
                log.error("inventory REFUSED order {}'s extension with {}: {}", order.id(), e.status(), e.contentUTF8());
                throw new UpstreamRejectedException("inventory refused order's extension with " + e.status());
            }
            log.warn("order {}'s extension was not answered: inventory status={}", order.id(), e.status());
            throw new UpstreamUnavailableException("inventory");
        }
    }

    /**
     * Gives a hold back (a placement nobody finished). Inventory answering 404 means it never held it: nothing to give back, which is the same
     * outcome. Anything else that is not an answer keeps the caller from deleting what a hold may still exist for.
     */
    public void release(java.util.UUID reservationId) {
        try {
            inventory.release(reservationId);
        } catch (FeignException e) {
            if (e.status() == 404) {
                return;
            }
            if (e.status() >= 400 && e.status() < 500) {
                log.error("inventory REFUSED the release of {} with {}: {}", reservationId, e.status(), e.contentUTF8());
                throw new UpstreamRejectedException("inventory refused order's release with " + e.status());
            }
            throw new UpstreamUnavailableException("inventory");
        }
    }

    private RuntimeException refusal(Order order, FeignException e) {
        if (e.status() == 409) {
            Map<String, Object> problem = problem(e);
            String type = problem.get("type") instanceof String t ? t : "";
            String code = type.startsWith(PROBLEM_PREFIX) ? type.substring(PROBLEM_PREFIX.length()) : "hold-refused";
            if (code.equals("reservation-in-progress")) {
                log.info("inventory is making order {}'s hold right now: ask again", order.id());
                return new UpstreamUnavailableException("inventory");
            }
            String detail = problem.get("detail") instanceof String d ? d : "the pieces could not be held";
            SalesProblem refused = new SalesProblem(HttpStatus.CONFLICT, code, detail);
            problem.forEach((k, v) -> {
                if (!List.of("type", "title", "status", "detail", "instance", "traceId", "timestamp").contains(k)) {
                    refused.with(k, v);
                }
            });
            return refused;
        }
        if (e.status() >= 400 && e.status() < 500) {
            log.error("inventory REFUSED order {}'s hold request with {}: {}", order.id(), e.status(), e.contentUTF8());
            return new UpstreamRejectedException("inventory refused order's hold request with " + e.status());
        }
        log.warn("order {}'s hold was not answered: inventory status={}", order.id(), e.status());
        return new UpstreamUnavailableException("inventory");
    }

    /** What inventory said, as a map; empty when it said nothing readable (the status alone then decides). */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> problem(FeignException e) {
        try {
            String body = e.contentUTF8();
            return body == null || body.isBlank() ? new java.util.LinkedHashMap<>() : new java.util.LinkedHashMap<>(JSON.readValue(body, Map.class));
        } catch (RuntimeException unreadable) {
            return new java.util.LinkedHashMap<>();
        }
    }
}
