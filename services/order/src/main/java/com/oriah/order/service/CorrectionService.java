package com.oriah.order.service;

import com.oriah.observability.ResourceNotFoundException;
import com.oriah.order.client.InventoryClient;
import com.oriah.order.exception.SalesProblem;
import com.oriah.order.service.Corrections.Begun;
import com.oriah.order.service.Corrections.Correction;
import feign.FeignException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Undoing a paid sale (Phase 12, plan 4.2 and 4.3): cancelling a paid order before it is dispatched, and recording pieces that came back after. Order
 * decides whether it may be done and keeps the record; inventory puts the stock back. Deliberately NOT transactional: inventory is asked between two
 * short transactions ({@link Corrections}), and no database connection is held while it answers.
 *
 * <h2>Safe to repeat</h2>
 *
 * The correction's id is the key shared with inventory, and the request is rebuilt from what is stored, so every try (the person's, a repeat, the job's)
 * asks for exactly the same thing, which inventory does once.
 *
 * <h2>What inventory's answer means</h2>
 *
 * {@link CorrectionReplies} decides. An answer that PROVES nothing moved ends the attempt (REFUSED; the order is free again; the person is told why, and
 * a new request is a new attempt with a new id). Any other failure (silence, a server error, an answer nobody has heard of) may mean the stock DID go
 * back: the correction stays REQUESTED, the order is held as it is, and the same request is made again by a repeat or by {@link CorrectionJob}.
 */
@Service
@Slf4j
public class CorrectionService {

    /** What a request came to. {@code replay}: this answers a correction that had already been recorded. */
    public record Outcome(Correction correction, boolean replay) {
    }

    private final Corrections corrections;
    private final InventoryClient inventory;

    public CorrectionService(Corrections corrections, InventoryClient inventory) {
        this.corrections = corrections;
        this.inventory = inventory;
    }

    /**
     * Cancels a paid order that has not been dispatched: every piece and its box go back to stock, and everything that was paid is owed.
     *
     * @param actor {@code user:<keycloak subject>} of the member of staff
     * @return the cancellation: DONE, or still REQUESTED when inventory's answer settled nothing
     * @throws SalesProblem the refusals of {@link Corrections#beginCancellation}; 409 {@code cancellation-refused} when inventory proved it moved nothing
     */
    public Outcome cancel(UUID orderId, String reasonCode, String note, String actor) {
        Begun begun = corrections.beginCancellation(orderId, reasonCode, note, actor);
        Correction correction = begun.correction();
        if (!correction.inFlight()) {
            return new Outcome(correction, true);
        }
        return new Outcome(askInventory(correction, "cancellation-refused"), !begun.created());
    }

    /**
     * Records pieces that came back after the order was dispatched or handed over: each is put back into its lot at inventory and held there, not for
     * sale, until it is assessed; the customer is owed the pieces' own price less their own discount.
     *
     * @param key   the caller's Idempotency-Key
     * @param actor {@code user:<keycloak subject>} of the member of staff
     * @return the return: DONE, or still REQUESTED when inventory's answer settled nothing
     * @throws SalesProblem the refusals of {@link Corrections#beginReturn}; 409 {@code return-refused} when inventory proved it moved nothing (also
     *                      when this key is repeated for an attempt that was refused: a new attempt needs a new key)
     */
    public Outcome recordReturn(UUID orderId, Corrections.ReturnAsk ask, String key, String actor, boolean mayBackdate) {
        Begun begun = corrections.beginReturn(orderId, ask, key, actor, mayBackdate);
        Correction correction = begun.correction();
        if (Corrections.REFUSED.equals(correction.state())) {
            throw new SalesProblem(HttpStatus.CONFLICT, "return-refused", "this return was refused by the stock system and nothing was changed: record it "
                    + "again with a new Idempotency-Key once the cause is dealt with").with("correctionId", correction.id().toString())
                    .with("refusedCode", correction.refusedCode());
        }
        if (!correction.inFlight()) {
            return new Outcome(correction, true);
        }
        return new Outcome(askInventory(correction, "return-refused"), !begun.created());
    }

    /**
     * The job's turn at a correction that is still in flight: first ask inventory whether it already holds the reversal (the reply may have been lost,
     * or this service stopped between the two halves); if it says it has none, send the same request again; any other answer waits for the next pass.
     */
    public void resume(UUID correctionId) {
        Correction correction = corrections.get(correctionId);
        if (!correction.inFlight()) {
            return;
        }
        try {
            inventory.reversal(correctionId);
        } catch (FeignException e) {
            if (CorrectionReplies.saysThereIsNoReversal(e.status(), InventoryProblems.code(e))) {
                try {
                    askInventory(correction, "correction-refused");
                } catch (SalesProblem refused) {
                    // Already logged and recorded REFUSED: there is nobody to tell from a job.
                }
                return;
            }
            log.warn("Correction {} of order {}: inventory could not say whether it holds the reversal ({}): it waits", correctionId, correction.orderId(), e.status());
            corrections.retryLater(correctionId);
            return;
        } catch (RuntimeException e) {
            log.warn("Correction {} of order {}: inventory could not be asked ({}): it waits", correctionId, correction.orderId(), e.getClass().getSimpleName());
            corrections.retryLater(correctionId);
            return;
        }
        corrections.finish(correctionId);
    }

    /**
     * Gives up a correction that is stuck in flight, by hand. Inventory is asked to abandon the reversal id: if it had NOT been applied, inventory
     * records the abandon (no later request under that id is ever applied), the attempt is REFUSED and the order is free again. If it had been
     * applied, the stock moved: the correction is finished instead and the person is told so. No answer proves nothing, and nothing is changed.
     *
     * @throws ResourceNotFoundException 404
     * @throws SalesProblem 409 {@code not-in-flight}, 409 {@code it-did-move}, 503 {@code inventory-unavailable}
     */
    public Correction abandon(UUID correctionId) {
        Correction correction = corrections.get(correctionId);
        if (!correction.inFlight()) {
            throw new SalesProblem(HttpStatus.CONFLICT, "not-in-flight", "this correction is " + correction.state() + ": only one that is still with the stock "
                    + "system can be abandoned").with("state", correction.state());
        }
        // Inventory decides, under the reversal's own lock, and REMEMBERS an abandon: a request for this id that is still on its way (the job's, a
        // person's repeat, one queued behind a slow connection) is then refused by name and moves nothing. Reading "there is none" and writing
        // REFUSED here would leave that request free to put the stock back for an order that carries on as paid.
        InventoryClient.Abandoned answer;
        try {
            answer = inventory.abandonReversal(correctionId);
        } catch (FeignException e) {
            throw unknown(e.status());
        } catch (RuntimeException e) {
            throw unknown(-1);
        }
        if (answer == null) {
            throw unknown(-1);
        }
        if (!answer.applied()) {
            Correction abandoned = corrections.refuse(correctionId, "abandoned-nothing-moved");
            log.warn("Correction {} of order {} was abandoned by hand: inventory recorded the abandon, nothing moved and nothing will under this id",
                    correctionId, correction.orderId());
            return abandoned;
        }
        corrections.finish(correctionId);
        throw new SalesProblem(HttpStatus.CONFLICT, "it-did-move", "the stock system had already put the stock back for this correction, so it could not be "
                + "abandoned: it has been finished instead").with("correctionId", correctionId.toString()).with("state", Corrections.DONE);
    }

    // ------------------------------------------------------------------ asking inventory

    /**
     * @param refusedAs the problem type the person sees when inventory proves it moved nothing
     * @return the correction as it stands afterwards: DONE, or REQUESTED when the answer settled nothing
     */
    Correction askInventory(Correction correction, String refusedAs) {
        UUID id = correction.id();
        try {
            inventory.reverseSale(corrections.ask(id));
        } catch (FeignException e) {
            String code = InventoryProblems.code(e);
            if (CorrectionReplies.provesNothingMoved(e.status(), code)) {
                String refusedCode = code.isEmpty() ? "http-" + e.status() : code;
                corrections.refuse(id, refusedCode);
                log.error("Order {}: inventory REFUSED {} {} with {} {} before anything moved: the attempt is over and the order is unchanged -- someone must "
                        + "look at why", correction.orderId(), correction.kind(), id, e.status(), refusedCode);
                String why = InventoryProblems.detail(e);
                throw new SalesProblem(HttpStatus.CONFLICT, refusedAs, "the stock system refused this and nothing was changed"
                        + (why == null ? "" : ": " + why)).with("correctionId", id.toString()).with("refusedCode", refusedCode);
            }
            log.warn("Order {}: {} {} was asked of inventory and the answer ({}{}) does not say whether it happened: it stays in flight and is asked again",
                    correction.orderId(), correction.kind(), id, e.status(), code.isEmpty() ? "" : " " + code);
            return corrections.retryLater(id);
        } catch (RuntimeException e) {
            log.warn("Order {}: {} {} could not be asked of inventory ({}): it stays in flight and is asked again", correction.orderId(), correction.kind(), id,
                    e.getClass().getSimpleName());
            return corrections.retryLater(id);
        }
        return corrections.finish(id);
    }

    private static SalesProblem unknown(int status) {
        return new SalesProblem(HttpStatus.SERVICE_UNAVAILABLE, "inventory-unavailable", "the stock system could not say whether it put the stock back"
                + (status > 0 ? " (it answered " + status + ")" : "") + ": nothing was changed; try again in a moment");
    }
}
