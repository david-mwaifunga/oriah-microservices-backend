package com.oriah.order.service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.oriah.order.client.InventoryClient;
import com.oriah.order.exception.SalesProblem;
import com.oriah.order.exception.UpstreamRejectedException;
import com.oriah.order.exception.UpstreamUnavailableException;
import com.oriah.order.service.BoxSwaps.Plan;
import com.oriah.order.service.BoxSwaps.Planned;
import com.oriah.order.service.BoxSwaps.Swap;

import feign.FeignException;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.json.JsonMapper;

/**
 * A box changed after the order was paid (Phase 7; design FINAL C4 and E5): the customer asks for a different box, or the one set aside is damaged.
 * Order decides whether it may be done and keeps the record; inventory does the stock. Deliberately NOT transactional: it asks inventory between two
 * short transactions ({@link BoxSwaps}), and no database connection is held while it answers.
 *
 * <p>Safe to repeat. The amendment id is the idempotency key shared with inventory, and the sale line the replacement is sold under is DERIVED from it,
 * so a retry after a lost reply (inventory did the swap, order never heard) asks for exactly the same swap, which inventory answers once.
 *
 * <p>While inventory is being asked the swap is IN FLIGHT ({@link BoxSwaps}): a durable record that keeps the order from shipping and from any other swap.
 * It is cleared when the swap is recorded, or when inventory refuses in a way that proves it moved nothing; any other answer leaves it, and the way out is
 * to repeat the swap.
 */
@Service
@Slf4j
public class BoxSwapService {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PROBLEM_PREFIX = "https://oriah.dev/problems/";
    /** The 409s from inventory that say it did not move the box (see {@link #provesNothingMoved}). */
    private static final java.util.Set<String> DEFINITE_REFUSALS = java.util.Set.of("not-a-box-sale", "same-box", "not-packaging", "insufficient-stock");

    private final BoxSwaps swaps;
    private final InventoryClient inventory;

    public BoxSwapService(BoxSwaps swaps, InventoryClient inventory) {
        this.swaps = swaps;
        this.inventory = inventory;
    }

    /** The sale line the replacement box is sold under: the same one on every retry of the same amendment. */
    static UUID replacementSaleLine(UUID amendmentId) {
        return UUID.nameUUIDFromBytes(("order-box-swap:" + amendmentId).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * @param amendmentId the caller's id for this swap, to retry safely; null derives one from the line as it stands, so a retry with no id is just as safe
     * @param actor       {@code user:<keycloak subject>} of the member of staff
     */
    public Swap swap(UUID orderId, UUID lineId, UUID newBoxVariantId, String reason, UUID amendmentId, String actor) {
        Planned planned = swaps.plan(orderId, lineId, newBoxVariantId, reason, amendmentId, actor);
        if (planned.recorded() != null) {
            return planned.recorded();
        }
        Plan plan = planned.plan();
        UUID amendment = plan.amendmentId();
        UUID replacementLine = replacementSaleLine(amendment);
        InventoryClient.BoxSwap done;
        try {
            done = inventory.swapBox(new InventoryClient.SwapAsk(amendment, orderId, plan.productId(), plan.oldBoxLineId(), replacementLine,
                    newBoxVariantId, plan.reason()));
        } catch (FeignException e) {
            Map<String, Object> problem = problem(e);
            String code = codeOf(e, problem);
            RuntimeException refusal = refused(e, problem, code);
            if (provesNothingMoved(e.status(), code)) {
                swaps.abandon(plan);
            } else {
                log.error("Order {}: box swap {} was asked of inventory and the answer ({}{}) does not say it did not happen: it stays IN FLIGHT -- the order "
                        + "cannot be shipped until the same swap is repeated and recorded", orderId, amendment, e.status(), code.isEmpty() ? "" : " " + code);
            }
            throw refusal;
        }
        return swaps.record(plan, replacementLine, done == null ? null : done.occurredAt(), actor);
    }

    /**
     * Whether inventory's refusal PROVES it moved nothing, so the swap is over and the order is free again. Only a refusal that names a reason it did not
     * (or could not) start is trusted: the box is not a box, the same box, not packaging, no stock, no such sale line, or a request refused before it was
     * looked at (a 4xx that is not a 409). A timeout, a server error, an amendment id inventory says it has used, a line it says it has already swapped,
     * or a 409 nobody has heard of may mean it happened: those leave the swap in flight.
     */
    static boolean provesNothingMoved(int status, String code) {
        if (status == 409) {
            return DEFINITE_REFUSALS.contains(code);
        }
        // Any other 4xx (a 404 included: no such sale line) was refused before inventory moved anything.
        return status >= 400 && status < 500;
    }

    private static String codeOf(FeignException e, Map<String, Object> problem) {
        String type = problem.get("type") instanceof String t ? t : "";
        if (type.startsWith(PROBLEM_PREFIX)) {
            return type.substring(PROBLEM_PREFIX.length());
        }
        return e.status() == 404 ? "not-found" : (e.status() == 409 ? "box-swap-refused" : "");
    }

    private RuntimeException refused(FeignException e, Map<String, Object> problem, String code) {
        if (e.status() == 404 || e.status() == 409) {
            String detail = problem.get("detail") instanceof String d ? d : "inventory could not swap the box";
            SalesProblem refused = new SalesProblem(HttpStatus.valueOf(e.status()), code, detail);
            problem.forEach((k, v) -> {
                if (!List.of("type", "title", "status", "detail", "instance", "traceId", "timestamp").contains(k)) {
                    refused.with(k, v);
                }
            });
            return refused;
        }
        if (e.status() >= 400 && e.status() < 500) {
            log.error("inventory REFUSED order's box swap with {}: {}", e.status(), e.contentUTF8());
            return new UpstreamRejectedException("inventory refused order's box swap with " + e.status());
        }
        log.warn("a box swap was not answered: inventory status={}", e.status());
        return new UpstreamUnavailableException("inventory");
    }

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
