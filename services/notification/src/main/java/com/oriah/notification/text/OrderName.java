package com.oriah.notification.text;

import java.util.Locale;
import java.util.UUID;

/**
 * What a message calls an order. Once it is paid: the receipt number the shop itself uses ({@code OR-000123}). Before that: the first eight
 * characters of its id in capitals ({@code 8A1F4B2E}), short enough to read back over the phone. Never the whole 36-character id.
 */
public final class OrderName {

    private static final int CODE_LENGTH = 8;

    private OrderName() {
    }

    /** @param receiptNumber the order's receipt number when notification has learnt it; null or blank before the order is paid */
    public static String of(UUID orderId, String receiptNumber) {
        return receiptNumber == null || receiptNumber.isBlank() ? code(orderId) : receiptNumber;
    }

    public static String code(UUID orderId) {
        return orderId.toString().substring(0, CODE_LENGTH).toUpperCase(Locale.ROOT);
    }
}
