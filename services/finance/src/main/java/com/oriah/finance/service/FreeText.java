package com.oriah.finance.service;

import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;

import com.oriah.finance.exception.FinanceProblem;

/** Free text is kept for good in rows that are never edited, so it names no one: a run of nine or more digits (a phone number) is refused. */
final class FreeText {

    private static final Pattern DIGIT_RUN = Pattern.compile("\\d{9,}");

    private FreeText() {
    }

    static void noPhoneNumbers(String text, String what) {
        if (text != null && DIGIT_RUN.matcher(text.replaceAll("[\\s+\\-()]", "")).find()) {
            throw new FinanceProblem(HttpStatus.BAD_REQUEST, "no-phone-numbers", "Do not type phone numbers into " + what + ": it is kept for good and names no one");
        }
    }
}
