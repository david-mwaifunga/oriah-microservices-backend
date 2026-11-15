package com.oriah.finance.service;

import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;

import com.oriah.finance.exception.FinanceProblem;

/** Free text is kept for good in rows that are never edited, so it names no one: a run of nine or more digits (a phone number) is refused. */
final class FreeText {

    private static final Pattern DIGIT_RUN = Pattern.compile("\\d{9,}");

    private static final Pattern PROVIDER = Pattern.compile("[A-Z_]{2,20}");

    private FreeText() {
    }

    /** A payment provider is a plain code (MTN, AIRTEL, ZAMTEL, BANK), written the way a statement check writes it, so a bill typed against it meets the statement of the same provider. */
    static String providerCode(String provider) {
        if (provider == null || provider.isBlank()) {
            return provider;
        }
        String code = provider.strip().toUpperCase(java.util.Locale.ROOT);
        if (!PROVIDER.matcher(code).matches()) {
            throw new FinanceProblem(HttpStatus.BAD_REQUEST, "invalid-provider", "A payment provider is a plain code such as MTN, AIRTEL, ZAMTEL or BANK (letters only, up to 20)");
        }
        return code;
    }

    static void noPhoneNumbers(String text, String what) {
        if (text != null && DIGIT_RUN.matcher(text.replaceAll("[\\s+\\-()]", "")).find()) {
            throw new FinanceProblem(HttpStatus.BAD_REQUEST, "no-phone-numbers", "Do not type phone numbers into " + what + ": it is kept for good and names no one");
        }
    }
}
