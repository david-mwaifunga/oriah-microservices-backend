package com.oriah.events;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHeaders;
import org.springframework.util.MimeType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A replay request, and the continuation a producer sends itself to answer
 * a long replay in steps.
 *
 * <p>Why steps: one delivery of a request used to send the whole history at
 * once, so a long enough history would outlast the time Kafka allows a
 * listener between two polls, and Kafka would hand the request to another
 * instance mid-answer, for ever. Now one delivery answers one bounded step
 * and ends with a continuation -- the same request plus where H is
 * ({@code throughOutboxId}) and the last row sent ({@code afterOutboxId}) --
 * which the producer reads back from the same topic as its next step.
 */
class ReplayRequestTest {

    private static final UUID REQUEST = UUID.fromString("5f0c2b6e-3a1d-4c8e-9b7a-2d4e6f8a0b1c");

    @Test
    @DisplayName("a consumer's request has neither continuation field; its step is the request itself")
    void consumersRequest() {
        ReplayRequest request = new ReplayRequest(REQUEST, "order", "finance", 1);

        assertThat(request.continuation()).isFalse();
        assertThat(request.throughOutboxId()).isNull();
        assertThat(request.afterOutboxId()).isNull();
        assertThat(request.stepId()).isEqualTo(REQUEST);
    }

    @Test
    @DisplayName("a continuation's step id is named after the request and the position it continues from: the "
            + "same continuation delivered twice has the same id, the next one another, and none is ever a "
            + "request id")
    void continuationStepId() {
        ReplayRequest after10 = new ReplayRequest(REQUEST, "order", "finance", 1, 35L, 10L);
        ReplayRequest after10Again = new ReplayRequest(REQUEST, "order", "finance", 1, 35L, 10L);
        ReplayRequest after20 = new ReplayRequest(REQUEST, "order", "finance", 1, 35L, 20L);
        ReplayRequest otherRequest = new ReplayRequest(UUID.randomUUID(), "order", "finance", 1, 35L, 10L);

        assertThat(after10.continuation()).isTrue();
        assertThat(after10.stepId()).isEqualTo(after10Again.stepId())
                .isNotEqualTo(after20.stepId())
                .isNotEqualTo(otherRequest.stepId())
                .isNotEqualTo(REQUEST);
        // Name-based (version 3); a request id is random (version 4), so the two can never be equal.
        assertThat(after10.stepId().version()).isEqualTo(3);
    }

    @Test
    @DisplayName("throughOutboxId and afterOutboxId come together: one without the other is refused")
    void bothOrNeither() {
        assertThatThrownBy(() -> new ReplayRequest(REQUEST, "order", "finance", 1, 35L, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("throughOutboxId and afterOutboxId come together");
        assertThatThrownBy(() -> new ReplayRequest(REQUEST, "order", "finance", 1, null, 10L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("throughOutboxId and afterOutboxId come together");
    }

    @Test
    @DisplayName("afterOutboxId below fromOutboxId - 1 is refused: it would send rows before the ones asked for")
    void afterNotBeforeTheStart() {
        assertThatThrownBy(() -> new ReplayRequest(REQUEST, "order", "finance", 5, 35L, 3L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("afterOutboxId is fromOutboxId - 1 or more")
                .hasMessageContaining("was 3");
        // The edge: nothing sent yet.
        assertThat(new ReplayRequest(REQUEST, "order", "finance", 5, 35L, 4L).afterOutboxId()).isEqualTo(4L);
    }

    @Test
    @DisplayName("throughOutboxId below afterOutboxId is refused: H is where the replay ends")
    void throughNotBeforeAfter() {
        assertThatThrownBy(() -> new ReplayRequest(REQUEST, "order", "finance", 1, 9L, 10L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("throughOutboxId is afterOutboxId or more")
                .hasMessageContaining("was 9");
        // The edge: every row through H already sent.
        assertThat(new ReplayRequest(REQUEST, "order", "finance", 1, 10L, 10L).throughOutboxId()).isEqualTo(10L);
    }

    @Test
    @DisplayName("on the wire a consumer's request is the four fields it always was, JSON, keyed by its request id "
            + "so every step of one replay lands on one partition of the requests topic, in order")
    void consumersRequestOnTheWire() {
        Message<byte[]> message = new ReplayRequest(REQUEST, "order", "finance", 1).message();

        Map<String, Object> body = body(message);
        assertThat(body).containsOnlyKeys("requestId", "producer", "consumer", "fromOutboxId");
        assertThat(body.get("requestId")).isEqualTo(REQUEST.toString());
        assertThat(body.get("producer")).isEqualTo("order");
        assertThat(body.get("consumer")).isEqualTo("finance");
        assertThat(((Number) body.get("fromOutboxId")).longValue()).isEqualTo(1L);
        assertKeyedAndJson(message);
    }

    @Test
    @DisplayName("on the wire a continuation carries H and the last row sent too, under the same key")
    void continuationOnTheWire() {
        Message<byte[]> message = new ReplayRequest(REQUEST, "order", "finance", 1, 35L, 10L).message();

        Map<String, Object> body = body(message);
        assertThat(body).containsOnlyKeys("requestId", "producer", "consumer", "fromOutboxId", "throughOutboxId",
                "afterOutboxId");
        assertThat(((Number) body.get("fromOutboxId")).longValue()).isEqualTo(1L);
        assertThat(((Number) body.get("throughOutboxId")).longValue()).isEqualTo(35L);
        assertThat(((Number) body.get("afterOutboxId")).longValue()).isEqualTo(10L);
        assertKeyedAndJson(message);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> body(Message<byte[]> message) {
        return EventJson.mapper().readValue(message.getPayload(), Map.class);
    }

    private static void assertKeyedAndJson(Message<byte[]> message) {
        assertThat((byte[]) message.getHeaders().get(EventHeaders.KAFKA_KEY)).as("the Kafka key")
                .isEqualTo(REQUEST.toString().getBytes(StandardCharsets.UTF_8));
        assertThat(MimeType.valueOf(String.valueOf(message.getHeaders().get(MessageHeaders.CONTENT_TYPE))))
                .isEqualTo(MimeType.valueOf("application/json"));
    }
}
