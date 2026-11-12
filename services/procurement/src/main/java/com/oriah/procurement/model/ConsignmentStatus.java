package com.oriah.procurement.model;

/**
 * Where a consignment stands. {@link #DRAFT}: being built (orders attached, costs keyed),
 * still free to change or delete. {@link #RECEIVED}: the goods have landed, the unit costs
 * are worked out and published, and nothing about it changes again.
 */
public enum ConsignmentStatus {
    DRAFT,
    RECEIVED
}
