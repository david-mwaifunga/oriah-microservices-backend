package com.oriah.finance.service;

import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;

import com.oriah.finance.exception.FinanceProblem;

/** Free text is kept for good in rows that are never edited, so it names no one: a Zambian phone number, however it is typed (spaces, dots, dashes, brackets, +260, other scripts' digits), is refused. */
final class FreeText {

    /** A Zambian phone number once its separators are gone: 0977123456, 977123456, 260977123456 (an optional +). Bounded by non-digits, so a long reference is not one. */
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(?:260)?0?[79]\\d{8}(?!\\d)");

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
        if (text != null && PHONE.matcher(digitsAndLetters(text)).find()) {
            throw new FinanceProblem(HttpStatus.BAD_REQUEST, "no-phone-numbers", "Do not type phone numbers into " + what + ": it is kept for good and names no one");
        }
    }

    /** The text with every kind of digit written as 0-9 and the separators people put inside a number (space, dot, dash, brackets, plus) removed. */
    private static String digitsAndLetters(String text) {
        StringBuilder out = new StringBuilder(text.length());
        text.codePoints().forEach(cp -> {
            if (Character.isDigit(cp)) {
                out.append((char) ('0' + Character.digit(cp, 10)));
            } else if (!(Character.isWhitespace(cp) || cp == '.' || cp == '-' || cp == '(' || cp == ')' || cp == '+' || cp == '‐' || cp == '‑' || cp == '‒' || cp == '–')) {
                out.append(Character.toChars(cp));
            }
        });
        return out.toString();
    }
}
