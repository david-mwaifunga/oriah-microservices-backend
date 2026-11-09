package com.oriah.events;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A consumer's declaration: its name, the event types it applies (with the
 * payload versions it can read) and the types it deliberately skips. Every
 * other type is refused when it arrives, so a declaration mistake is a
 * startup error, not a silently dropped event.
 */
class InboxConsumerTest {

    @Test
    @DisplayName("a name of lower-case letters, digits and dashes, up to 64 characters, is accepted")
    void goodName() {
        assertThat(InboxConsumer.named("search-index-2").name()).isEqualTo("search-index-2");
        assertThat(InboxConsumer.named("a".repeat(64)).name()).hasSize(64);
    }

    @ParameterizedTest(name = "\"{0}\"")
    @NullAndEmptySource
    @ValueSource(strings = {"Search", "search index", "search.index", "search_index", "search/index"})
    @DisplayName("any other name is refused: it is stored in processed_event and names the consumer's replay topic")
    void badName(String name) {
        assertThatThrownBy(() -> InboxConsumer.named(name))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("consumer name");
    }

    @Test
    @DisplayName("a name longer than 64 characters is refused: its column holds 64")
    void nameTooLong() {
        assertThatThrownBy(() -> InboxConsumer.named("a".repeat(65)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("consumer name");
    }

    @Test
    @DisplayName("a handled type must name at least one payload version it can read")
    void handledTypeNeedsAVersion() {
        InboxConsumer consumer = InboxConsumer.named("search");

        assertThatThrownBy(() -> consumer.handles("product.updated"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("product.updated");
        assertThatThrownBy(() -> consumer.handles("product.updated", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("product.updated");
    }

    @Test
    @DisplayName("an event type is never blank")
    void blankTypeRefused() {
        InboxConsumer consumer = InboxConsumer.named("search");

        assertThatThrownBy(() -> consumer.handles(" ", 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> consumer.ignores("")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("one type cannot be both handled and ignored, whichever is declared first")
    void handledAndIgnoredRefused() {
        assertThatThrownBy(() -> InboxConsumer.named("search").handles("product.updated", 1).ignores("product.updated"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("product.updated");
        assertThatThrownBy(() -> InboxConsumer.named("search").ignores("product.updated").handles("product.updated", 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("product.updated");
    }
}
