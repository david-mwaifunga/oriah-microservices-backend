package com.oriah.catalog.pricing;

/** How the product's default box entered the comparison. */
public enum BoxBasis {

    /** The product has no default box. */
    NONE,

    /** It has one, but no lot of it is held now, so it adds nothing yet. */
    NO_HELD_STOCK,

    /** The dearest held lot of the box is added to the goods' cost. */
    COUNTED
}
