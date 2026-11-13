package com.oriah.inventory.ledger;

import java.util.Arrays;

/**
 * Why stock moved: ONE closed list, born in Phase 5 and complete from the start, so that later phases add handlers
 * and never reasons (CLIENT_DECISIONS 1.1; a report by reason, such as the one Smart Invoice will want, groups on
 * {@link #category()}). The database holds the same list in a CHECK on {@code stock_movement.reason}; a test pins
 * the two together.
 *
 * <p>Wired in Phase 5: {@code CONSIGNMENT_RECEIVED}, {@code SALE}, the write-offs, the give-aways,
 * {@code TAKEN_BACK} and {@code COST_CORRECTED}. {@code RETURN}, {@code CANCELLED_BEFORE_DISPATCH},
 * {@code RELISTED_MARKED_DOWN} and {@code BOX_SWAPPED} exist so Phases 7 and 12 only add behaviour.
 */
public enum StockReason {

    /** Goods landed: a consignment line becomes lots (day-one stock too; its lots say {@code isEstimated}). */
    CONSIGNMENT_RECEIVED(Category.RECEIPT),
    /** A sale drained the lots (Phase 7 calls it; the order saga). */
    SALE(Category.SALE),
    /** A customer returned it after dispatch (Phase 12). */
    RETURN(Category.RETURN),
    WRITE_OFF_LOST(Category.WRITE_OFF),
    WRITE_OFF_STOLEN(Category.WRITE_OFF),
    WRITE_OFF_BROKEN(Category.WRITE_OFF),
    GIVEN_AWAY_MODEL(Category.GIVE_AWAY),
    GIVEN_AWAY_INFLUENCER(Category.GIVE_AWAY),
    GIVEN_AWAY_GIFT(Category.GIVE_AWAY),
    GIVEN_AWAY_PRIZE(Category.GIVE_AWAY),
    /** A piece written off or given away came back; it re-enters at the same lot cost. */
    TAKEN_BACK(Category.TAKE_BACK),
    /** A paid order was cancelled before dispatch (Phase 12). */
    CANCELLED_BEFORE_DISPATCH(Category.CANCELLATION),
    /** A returned piece was relisted at a lower price (Phase 12). */
    RELISTED_MARKED_DOWN(Category.MARKDOWN),
    /** A box was swapped on a paid order (Phase 7). */
    BOX_SWAPPED(Category.BOX_SWAP),
    /** A lot was closed and its units reopened at a corrected cost: a valuation transfer, not an event in the shop. */
    COST_CORRECTED(Category.COST_CORRECTION);

    /** What a report by reason groups on. */
    public enum Category { RECEIPT, SALE, RETURN, WRITE_OFF, GIVE_AWAY, TAKE_BACK, CANCELLATION, MARKDOWN, BOX_SWAP, COST_CORRECTION }

    private final Category category;

    StockReason(Category category) {
        this.category = category;
    }

    public Category category() {
        return category;
    }

    /** The reasons a person with the write-off capability may give: the stock leaves, no sale is involved. */
    public boolean isWriteOffOrGiveAway() {
        return category == Category.WRITE_OFF || category == Category.GIVE_AWAY;
    }

    public static StockReason fromCode(String code) {
        if (code != null) {
            for (StockReason reason : values()) {
                if (reason.name().equals(code)) {
                    return reason;
                }
            }
        }
        throw new IllegalArgumentException("unknown reason " + code + ": it must be one of " + Arrays.toString(values()));
    }
}
