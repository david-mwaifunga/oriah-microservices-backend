package com.oriah.events;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The module's own mapper writes every outbox payload. Three rules carry
 * today's wire format forward: a null field is written (a consumer reading
 * previousStatus must see null, not a missing key); an amount is written
 * plainly with its scale (150.00, never 150 or 1.5E+2); a moment is an
 * ISO-8601 instant, never a number.
 *
 * <p>Golden tests against JSON captured from today's producers come with
 * each producer's adoption; this pins the rules themselves.
 */
class EventJsonTest {

    /** Fields named so declaration order and alphabetical order agree. */
    record Sample(BigDecimal amount, Instant at, String note) {
    }

    @Test
    @DisplayName("nulls are written, amounts keep their scale, instants are ISO-8601")
    void wireFormat() {
        String json = EventJson.write(new Sample(new BigDecimal("150.00"), Instant.parse("2026-11-09T07:30:00Z"), null));

        assertThat(json).isEqualTo("{\"amount\":150.00,\"at\":\"2026-11-09T07:30:00Z\",\"note\":null}");
    }

    @Test
    @DisplayName("an amount is never written in exponent form, whatever its scale")
    void bigDecimalPlain() {
        assertThat(EventJson.write(new Sample(new BigDecimal("1E+3"), null, null)))
                .startsWith("{\"amount\":1000,");
        assertThat(EventJson.write(new Sample(new BigDecimal("0.10"), null, null)))
                .startsWith("{\"amount\":0.10,");
        assertThat(EventJson.write(new Sample(new BigDecimal("0.0000001"), null, null)))
                .startsWith("{\"amount\":0.0000001,");
    }

    @Test
    @DisplayName("an instant keeps its fraction of a second")
    void instantFraction() {
        assertThat(EventJson.write(new Sample(null, Instant.parse("2026-11-09T07:30:00.123456Z"), "x")))
                .isEqualTo("{\"amount\":null,\"at\":\"2026-11-09T07:30:00.123456Z\",\"note\":\"x\"}");
    }
}
