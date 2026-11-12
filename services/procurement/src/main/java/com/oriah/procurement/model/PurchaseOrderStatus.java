package com.oriah.procurement.model;

/**
 * Where a purchase order stands. {@link #OPEN}: placed, goods not yet in. {@link #RECEIVED}:
 * its consignment was received, which is the end of it. {@link #CANCELLED}: never going
 * to arrive. The last two are history and are never changed again.
 */
public enum PurchaseOrderStatus {
    OPEN,
    RECEIVED,
    CANCELLED
}
