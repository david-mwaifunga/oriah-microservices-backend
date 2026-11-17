package com.oriah.payment.provider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What each of ZynlePay's response codes means to Oriah.
 *
 * <p>The meanings are known only second-hand until the merchant account exists, so every list is a setting. What is NOT a setting is the rule that
 * keeps money safe: <strong>a code nobody listed is never a failure and never money</strong>. At the push an unlisted code is {@link PushAnswer#UNKNOWN}
 * (the attempt stays pending and is asked about); at the status call it is {@link StatusAnswer#UNREADABLE} (nothing changes, it is asked again).
 *
 * <p>An entry is a code ({@code 120}) or an inclusive range ({@code 9900-9915}). One code cannot be in two lists.
 */
public final class ZynlePayCodes {

    /** What the answer to "collect this" means. Only {@code REFUSED} ends an attempt. */
    public enum PushAnswer { INITIATED, REFUSED, CREDENTIALS, AMBIGUOUS, UNKNOWN }

    /** What the answer to "what became of it" means. Only {@code PAID} is money; only {@code FAILED} ends an attempt. */
    public enum StatusAnswer { PAID, PENDING, FAILED, NOT_FOUND, CREDENTIALS, UNREADABLE }

    private record Range(int from, int to, String list, String entry) {
        boolean holds(int code) {
            return code >= from && code <= to;
        }
    }

    private final Map<String, List<Range>> lists = new LinkedHashMap<>();

    public ZynlePayCodes(List<String> initiated, List<String> paid, List<String> pending, List<String> failed, List<String> refused,
                         List<String> credentials, List<String> ambiguous, List<String> notFound) {
        add("initiated", initiated, true);
        add("paid", paid, true);
        add("pending", pending, false);
        add("failed", failed, true);
        add("refused", refused, false);
        add("credentials", credentials, false);
        add("ambiguous", ambiguous, false);
        add("not-found", notFound, false);
    }

    public PushAnswer push(String code) {
        return switch (listOf(code)) {
            case "initiated" -> PushAnswer.INITIATED;
            case "refused" -> PushAnswer.REFUSED;
            case "credentials" -> PushAnswer.CREDENTIALS;
            case "ambiguous" -> PushAnswer.AMBIGUOUS;
            default -> PushAnswer.UNKNOWN;
        };
    }

    public StatusAnswer status(String code) {
        return switch (listOf(code)) {
            case "paid" -> StatusAnswer.PAID;
            // "initiated" from the status call says what the push said: it has begun, and no more is known.
            case "pending", "initiated" -> StatusAnswer.PENDING;
            case "failed" -> StatusAnswer.FAILED;
            case "not-found" -> StatusAnswer.NOT_FOUND;
            case "credentials" -> StatusAnswer.CREDENTIALS;
            default -> StatusAnswer.UNREADABLE;
        };
    }

    /** The list a code is in, or the empty string. */
    private String listOf(String code) {
        Integer number = number(code);
        if (number == null) {
            return "";
        }
        for (List<Range> ranges : lists.values()) {
            for (Range range : ranges) {
                if (range.holds(number)) {
                    return range.list();
                }
            }
        }
        return "";
    }

    private static Integer number(String code) {
        if (code == null) {
            return null;
        }
        String digits = code.strip();
        if (digits.isEmpty() || digits.length() > 8 || !digits.chars().allMatch(c -> c >= '0' && c <= '9')) {
            return null;
        }
        return Integer.valueOf(digits);
    }

    private void add(String list, List<String> entries, boolean required) {
        List<Range> ranges = new ArrayList<>();
        for (String entry : entries == null ? List.<String>of() : entries) {
            Range range = range(list, entry);
            for (List<Range> others : lists.values()) {
                for (Range other : others) {
                    if (range.from() <= other.to() && other.from() <= range.to()) {
                        throw new IllegalArgumentException("ZynlePay code " + entry + " is listed as " + list + " and " + other.entry() + " as " + other.list()
                                + ": one code cannot mean two things");
                    }
                }
            }
            ranges.add(range);
        }
        if (required && ranges.isEmpty()) {
            throw new IllegalArgumentException("ZynlePay's '" + list + "' code is not set: money rests on it");
        }
        lists.put(list, ranges);
    }

    private static Range range(String list, String entry) {
        String text = entry == null ? "" : entry.strip();
        int dash = text.indexOf('-');
        Integer from = number(dash < 0 ? text : text.substring(0, dash));
        Integer to = number(dash < 0 ? text : text.substring(dash + 1));
        if (from == null || to == null || from > to) {
            throw new IllegalArgumentException("ZynlePay code '" + entry + "' (" + list + ") is not a code or a range of codes such as 120 or 9900-9915");
        }
        return new Range(from, to, list, text);
    }
}
