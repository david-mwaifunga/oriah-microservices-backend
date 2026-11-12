package com.oriah.catalog.model;

/**
 * What a catalog entry is for.
 *
 * <p>{@link #SELLABLE} is something Oriah sells to a shopper: a ring, a watch.
 * {@link #PACKAGING} is what a sale goes out in -- a box, a pouch, a ribbon. It
 * is bought, stocked and costed like any other item (so a consignment line has
 * a catalog entry to point at), but it is never listed on the storefront and
 * never has a selling price: its cost is attached to the sale it went out with
 * (settled decision 9), not baked into the ring's own cost.
 *
 * <p>Fixed when the product is created. Stock, receipt lots and consignment
 * lines hang off its variants from Phase 4, so changing the kind afterwards
 * would orphan cost that was recorded against the other meaning.
 */
public enum ProductKind {
    SELLABLE,
    PACKAGING
}
