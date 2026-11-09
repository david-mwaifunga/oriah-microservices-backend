package com.oriah.events;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A consumer's declaration: its name, the event types it applies (with the
 * aggregate each is about and the payload versions it can read) and the
 * types it deliberately skips. Every other type is refused when it arrives,
 * so a declaration mistake is a startup error, not a silently dropped event.
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

        assertThatThrownBy(() -> consumer.handles("product", "product.updated"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("product.updated");
        assertThatThrownBy(() -> consumer.handles("product", "product.updated", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("product.updated");
    }

    @Test
    @DisplayName("an event type is never blank")
    void blankTypeRefused() {
        InboxConsumer consumer = InboxConsumer.named("search");

        assertThatThrownBy(() -> consumer.handles("product", " ", 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> consumer.ignores("")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("one type cannot be both handled and ignored, whichever is declared first")
    void handledAndIgnoredRefused() {
        assertThatThrownBy(() -> InboxConsumer.named("search")
                .handles("product", "product.updated", 1).ignores("product.updated"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("product.updated");
        assertThatThrownBy(() -> InboxConsumer.named("search")
                .ignores("product.updated").handles("product", "product.updated", 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("product.updated");
    }

    @ParameterizedTest(name = "\"{0}\"")
    @NullAndEmptySource
    @ValueSource(strings = " ")
    @DisplayName("a handled type names the aggregate its events are about; a missing or blank one is refused, "
            + "by the same rule the outbox applies when the event is written")
    void aggregateTypeRequired(String aggregateType) {
        assertThatThrownBy(() -> InboxConsumer.named("search").handles(aggregateType, "product.updated", 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("product.updated")
                .hasMessageContaining("aggregateType is required");
    }

    @Test
    @DisplayName("the aggregate type fits its column, as in the outbox: 64 characters are accepted, 65 refused")
    void aggregateTypeFitsItsColumn() {
        InboxConsumer.named("search").handles("p".repeat(64), "product.updated", 1);

        assertThatThrownBy(() -> InboxConsumer.named("search").handles("p".repeat(65), "product.updated", 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("product.updated")
                .hasMessageContaining("its column holds 64");
    }

    @Test
    @DisplayName("one event type is about one aggregate: declaring it again for a different aggregate is refused")
    void oneAggregatePerType() {
        InboxConsumer search = InboxConsumer.named("search").handles("product", "product.updated", 1);

        assertThatThrownBy(() -> search.handles("variant", "product.updated", 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("product.updated")
                .hasMessageContaining("'product'")
                .hasMessageContaining("'variant'");
    }

    @Test
    @DisplayName("declaring a type again for the same aggregate adds payload versions, as before")
    void sameAggregateAddsVersions() {
        InboxConsumer search = InboxConsumer.named("search")
                .handles("product", "product.updated", 1)
                .handles("product", "product.updated", 2);

        assertThat(search.applies(envelope("product.updated", "product", 1))).isTrue();
        assertThat(search.applies(envelope("product.updated", "product", 2))).isTrue();
    }

    private static Envelope envelope(String eventType, String aggregateType, int schemaVersion) {
        return new Envelope(UUID.randomUUID(), eventType, aggregateType, "p-1", 1, schemaVersion);
    }
}
