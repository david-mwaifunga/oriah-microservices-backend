package com.oriah.catalog.model;

/**
 * Stock availability classification for a product, as exposed on the PDP.
 *
 * <p>Computed by catalog at read time from the inventory service's
 * response:
 * <ul>
 *   <li>{@link #IN_STOCK}      — available >= 5</li>
 *   <li>{@link #LOW_STOCK}     — 0 < available < 5 (drives "Only N left" badges)</li>
 *   <li>{@link #OUT_OF_STOCK}  — available == 0</li>
 *   <li>{@link #UNKNOWN}       — inventory call failed or no inventory row yet</li>
 * </ul>
 */
public enum Availability {
    IN_STOCK,
    LOW_STOCK,
    OUT_OF_STOCK,
    UNKNOWN
}
