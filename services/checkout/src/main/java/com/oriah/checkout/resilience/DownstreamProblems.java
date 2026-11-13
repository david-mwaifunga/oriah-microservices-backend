package com.oriah.checkout.resilience;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import feign.FeignException;
import tools.jackson.databind.json.JsonMapper;

/**
 * What a downstream service said when it refused: the problem+json body of the answer, read for the fields a shopper can act on (the type, the detail and
 * any extra properties such as {@code shortages}). Used to pass a refusal on under its OWN name -- inventory's {@code insufficient-stock} naming every short
 * line, shipping's {@code town-not-offered} -- rather than flattening it into a generic 500.
 */
public final class DownstreamProblems {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PREFIX = "https://oriah.dev/problems/";
    /** The members every problem+json has: not repeated as extras. */
    private static final List<String> STANDARD = List.of("type", "title", "status", "detail", "instance", "traceId", "timestamp");

    /** A refusal as the downstream gave it. */
    public record Problem(String code, String detail, Map<String, Object> extras) {
    }

    private DownstreamProblems() {
    }

    /** The problem the downstream answered with, if the failure is an answer with a readable problem+json body of ours. */
    @SuppressWarnings("unchecked")
    public static Optional<Problem> of(Throwable failure) {
        for (Throwable c = failure; c != null; c = c.getCause()) {
            if (c instanceof FeignException fe) {
                return fe.responseBody().map(b -> StandardCharsets.UTF_8.decode(b.duplicate()).toString()).flatMap(body -> {
                    try {
                        Map<String, Object> read = JSON.readValue(body, Map.class);
                        String type = read.get("type") instanceof String t ? t : "";
                        if (!type.startsWith(PREFIX)) {
                            return Optional.empty();
                        }
                        Map<String, Object> extras = new LinkedHashMap<>();
                        read.forEach((k, v) -> {
                            if (!STANDARD.contains(k)) {
                                extras.put(k, v);
                            }
                        });
                        String detail = read.get("detail") instanceof String d ? d : null;
                        return Optional.of(new Problem(type.substring(PREFIX.length()), detail, extras));
                    } catch (RuntimeException unreadable) {
                        return Optional.empty();
                    }
                });
            }
            if (c.getCause() == c) {
                break;
            }
        }
        return Optional.empty();
    }
}
