package com.oriah.catalog.service;

import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;

import com.oriah.catalog.client.InForceReply;
import com.oriah.catalog.client.PromotionClient;
import com.oriah.catalog.exception.PromotionsUnavailableException;
import com.oriah.catalog.promotion.PromotionDefinition;

import feign.FeignException;
import lombok.extern.slf4j.Slf4j;

/**
 * Asks promotion what is in force at an instant.
 *
 * <p>An answer is the real answer or a {@link PromotionsUnavailableException} (503): the failure that must never happen is silence. A promotion
 * service that cannot be reached, answers slowly, refuses, no longer has the route, or answers something that does not read as a list of
 * promotions is never taken to mean "no promotion in force", because that would charge the list price during a promotion and quote it to the
 * customer. See {@link PromotionClient}.
 */
@Service
@Slf4j
public class PromotionLookup {

    private final PromotionClient client;

    public PromotionLookup(PromotionClient client) {
        this.client = client;
    }

    /**
     * @param at the instant to price at; null means now (promotion's own clock answers)
     * @return the promotions in force then, possibly none
     * @throws PromotionsUnavailableException whenever a real answer could not be had
     */
    public List<PromotionDefinition> inForce(Instant at) {
        InForceReply reply;
        try {
            reply = client.inForce(at == null ? null : at.toString());
        } catch (FeignException e) {
            if (e.status() >= 500 || e.status() < 0 || e.status() == 408 || e.status() == 429) {
                log.warn("promotion could not answer the in-force read (status {}): {}", e.status(), e.getMessage());
            } else {
                log.error("promotion answered the in-force read with {}, which catalog does not expect: {}", e.status(), e.getMessage());
            }
            throw new PromotionsUnavailableException(e);
        } catch (RuntimeException e) {
            log.error("the in-force read could not be made or its answer could not be read: {}", e.toString());
            throw new PromotionsUnavailableException(e);
        }
        if (reply == null || reply.promotions() == null) {
            log.error("promotion answered the in-force read with no list of promotions at all");
            throw new PromotionsUnavailableException(new IllegalStateException("no promotions list in the answer"));
        }
        for (PromotionDefinition p : reply.promotions()) {
            if (p == null || p.id() == null || p.scopeType() == null || p.discountType() == null || p.amount() == null || p.startsOn() == null) {
                log.error("promotion answered the in-force read with a promotion that is missing what pricing needs: {}", p);
                throw new PromotionsUnavailableException(new IllegalStateException("an incomplete promotion in the answer"));
            }
        }
        return List.copyOf(reply.promotions());
    }
}
