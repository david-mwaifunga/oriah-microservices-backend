package com.oriah.notification.text;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/**
 * An amount as a person in the shop writes it: {@code K5,875.00}. Every message that names money goes through here, so no message ever prints
 * "5875.00 ZMW".
 *
 * <p>An amount in any other currency keeps its code ({@code 5,875.00 USD}) so that it can never be read as Kwacha. The writing is fixed (a comma
 * between thousands, a point before the ngwee) whatever language the server runs in.
 */
public final class Kwacha {

    private static final String KWACHA = "ZMW";

    private Kwacha() {
    }

    public static String write(BigDecimal amount, String currency) {
        // A new formatter each time: DecimalFormat is not safe to share between threads, and a message is not written often enough to mind.
        DecimalFormat twoDecimals = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.ENGLISH));
        String figure = twoDecimals.format(amount.setScale(2, RoundingMode.HALF_UP));
        return KWACHA.equals(currency) ? "K" + figure : figure + " " + currency;
    }
}
