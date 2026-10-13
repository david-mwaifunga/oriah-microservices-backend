package com.oriah.order.exception;

/**
 * The caller stated a price or currency that is not the one catalog holds.
 *
 * <h2>Why a stated price is checked rather than ignored</h2>
 *
 * Repricing and overwriting would have introduced a worse bug than it closed.
 * Today the checkout path has exactly ONE price read: checkout reprices the
 * bag, sends those prices to order, and charges that same total. Had order
 * read catalog independently and overwritten, that path would have TWO reads
 * with nothing reconciling them -- so a price moving in between leaves an
 * order whose recorded total exceeds what the card paid. An invisible
 * under-charge on the cart path.
 *
 * <p>Refusing closes the window without a second release, because placement
 * runs before the payment intent is created: a refusal costs a retry and
 * nothing else.
 *
 * <h2>One message for both conditions</h2>
 *
 * Price and currency mismatches raise the same text and do not report which
 * one, or what catalog's figure is. The caller already knows what they sent;
 * telling them the real price would turn a refusal into a price feed, and the
 * remedy is identical either way -- re-read the item and try again. The
 * operator-facing log line carries both figures.
 */
public class PriceClaimRejectedException extends IllegalStateException {

    public PriceClaimRejectedException() {
        super("The price has changed since you chose these items. "
                + "Please review your order and try again.");
    }
}
