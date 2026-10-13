package com.oriah.inventory.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.UUID;

/**
 * Inventory's local view of catalog's {@code product.deleted} event.
 */
/**
 * Tolerates fields this consumer does not declare, as a property of the
 * CONTRACT rather than of configuration.
 *
 * <p>Without this annotation the forward-compatibility story rested entirely
 * on Spring Boot disabling {@code FAIL_ON_UNKNOWN_PROPERTIES} on the
 * auto-configured mapper. Jackson's own default is the opposite, so a bare
 * {@code new ObjectMapper()} anywhere in this path -- or anyone setting
 * {@code spring.jackson.deserialization.fail-on-unknown-properties=true},
 * which is a perfectly reasonable thing to want for request bodies -- would
 * turn every field catalog adds into a poison message.
 *
 * <p>And it would do so silently: this consumer logs and swallows
 * deserialisation failures, and no DLQ is configured, so the symptom would be
 * events disappearing with a line in a log nobody is watching rather than
 * anything failing loudly.
 *
 * <p>Found by a test that constructed a raw ObjectMapper and failed, which is
 * exactly the condition described above.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProductDeletedEvent(UUID id) {
    public static final String TYPE = "product.deleted";
}
