package com.oriah.catalog.pricing;

/** What the below-cost rule found. It never refuses a price; it only says what it saw. */
public enum PriceAssessment {

    /** The price covers the dearest held cost (and the default box's). */
    OK,

    /** The price is below the dearest held cost plus the default box's: saving it needs a typed reason. */
    BELOW,

    /** Nothing is held, so there is no cost to compare with. Says so; never reads as OK. */
    NO_COST_KNOWN,

    /** The price is in a currency other than the lots': only legacy data can say this (prices are ZMW). Treated like BELOW. */
    CURRENCY_MISMATCH
}
