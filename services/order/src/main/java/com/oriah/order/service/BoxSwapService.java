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
 */
@Service
@Slf4j
public class BoxSwapService {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PROBLEM_PREFIX = "https://oriah.dev/problems/";

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
        Planned planned = swaps.plan(orderId, lineId, newBoxVariantId, reason, amendmentId);
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
            throw refused(e);
        }
        return swaps.record(plan, replacementLine, done == null ? null : done.occurredAt(), actor);
    }

    private RuntimeException refused(FeignException e) {
        if (e.status() == 404 || e.status() == 409) {
            Map<String, Object> problem = problem(e);
            String type = problem.get("type") instanceof String t ? t : "";
            String code = type.startsWith(PROBLEM_PREFIX) ? type.substring(PROBLEM_PREFIX.length()) : (e.status() == 404 ? "not-found" : "box-swap-refused");
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
