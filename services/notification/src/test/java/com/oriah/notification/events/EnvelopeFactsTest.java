package com.oriah.notification.events;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;

import com.oriah.events.EventHeaders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The envelope of a relayed event, read once and in one place: which event it is, what it is about, where it stands in that thing's history, when it
 * happened and when its producer wrote it down. Every binder carries a header as text; Kafka hands it over as bytes. Both read the same.
 *
 * <p>A message is only as late as its producer's record of it says ({@code recorded-at}), so a header that is missing or garbled is refused by name
 * rather than guessed: a guess here would decide whether a customer is emailed.
 */
class EnvelopeFactsTest {

    private static final UUID EVENT = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID ORDER = UUID.fromString("8a1f4b2e-3c5d-4e6f-9a0b-1c2d3e4f5a6b");

    @Test
    @DisplayName("reads every header it needs when the binder hands them over as text")
    void readsEveryHeaderAsText() {
        EnvelopeFacts facts = EnvelopeFacts.from(asText().build());

        assertThat(facts.eventId()).isEqualTo(EVENT);
        assertThat(facts.eventType()).isEqualTo("order.paid");
        assertThat(facts.aggregateId()).isEqualTo(ORDER);
        assertThat(facts.seq()).isEqualTo(7L);
        assertThat(facts.occurredAt()).isEqualTo(Instant.parse("2026-11-09T07:30:00Z"));
        assertThat(facts.recordedAt()).isEqualTo(Instant.parse("2026-11-09T07:30:00.125Z"));
    }

    @Test
    @DisplayName("reads the same from bytes, which is how Kafka hands a header over")
    void readsEveryHeaderAsBytes() {
        Message<byte[]> message = MessageBuilder.withPayload(new byte[0])
                .setHeader(EventHeaders.EVENT_ID, bytes(EVENT.toString()))
                .setHeader(EventHeaders.EVENT_TYPE, bytes("order.paid"))
                .setHeader(EventHeaders.AGGREGATE_ID, bytes(ORDER.toString()))
                .setHeader(EventHeaders.SEQ, bytes("7"))
                .setHeader(EventHeaders.OCCURRED_AT, bytes("2026-11-09T07:30:00Z"))
                .setHeader(EventHeaders.RECORDED_AT, bytes("2026-11-09T07:30:00.125Z"))
                .build();

        assertThat(EnvelopeFacts.from(message)).isEqualTo(EnvelopeFacts.from(asText().build()));
    }

    @Test
    @DisplayName("a message with no recorded-at is refused, and the refusal names the header")
    void aMissingRecordedAtIsRefusedByName() {
        Message<byte[]> message = asText().removeHeader(EventHeaders.RECORDED_AT).build();

        assertThatThrownBy(() -> EnvelopeFacts.from(message))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("recorded-at");
    }

    @Test
    @DisplayName("a seq that is not a number is refused, and the refusal names the header")
    void aGarbledSeqIsRefusedByName() {
        Message<byte[]> message = asText().setHeader(EventHeaders.SEQ, "seven").build();

        assertThatThrownBy(() -> EnvelopeFacts.from(message))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("seq");
    }

    @Test
    @DisplayName("an aggregate id that is not an id, or a time that is not a time, is refused by name too")
    void aGarbledIdOrTimeIsRefusedByName() {
        assertThatThrownBy(() -> EnvelopeFacts.from(asText().setHeader(EventHeaders.AGGREGATE_ID, "order-7").build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("aggregate-id");
        assertThatThrownBy(() -> EnvelopeFacts.from(asText().setHeader(EventHeaders.OCCURRED_AT, "yesterday").build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("occurred-at");
        assertThatThrownBy(() -> EnvelopeFacts.from(asText().setHeader(EventHeaders.EVENT_TYPE, " ").build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("event-type");
    }

    @Test
    @DisplayName("a refusal never repeats what the header held: only its name")
    void aRefusalNamesTheHeaderNotItsValue() {
        assertThatThrownBy(() -> EnvelopeFacts.from(asText().setHeader(EventHeaders.SEQ, "someone@example.com").build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("someone@example.com");
    }

    private static MessageBuilder<byte[]> asText() {
        return MessageBuilder.withPayload(new byte[0])
                .setHeader(EventHeaders.EVENT_ID, EVENT.toString())
                .setHeader(EventHeaders.EVENT_TYPE, "order.paid")
                .setHeader(EventHeaders.AGGREGATE_ID, ORDER.toString())
                .setHeader(EventHeaders.SEQ, "7")
                .setHeader(EventHeaders.OCCURRED_AT, "2026-11-09T07:30:00Z")
                .setHeader(EventHeaders.RECORDED_AT, "2026-11-09T07:30:00.125Z");
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
