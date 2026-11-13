package com.oriah.inventory.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.UUID;

/**
 * Inventory's local view of catalog's {@code product.updated} event. See
 * {@link ProductCreatedEvent} for why this is duplicated instead of
 * shared via a library.
 */
/**
 * <h2>Fields added by catalog's products phase</h2>
 *
 * {@code productType}, {@code status} and {@code variants} were appended to
 * what catalog sends. They are declared here because this consumer now needs
 * {@code variants}: the product id alone is no longer the thing a customer
 * buys, so registering stock against it would track an id nobody can purchase.
 *
 * <p>Fields this record does NOT declare are dropped silently, which is what
 * makes catalog's additions safe. It is also why renaming or removing
 * {@code id} or {@code title} would not be -- a consumer-side test pushes a
 * real serialised payload through the consumer bean to keep that honest,
 * since nothing compiles the two definitions together.
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
 * <p>It would no longer be silent -- a body that cannot be read now throws,
 * and the oriah-events module sends the message to the dead-letter topic on
 * its first delivery, since a retry would read it no better -- but every
 * product event after such a change would stop reaching product_index until
 * the consumer was fixed and the events replayed.
 *
 * <p>Found by a test that constructed a raw ObjectMapper and failed, which is
 * exactly the condition described above.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProductUpdatedEvent(
        UUID id,
        String title,
        String description,
        List<String> tags,
        String productType,
        String status,
        List<VariantPayload> variants,
        // Added in Phase 6 (kind in Phase 3, which inventory ignored until now); an event from before carries none of them.
        String kind,
        UUID defaultPackagingVariantId,
        Integer unpublishCount
) {
    public static final String TYPE = "product.updated";
}
