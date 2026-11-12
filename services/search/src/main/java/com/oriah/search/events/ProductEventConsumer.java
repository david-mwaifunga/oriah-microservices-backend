package com.oriah.search.events;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Consumer;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oriah.events.EventHeaders;
import com.oriah.events.Inbox;
import com.oriah.events.InboxConsumer;
import com.oriah.search.model.SearchIndexEntry;
import com.oriah.search.repository.SearchIndexRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Mirrors catalog's product view into the local search_index. Same
 * pattern as inventory's ProductEventConsumer — single bean, header
 * dispatch, upsert-then-the-DB-generated-tsvector-takes-care-of-the-rest.
 *
 * <h2>Only the newest snapshot counts</h2>
 *
 * The index is a PROJECTION: a copy of catalog's products, and each product
 * event carries the whole product as it then was, numbered per product by
 * seq. So each event goes through {@link Inbox#onceInOrder}: one already
 * applied (Kafka redelivers) is skipped, and so is one older than the newest
 * already applied to that product. The newest seq per product is kept in
 * processed_aggregate, in the same transaction as the index change, and is
 * never deleted -- so a product deleted at seq 3 stays deleted when its
 * seq 2 update turns up late, even after a restart.
 *
 * <h2>Nothing is swallowed</h2>
 *
 * An event that cannot be applied -- a type or payload version not declared
 * below, an aggregate-type other than {@code product}, a missing or garbled
 * envelope header, a body that breaks the contract -- throws. The transaction
 * rolls back with its record, and the oriah-events module publishes it to
 * {@code catalog.product.dlq.search}: on its first delivery for a refusal like
 * these, which would fail the same way again, and after three deliveries for
 * anything else, such as the database being away (application.yaml). It is
 * not committed until that topic has it. Logging and carrying on, as this
 * consumer once did, lost such an event for good.
 */
@Configuration
@Slf4j
public class ProductEventConsumer {

    /**
     * What every product event is about: a product, spelled exactly as catalog
     * writes it into its outbox (ProductService.AGGREGATE, lower case). The
     * Inbox keeps the newest seq per aggregate type and id, so an event that
     * says it is about something else is refused before anything is
     * recorded: applied, its seq would be filed under another history, and a
     * later, older snapshot of this product would look new and overwrite it.
     */
    private static final String PRODUCT = "product";

    /**
     * Every type catalog publishes on catalog.product, all handled: each is a
     * full snapshot the index needs (a deletion being the snapshot "gone").
     * Nothing is ignored. Payload version 1 is catalog's ProductCreatedEvent,
     * ProductUpdatedEvent and ProductDeletedEvent as they are today.
     */
    static final InboxConsumer SEARCH = InboxConsumer.named("search")
            .handles(PRODUCT, "product.created", 1)
            .handles(PRODUCT, "product.updated", 1)
            .handles(PRODUCT, "product.deleted", 1);

    @Bean
    public Consumer<Message<byte[]>> productEvents(
            Inbox inbox,
            SearchIndexRepository repository,
            ObjectMapper objectMapper) {
        return new Handler(inbox, repository, objectMapper);
    }

    static class Handler implements Consumer<Message<byte[]>> {
        private final Inbox inbox;
        private final SearchIndexRepository repository;
        private final ObjectMapper objectMapper;

        Handler(Inbox inbox, SearchIndexRepository repository, ObjectMapper objectMapper) {
            this.inbox = inbox;
            this.repository = repository;
            this.objectMapper = objectMapper;
        }

        @Override
        @Transactional
        public void accept(Message<byte[]> message) {
            inbox.onceInOrder(SEARCH, message.getHeaders(), () -> apply(message));
        }

        /** Runs only for a handled snapshot newer than any applied to its product; the Inbox checked the headers. */
        private void apply(Message<byte[]> message) {
            String type = header(message, EventHeaders.EVENT_TYPE);
            JsonNode payload = read(message.getPayload());
            UUID id = productId(message, payload);
            switch (type) {
                case "product.created", "product.updated" -> upsert(id, payload);
                case "product.deleted" -> {
                    repository.deleteById(id);
                    ProductEventConsumer.log.info("Removed search_index row id={}", id);
                }
                // Unreachable while SEARCH and this switch list the same types.
                default -> throw new IllegalStateException("no handling for declared event type " + type);
            }
        }

        /**
         * The product the snapshot is of -- which must be the product the
         * envelope names. The seq that orders snapshots is counted per
         * aggregate-id, so a body about another product would be ordered by
         * the wrong product's seq; it is refused instead.
         */
        private static UUID productId(Message<byte[]> message, JsonNode payload) {
            UUID id = UUID.fromString(payload.path("id").asText());
            String aggregateId = header(message, EventHeaders.AGGREGATE_ID);
            if (!id.toString().equals(aggregateId)) {
                throw new IllegalArgumentException("snapshot of product " + id
                        + " arrived in an envelope for product " + aggregateId);
            }
            return id;
        }

        private JsonNode read(byte[] body) {
            try {
                return objectMapper.readTree(body);
            } catch (IOException e) {
                throw new UncheckedIOException("product event body is not JSON", e);
            }
        }

        /** A header as text: a String as the binder hands it over, or raw UTF-8 bytes as Kafka carries it. */
        private static String header(Message<?> message, String name) {
            Object value = message.getHeaders().get(name);
            return value instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : String.valueOf(value);
        }

        private void upsert(UUID id, JsonNode payload) {
            String title = payload.path("title").asText("");
            String description = payload.path("description").asText(null);
            String tags = tagsToString(payload.path("tags"));
            // Whether a search may return it. A snapshot is the whole product, so
            // this is decided afresh by every one: publishing lists a product,
            // withdrawing it takes it out, and packaging never gets in. An absent
            // kind is a snapshot from before Phase 3 (see isListable).
            boolean listed = SearchIndexEntry.isListable(
                    payload.path("status").asText(null), payload.path("kind").asText(null));
            repository.findById(id).ifPresentOrElse(
                    existing -> {
                        existing.replace(title, description, tags, listed);
                        ProductEventConsumer.log.info("Updated search_index id={} title=\"{}\" listed={}", id, title, listed);
                    },
                    () -> {
                        repository.save(new SearchIndexEntry(id, title, description, tags, listed));
                        ProductEventConsumer.log.info("Indexed search_index id={} title=\"{}\" listed={}", id, title, listed);
                    }
            );
        }

        private String tagsToString(JsonNode tags) {
            if (tags == null || !tags.isArray()) return "";
            var sb = new StringBuilder();
            tags.forEach(t -> { sb.append(t.asText()); sb.append(' '); });
            return sb.toString().trim();
        }
    }
}
