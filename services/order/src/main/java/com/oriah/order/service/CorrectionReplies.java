package com.oriah.order.service;

import java.time.Duration;
import java.util.Set;

/**
 * How order reads inventory's answer to a correction (Phase 12, plan 4.2 and 4.6, FINAL AM2). A pure rule, the same idea as the box swap's
 * {@code provesNothingMoved}: a correction is asked of inventory between two short transactions, so an answer either PROVES that no stock moved (the
 * correction is over and the order is free again) or it does not (the stock may have gone back: the correction waits and is asked again).
 */
public final class CorrectionReplies {

    /** Inventory's 404s on a reversal that it raises itself, before it writes anything. */
    private static final Set<String> NOT_FOUND = Set.of("sale-line-not-found", "reservation-not-found");
    /** Inventory's 409s on a reversal that say it did not start. */
    private static final Set<String> REFUSED = Set.of("not-a-sale", "reservation-not-consumed", "box-line-not-live", "reversal-abandoned");
    /** Inventory's 409s on a piece's outcome that say the piece is still waiting. */
    private static final Set<String> PIECE_REFUSED = Set.of("markdown-variant-of-another-product", "markdown-variant-in-use", "already-marked-down");

    private static final Duration FIRST_WAIT = Duration.ofSeconds(15);
    private static final Duration LONGEST_WAIT = Duration.ofMinutes(5);

    private CorrectionReplies() {
    }

    /**
     * Whether inventory's refusal of a sale reversal proves it moved nothing. NOT proof: a timeout, a 5xx, a 408, 425 or 429 (anything on the way can
     * produce those after inventory has acted), a 401 (order's own token was rejected: see below), a bare 404, {@code reversal-id-used} and
     * {@code more-than-outstanding} (what a repeat of a reversal that DID happen can look like) and any 409 nobody has heard of.
     * {@code reversal-abandoned} IS proof: inventory recorded that this id was given up, and does nothing under it.
     *
     * @param status the HTTP status (-1 when there was none)
     * @param code   inventory's problem type without its prefix; empty when the answer carried none
     */
    public static boolean provesNothingMoved(int status, String code) {
        if (status == 404) {
            return NOT_FOUND.contains(code);
        }
        if (status == 409) {
            return REFUSED.contains(code);
        }
        return refusedBeforeItWasLookedAt(status);
    }

    /** Reading a reversal back: only inventory's own typed 404 says it has none (it takes the reversal's lock first, so it waits out one being written). */
    public static boolean saysThereIsNoReversal(int status, String code) {
        return status == 404 && "reversal-not-found".equals(code);
    }

    /**
     * Whether inventory's refusal of a returned piece's outcome proves the piece was not decided, so it goes back to waiting and another outcome may be
     * chosen. {@code markdown-variant-not-known-yet} is a wait (inventory has not heard of the new listing yet), and {@code piece-outcome-recorded}
     * means inventory holds a DIFFERENT outcome, which a person must look at: neither frees the piece.
     */
    public static boolean provesThePieceWasNotDecided(int status, String code) {
        if (status == 409) {
            return PIECE_REFUSED.contains(code);
        }
        return refusedBeforeItWasLookedAt(status);
    }

    /** How long to wait before asking again: 15 seconds, doubling, five minutes at most. */
    public static Duration backoff(int attempts) {
        Duration wait = FIRST_WAIT.multipliedBy(1L << Math.min(Math.max(attempts, 0), 10));
        return wait.compareTo(LONGEST_WAIT) > 0 ? LONGEST_WAIT : wait;
    }

    /**
     * Refused before inventory looked at what was asked: malformed, not allowed, not processable. NOT 401: a rejected token is about order's own
     * token at that moment (expired, or signed by keys Keycloak has since replaced), so it can answer a REPEAT of a request whose first try moved
     * the stock and whose reply was lost. It proves nothing, and the correction waits.
     */
    private static boolean refusedBeforeItWasLookedAt(int status) {
        return status == 400 || status == 403 || status == 422;
    }
}
