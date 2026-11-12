package com.oriah.procurement.model;

/**
 * What the forwarder's invoice says its single charge covers (CLIENT_DECISIONS 3.4). Recorded
 * per consignment, from the invoice, with NO default: it is never assumed.
 *
 * <p>{@link #DUTY_PAID}: duty, surtax and import VAT are already inside the courier's charge,
 * so a duty line of its own above zero would count them twice. {@link #FREIGHT_ONLY}: the
 * charge is freight and handling alone; anything assessed on top is keyed as its own line.
 */
public enum ChargesBasis {
    DUTY_PAID,
    FREIGHT_ONLY
}
