package com.oriah.order.service;

import com.oriah.order.client.CatalogMarkdownClient;
import com.oriah.order.client.InventoryClient;
import com.oriah.order.exception.SalesProblem;
import com.oriah.order.service.ReturnPieces.Found;
import feign.FeignException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

/**
 * Looking at a piece that came back (Phase 12, plan 4.6): FINE (it goes back on sale), MARKED_DOWN (it is sold again on its own, under a listing of
 * its own, cheaper) or UNSELLABLE (it is written off). Order decides who may say what and keeps the decision; catalog makes the marked-down listing;
 * inventory moves the stock. Deliberately NOT transactional: catalog and inventory are asked between short transactions ({@link ReturnPieces}).
 *
 * <p>The piece stays held at inventory, not for sale, until inventory has recorded the outcome: so whatever fails on the way, the piece is "not on
 * sale", never "on sale wrongly".
 *
 * <ol>
 *   <li>MARKED_DOWN only, before anything is written: catalog is asked for the listing, with the person's own token. A failure leaves the piece
 *       undecided, so another outcome can still be chosen.</li>
 *   <li>The decision is written down (IN_PROGRESS).</li>
 *   <li>Inventory is asked, as the order system. A refusal that PROVES it recorded nothing takes the decision back, whole; any other failure leaves
 *       the piece IN_PROGRESS and {@link CorrectionJob} asks again (it needs nobody's token: the listing's id is stored).</li>
 *   <li>The piece is DONE.</li>
 * </ol>
 */
@Service
@Slf4j
public class AssessmentService {

    public static final String FINE = "FINE";
    public static final String MARKED_DOWN = "MARKED_DOWN";
    public static final String UNSELLABLE = "UNSELLABLE";

    /** What inventory calls each outcome. */
    private static final Map<String, String> AT_INVENTORY = Map.of(FINE, "RELEASED", MARKED_DOWN, "RELISTED", UNSELLABLE, "WRITTEN_OFF");

    /** {@code replay}: the piece had already been assessed this way. */
    public record Assessed(Corrections.Piece piece, boolean replay) {
    }

    private final ReturnPieces pieces;
    private final CatalogMarkdownClient catalog;
    private final InventoryClient inventory;

    public AssessmentService(ReturnPieces pieces, CatalogMarkdownClient catalog, InventoryClient inventory) {
        this.pieces = pieces;
        this.catalog = catalog;
        this.inventory = inventory;
    }

    /** The listing a marked-down piece moves to: the same id on every try, so catalog and inventory are each asked for exactly the same thing. */
    static UUID markdownVariant(UUID pieceId) {
        return UUID.nameUUIDFromBytes(("markdown:" + pieceId).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * @param actor {@code user:<keycloak subject>} of the member of staff
     * @return the piece: DONE, or IN_PROGRESS when inventory's answer settled nothing
     * @throws com.oriah.observability.ResourceNotFoundException 404
     * @throws SalesProblem 409 {@code return-not-received-yet}, {@code piece-already-assessed}, {@code assessment-in-progress}, catalog's or inventory's
     *                      own refusal under its own name; 503 {@code catalog-unavailable}
     */
    public Assessed assess(UUID returnId, UUID pieceId, String outcome, String note, String actor) {
        Found found = pieces.find(returnId, pieceId);
        if (ReturnPieces.judge(found, outcome)) {
            return new Assessed(found.piece(), true);
        }
        UUID child = MARKED_DOWN.equals(outcome) ? markdownVariant(pieceId) : null;
        if (child != null && ReturnPieces.AWAITING.equals(found.piece().state())) {
            makeTheListing(found, child);
        }
        Found decided = pieces.decide(returnId, pieceId, outcome, note, child, actor);
        if (ReturnPieces.DONE.equals(decided.piece().state())) {
            return new Assessed(decided.piece(), true);
        }
        try {
            askInventory(decided);
        } catch (FeignException e) {
            String code = InventoryProblems.code(e);
            if (CorrectionReplies.provesThePieceWasNotDecided(e.status(), code)) {
                pieces.backToAwaiting(pieceId);
                String why = InventoryProblems.detail(e);
                throw new SalesProblem(HttpStatus.CONFLICT, code.isEmpty() ? "assessment-refused" : code, "the stock system refused this and the piece is "
                        + "still waiting to be assessed" + (why == null ? "" : ": " + why)).with("pieceId", pieceId.toString());
            }
            log.warn("Return {}: piece {} ({}) was asked of inventory and the answer ({}{}) does not say it was recorded: it stays in progress and is asked "
                    + "again", returnId, pieceId, outcome, e.status(), code.isEmpty() ? "" : " " + code);
            return new Assessed(pieces.retryLater(pieceId).piece(), false);
        } catch (RuntimeException e) {
            log.warn("Return {}: piece {} ({}) could not be asked of inventory ({}): it stays in progress and is asked again", returnId, pieceId, outcome,
                    e.getClass().getSimpleName());
            return new Assessed(pieces.retryLater(pieceId).piece(), false);
        }
        return new Assessed(pieces.done(pieceId).piece(), false);
    }

    /** The job's turn at a piece whose outcome inventory has not recorded: the same request again, with what is stored. */
    public void resume(UUID pieceId) {
        Found found = pieces.find(pieceId);
        if (!ReturnPieces.IN_PROGRESS.equals(found.piece().state())) {
            return;
        }
        try {
            askInventory(found);
        } catch (FeignException e) {
            String code = InventoryProblems.code(e);
            if (CorrectionReplies.provesThePieceWasNotDecided(e.status(), code)) {
                pieces.backToAwaiting(pieceId);
                log.error("Return {}: inventory REFUSED the outcome {} of piece {} with {} {}: the piece is back to waiting and must be assessed again",
                        found.ret().id(), found.piece().outcome(), pieceId, e.status(), code);
                return;
            }
            pieces.retryLater(pieceId);
            return;
        } catch (RuntimeException e) {
            pieces.retryLater(pieceId);
            return;
        }
        pieces.done(pieceId);
    }

    private void askInventory(Found decided) {
        Corrections.Piece piece = decided.piece();
        inventory.decidePiece(piece.id(), new InventoryClient.PieceOutcomeAsk(AT_INVENTORY.get(piece.outcome()), piece.markdownVariantId(), piece.askedAt()));
    }

    private void makeTheListing(Found found, UUID child) {
        try {
            catalog.create(found.productId(), new CatalogMarkdownClient.MarkdownAsk(child, found.variantId(), found.piece().id()));
        } catch (FeignException e) {
            String code = InventoryProblems.code(e);
            boolean refused = e.status() >= 400 && e.status() < 500 && e.status() != 408 && e.status() != 425 && e.status() != 429;
            if (refused) {
                String why = InventoryProblems.detail(e);
                throw new SalesProblem(HttpStatus.CONFLICT, code.isEmpty() ? "markdown-refused" : code, "the catalogue could not list this piece on its own"
                        + (why == null ? "" : ": " + why) + "; the piece is still waiting to be assessed").with("pieceId", found.piece().id().toString());
            }
            log.warn("Return {}: catalog did not answer for the marked-down listing of piece {} ({}): the piece stays undecided", found.ret().id(),
                    found.piece().id(), e.status());
            throw new SalesProblem(HttpStatus.SERVICE_UNAVAILABLE, "catalog-unavailable", "the catalogue did not answer, so the piece was not marked down: "
                    + "nothing was changed; try again in a moment");
        } catch (RuntimeException e) {
            throw new SalesProblem(HttpStatus.SERVICE_UNAVAILABLE, "catalog-unavailable", "the catalogue could not be asked, so the piece was not marked down: "
                    + "nothing was changed; try again in a moment");
        }
    }
}
