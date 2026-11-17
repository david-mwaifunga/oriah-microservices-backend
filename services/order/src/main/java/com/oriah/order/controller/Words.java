package com.oriah.order.controller;

import com.oriah.order.exception.SalesProblem;
import org.springframework.http.HttpStatus;

/**
 * The length of words a person typed, counted the way the database counts them: in characters (code points), not in Java's UTF-16 units. An emoji is
 * one character to Postgres' {@code char_length} and two units to {@code String.length()}, so a note of three emoji passes a {@code @Size(min = 5)}
 * and then breaks the column's CHECK, and a note of 113 characters with many emoji fails a {@code @Size(max = 200)} the column would take. So a
 * note carries no {@code @Size}: this is its one length rule, a plain 400 either way.
 */
final class Words {

    private Words() {
    }

    /**
     * @param text  the words, already trimmed; null is "none" and passes (whether they are required is the caller's rule)
     * @param field what they are, for the message
     * @throws SalesProblem 400 {@code invalid-note} when they are not {@code min} to {@code max} characters
     */
    static void requireLength(String text, int min, int max, String field) {
        if (text == null) {
            return;
        }
        int characters = text.codePointCount(0, text.length());
        if (characters < min || characters > max) {
            throw new SalesProblem(HttpStatus.BAD_REQUEST, "invalid-note", "the " + field + " must be " + min + " to " + max + " characters");
        }
    }
}
