package com.oriah.catalog.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.oriah.catalog.client.HeldCostReply;
import com.oriah.catalog.client.InventoryCostClient;
import com.oriah.catalog.exception.CostContractException;
import com.oriah.catalog.exception.CostReadRefusedException;
import com.oriah.catalog.exception.CostUnavailableException;
import com.oriah.catalog.pricing.HeldCost;

import feign.FeignException;
import lombok.extern.slf4j.Slf4j;

/**
 * Asks inventory what the stock still held cost, for a person pricing a piece.
 *
 * <p>A lookup either answers EVERY variant it was asked about or throws. That is the whole point of the class: an answer that
 * leaves a variant out, or a call that could not be made, read as "no cost known" would let a price below cost through unseen.
 * See {@link InventoryCostClient} for why this call is not like the stock badge's.
 *
 * <p>The ways it fails, each told apart:
 * <ul>
 *   <li>inventory could not be reached, was too slow, or failed (connect, timeout, 5xx): {@link CostUnavailableException}, 503 with
 *       {@code Retry-After};</li>
 *   <li>inventory refused the token (401, 403) although the person may read cost: {@link CostReadRefusedException}, 502, a fault;</li>
 *   <li>too busy (408, 429): the same {@link CostUnavailableException}, try again;</li>
 *   <li>anything else (another 4xx, a body that does not read, an answer that misses a variant or adds one): a
 *       {@link CostContractException}, a 500 that is logged at ERROR: the two services disagree about the contract.</li>
 * </ul>
 */
@Service
@Slf4j
public class CostLookup {

    /** Inventory answers at most this many variants at a time. */
    static final int MAX_IDS_PER_CALL = 100;

    private final InventoryCostClient client;

    public CostLookup(InventoryCostClient client) {
        this.client = client;
    }

    /**
     * @param variantIds the goods and boxes to ask about; a variant named twice is asked about once
     * @param bearer     the caller's own {@code Authorization} header value ({@code Bearer ...}), read by the caller on the request
     *                   thread
     * @return one entry per distinct variant asked about
     */
    public Map<UUID, HeldCost> heldCosts(Collection<UUID> variantIds, String bearer) {
        if (bearer == null || bearer.isBlank()) {
            throw new IllegalArgumentException("the caller's token is required to ask what stock cost");
        }
        List<UUID> ids = new ArrayList<>(new LinkedHashSet<>(variantIds));
        Map<UUID, HeldCost> answer = new LinkedHashMap<>();
        for (int from = 0; from < ids.size(); from += MAX_IDS_PER_CALL) {
            List<UUID> chunk = ids.subList(from, Math.min(ids.size(), from + MAX_IDS_PER_CALL));
            answer.putAll(ask(chunk, bearer));
        }
        return answer;
    }

    private Map<UUID, HeldCost> ask(List<UUID> chunk, String bearer) {
        HeldCostReply reply;
        try {
            reply = client.held(bearer, chunk.stream().map(UUID::toString).collect(Collectors.joining(",")));
        } catch (FeignException e) {
            throw classify(e);
        }
        if (reply == null || reply.variants() == null) {
            throw new CostContractException("inventory answered the held-cost read with no variants at all", null);
        }
        Map<UUID, HeldCost> byId = new LinkedHashMap<>();
        for (HeldCost cost : reply.variants()) {
            if (cost == null || cost.variantId() == null) {
                throw new CostContractException("inventory answered the held-cost read with an entry that names no variant", null);
            }
            byId.put(cost.variantId(), cost);
        }
        for (UUID asked : chunk) {
            if (!byId.containsKey(asked)) {
                throw new CostContractException("inventory left variant " + asked + " out of its held-cost answer: it is never read as 'no cost known'", null);
            }
        }
        if (byId.size() != chunk.size()) {
            throw new CostContractException("inventory answered about " + byId.size() + " variants when asked about " + chunk.size(), null);
        }
        return byId;
    }

    private RuntimeException classify(FeignException e) {
        int status = e.status();
        if (status == 401 || status == 403) {
            log.error("inventory refused the held-cost read with {}: the person pricing may read cost, so the two services disagree about roles or the token expired", status);
            return new CostReadRefusedException(e);
        }
        if (status >= 500 || status < 0 || status == 408 || status == 429) {
            log.warn("inventory could not answer the held-cost read (status {}): {}", status, e.getMessage());
            return new CostUnavailableException(e);
        }
        log.error("inventory answered the held-cost read with {}, which catalog does not expect: {}", status, e.getMessage());
        return new CostContractException("inventory answered the held-cost read with " + status, e);
    }
}
