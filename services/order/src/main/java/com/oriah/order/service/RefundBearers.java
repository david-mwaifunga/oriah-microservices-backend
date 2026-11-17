package com.oriah.order.service;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Who bears the charge for sending a refund back, per reason (Phase 12, plan 5.2; FINAL AM3): {@code oriah.refunds.bearer.cancellation.<reason>} and
 * {@code oriah.refunds.bearer.return.<reason>}, each {@code CUSTOMER} or {@code ORIAH}. The client's rule is that the charge is taken from the refund
 * (the customer bears it); the owner may turn any one reason to ORIAH. The answer is written on the correction when it is recorded and travels on its
 * event, so a later change of a setting never changes a debt already recorded.
 *
 * <p>Every reason must have a setting: a reason with none refuses to start, so nobody ever bears a charge by accident. The reasons are the closed
 * lists the database holds too ({@code order_correction_reason_known}).
 */
@Component
@ConfigurationProperties("oriah.refunds.bearer")
public class RefundBearers {

    public static final Set<String> CANCELLATION_REASONS = Set.of("CUSTOMER_ASKED", "PIECE_UNAVAILABLE", "SHOP_ERROR", "OTHER");
    public static final Set<String> RETURN_REASONS = Set.of("CHANGED_MIND", "WRONG_SIZE", "FAULTY", "NOT_AS_DESCRIBED", "WRONG_ITEM_SENT", "DAMAGED_IN_TRANSIT",
            "NOT_DELIVERED", "OTHER");
    private static final Set<String> BEARERS = Set.of("CUSTOMER", "ORIAH");

    private Map<String, String> cancellation = Map.of();
    private Map<String, String> returns = Map.of();

    public Map<String, String> getCancellation() {
        return cancellation;
    }

    public void setCancellation(Map<String, String> cancellation) {
        this.cancellation = cancellation == null ? Map.of() : cancellation;
    }

    /** The property is {@code return}, which Java cannot name a field. */
    public Map<String, String> getReturn() {
        return returns;
    }

    public void setReturn(Map<String, String> returns) {
        this.returns = returns == null ? Map.of() : returns;
    }

    @PostConstruct
    public void validate() {
        check("cancellation", CANCELLATION_REASONS, cancellation);
        check("return", RETURN_REASONS, returns);
    }

    public String forCancellation(String reason) {
        return bearer("cancellation", CANCELLATION_REASONS, cancellation, reason);
    }

    public String forReturn(String reason) {
        return bearer("return", RETURN_REASONS, returns, reason);
    }

    private static String bearer(String kind, Set<String> reasons, Map<String, String> settings, String reason) {
        if (reason == null || !reasons.contains(reason)) {
            throw new IllegalArgumentException(reason + " is not a " + kind + " reason: one of " + new TreeSet<>(reasons));
        }
        return settings.get(reason);
    }

    private static void check(String kind, Set<String> reasons, Map<String, String> settings) {
        for (String reason : new TreeSet<>(reasons)) {
            String bearer = settings.get(reason);
            if (bearer == null || !BEARERS.contains(bearer)) {
                throw new IllegalStateException("oriah.refunds.bearer." + kind + "." + reason + " must be CUSTOMER or ORIAH (who bears the charge for sending "
                        + "this refund back); it is " + (bearer == null ? "not set" : "'" + bearer + "'"));
            }
        }
    }
}
