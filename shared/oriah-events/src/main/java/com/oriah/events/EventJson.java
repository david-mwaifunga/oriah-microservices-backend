package com.oriah.events;

import com.fasterxml.jackson.annotation.JsonInclude;

import tools.jackson.core.StreamWriteFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The one JSON mapper every outbox payload is written with.
 *
 * <p>The payload is written once, stored verbatim (a {@code json} column,
 * not {@code jsonb}) and published byte for byte, so this mapper alone
 * decides the wire format. It reproduces what today's producers send
 * through Spring Cloud Stream (Jackson 3, the same generation):
 * <ul>
 *   <li>a null field is written as {@code null}, not left out -- a
 *       consumer reading {@code previousStatus} must see it;</li>
 *   <li>an amount is written plainly with its scale -- {@code 150.00},
 *       never {@code 150} or {@code 1.5E+2};</li>
 *   <li>a moment is an ISO-8601 instant string, never a number.</li>
 * </ul>
 *
 * <p>Deliberately not a Spring bean: services already have Spring's own
 * mapper bean, and a second one would be ambiguous to inject. Consumers and
 * golden tests read the same rules through {@link #mapper()}.
 */
public final class EventJson {

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .changeDefaultPropertyInclusion(inclusion -> inclusion
                    .withValueInclusion(JsonInclude.Include.ALWAYS)
                    .withContentInclusion(JsonInclude.Include.ALWAYS))
            .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
            .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
            // As Spring Cloud Stream's own mapper: a payload with no
            // properties is written as {} rather than refused.
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
            .build();

    private EventJson() {
    }

    public static JsonMapper mapper() {
        return MAPPER;
    }

    /** @return the payload as the JSON text that is stored and published */
    public static String write(Object payload) {
        return MAPPER.writeValueAsString(payload);
    }
}
