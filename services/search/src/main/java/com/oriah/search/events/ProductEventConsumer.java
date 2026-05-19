package com.oriah.search.events;

import java.util.UUID;
import java.util.function.Consumer;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oriah.search.model.SearchIndexEntry;
import com.oriah.search.repository.SearchIndexRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Mirrors catalog's product view into the local search_index. Same
 * pattern as inventory's ProductEventConsumer — single bean, header
 * dispatch, upsert-then-the-DB-generated-tsvector-takes-care-of-the-rest.
 */
@Configuration
@Slf4j
public class ProductEventConsumer {

    private static final String HEADER_EVENT_TYPE = "event-type";

    @Bean
    public Consumer<Message<byte[]>> productEvents(
            SearchIndexRepository repository,
            ObjectMapper objectMapper) {
        return new Handler(repository, objectMapper);
    }

    static class Handler implements Consumer<Message<byte[]>> {
        private final SearchIndexRepository repository;
        private final ObjectMapper objectMapper;

        Handler(SearchIndexRepository repository, ObjectMapper objectMapper) {
            this.repository = repository;
            this.objectMapper = objectMapper;
        }

        @Override
        @Transactional
        public void accept(Message<byte[]> message) {
            Object typeHeader = message.getHeaders().get(HEADER_EVENT_TYPE);
            String type = typeHeader == null ? null : typeHeader.toString();
            try {
                JsonNode payload = objectMapper.readTree(message.getPayload());
                UUID id = UUID.fromString(payload.path("id").asText());
                switch (type) {
                    case null -> ProductEventConsumer.log.warn("Skipping null event-type message");
                    case "product.created", "product.updated" -> upsert(id, payload);
                    case "product.deleted" -> {
                        repository.deleteById(id);
                        ProductEventConsumer.log.info("Removed search_index row id={}", id);
                    }
                    default -> ProductEventConsumer.log.warn("Skipping unrecognized event-type={}", type);
                }
            } catch (Exception e) {
                ProductEventConsumer.log.error("Failed to process event-type={} payload-length={}",
                        type, message.getPayload().length, e);
            }
        }

        private void upsert(UUID id, JsonNode payload) {
            String title = payload.path("title").asText("");
            String description = payload.path("description").asText(null);
            String tags = tagsToString(payload.path("tags"));
            repository.findById(id).ifPresentOrElse(
                    existing -> {
                        existing.replace(title, description, tags);
                        ProductEventConsumer.log.info("Updated search_index id={} title=\"{}\"", id, title);
                    },
                    () -> {
                        repository.save(new SearchIndexEntry(id, title, description, tags));
                        ProductEventConsumer.log.info("Indexed search_index id={} title=\"{}\"", id, title);
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
