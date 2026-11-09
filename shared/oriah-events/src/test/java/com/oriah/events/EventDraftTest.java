package com.oriah.events;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * An event is refused before it reaches the database when it is missing
 * something the log must always carry: when it happened, who did it, and
 * what it says. The column sizes are checked here too, so an over-long id
 * fails with a sentence rather than a Postgres error.
 */
class EventDraftTest {

    private static final Instant WHEN = Instant.parse("2026-11-09T07:30:00Z");

    @Test
    @DisplayName("a complete draft is accepted as given")
    void completeDraft() {
        EventDraft draft = draft("order", "o-1", "order.created", 1, "oriah.order", WHEN, "user:abc", Map.of());

        assertThat(draft.aggregateType()).isEqualTo("order");
        assertThat(draft.occurredAt()).isEqualTo(WHEN);
        assertThat(draft.actor()).isEqualTo("user:abc");
    }

    @Test
    @DisplayName("occurredAt is required: the log never guesses when a fact happened")
    void occurredAtRequired() {
        assertRefused(() -> draft("order", "o-1", "order.created", 1, "oriah.order", null, "user:abc", Map.of()),
                "occurredAt");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "bob", "user:", "system:", "admin:root", "User:abc"})
    @DisplayName("actor is required and is 'user:<subject>' or 'system:<name>'")
    void actorShape(String actor) {
        assertRefused(() -> draft("order", "o-1", "order.created", 1, "oriah.order", WHEN, actor, Map.of()),
                "actor");
    }

    @Test
    @DisplayName("both actor kinds are accepted")
    void actorKinds() {
        draft("order", "o-1", "order.created", 1, "oriah.order", WHEN, "user:9f1c-subject", Map.of());
        draft("order", "o-1", "order.created", 1, "oriah.order", WHEN, "system:payment-reconciler", Map.of());
    }

    @Test
    @DisplayName("a payload is required")
    void payloadRequired() {
        assertRefused(() -> draft("order", "o-1", "order.created", 1, "oriah.order", WHEN, "user:abc", null),
                "payload");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    @DisplayName("schemaVersion starts at 1")
    void schemaVersionPositive(int version) {
        assertRefused(() -> draft("order", "o-1", "order.created", version, "oriah.order", WHEN, "user:abc", Map.of()),
                "schemaVersion");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " ")
    @DisplayName("aggregate type, aggregate id, event type and topic must each be present")
    void namesRequired(String blank) {
        assertRefused(() -> draft(blank, "o-1", "order.created", 1, "oriah.order", WHEN, "user:abc", Map.of()),
                "aggregateType");
        assertRefused(() -> draft("order", blank, "order.created", 1, "oriah.order", WHEN, "user:abc", Map.of()),
                "aggregateId");
        assertRefused(() -> draft("order", "o-1", blank, 1, "oriah.order", WHEN, "user:abc", Map.of()),
                "eventType");
        assertRefused(() -> draft("order", "o-1", "order.created", 1, blank, WHEN, "user:abc", Map.of()),
                "topic");
    }

    @Test
    @DisplayName("each name must fit its column: 64 for the aggregate, 128 for event type, topic and actor")
    void namesFitTheirColumns() {
        String s64 = "a".repeat(64);
        String s65 = "a".repeat(65);
        String s128 = "a".repeat(128);
        String s129 = "a".repeat(129);
        draft(s64, s64, s128, 1, s128, WHEN, "user:" + "a".repeat(123), Map.of());

        assertRefused(() -> draft(s65, "o-1", "order.created", 1, "oriah.order", WHEN, "user:abc", Map.of()),
                "aggregateType");
        assertRefused(() -> draft("order", s65, "order.created", 1, "oriah.order", WHEN, "user:abc", Map.of()),
                "aggregateId");
        assertRefused(() -> draft("order", "o-1", s129, 1, "oriah.order", WHEN, "user:abc", Map.of()),
                "eventType");
        assertRefused(() -> draft("order", "o-1", "order.created", 1, s129, WHEN, "user:abc", Map.of()),
                "topic");
        assertRefused(() -> draft("order", "o-1", "order.created", 1, "oriah.order", WHEN,
                "user:" + "a".repeat(124), Map.of()), "actor");
    }

    private static EventDraft draft(String aggregateType, String aggregateId, String eventType, int schemaVersion,
                                    String topic, Instant occurredAt, String actor, Object payload) {
        return new EventDraft(aggregateType, aggregateId, eventType, schemaVersion, topic, occurredAt, actor, payload);
    }

    private static void assertRefused(Runnable construction, String field) {
        assertThatThrownBy(construction::run)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(field);
    }
}
