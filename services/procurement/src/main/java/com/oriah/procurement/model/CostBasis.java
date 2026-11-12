package com.oriah.procurement.model;

/**
 * How a shared inbound cost is divided between a consignment's lines.
 *
 * <p>{@link #UNITS} shares by quantity (a courier charge is for size and bulk,
 * not for value), {@link #VALUE} by each line's all-in goods total (a flat agent
 * fee), {@link #WEIGHT} by the line's total chargeable weight in grams, and
 * {@link #DIRECT} not at all: the figure is typed per line, which is how a ZRA
 * assessment arrives (duty, surtax and import VAT are never pooled and spread).
 */
public enum CostBasis {
    UNITS,
    VALUE,
    WEIGHT,
    DIRECT
}
