package com.oriah.order.service;

import feign.FeignException;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

/**
 * Reads the problem type out of another service's refusal (problem+json under {@code https://oriah.dev/problems/<code>}). The code is what
 * {@link CorrectionReplies} judges: an answer with no readable type has no code (the empty string), and proves nothing by its name.
 */
final class InventoryProblems {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PREFIX = "https://oriah.dev/problems/";

    private InventoryProblems() {
    }

    /** The problem's code without its prefix; empty when the answer has no body, is not JSON, or names no type of ours. */
    static String code(FeignException e) {
        Object type = body(e).get("type");
        return type instanceof String t && t.startsWith(PREFIX) ? t.substring(PREFIX.length()) : "";
    }

    /** The problem's own words; null when it has none. */
    static String detail(FeignException e) {
        return body(e).get("detail") instanceof String d ? d : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> body(FeignException e) {
        try {
            String body = e.contentUTF8();
            return body == null || body.isBlank() ? Map.of() : JSON.readValue(body, Map.class);
        } catch (RuntimeException unreadable) {
            return Map.of();
        }
    }
}
