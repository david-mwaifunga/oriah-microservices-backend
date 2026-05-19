package com.oriah.promotion.model;

public enum PromotionType {
    /** A flat amount off subtotal, e.g. $10 OFF. */
    FIXED,
    /** A percentage of subtotal, e.g. 15% OFF (amount stored as 15.00). */
    PERCENTAGE
}
