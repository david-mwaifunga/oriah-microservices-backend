package com.oriah.inventory.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One purchasable configuration, as it arrives on a catalog product event.
 *
 * <h2>Why inventory has its own copy of this</h2>
 *
 * Inventory does not depend on catalog, and should not: a shared event class
 * would make a catalog release able to break this service at compile time and
 * would couple two independently deployable modules through a wire format they
 * are supposed to meet only on the topic. This is a consumer-side contract --
 * inventory declares what it expects to find, catalog declares what it sends,
 * and the only thing that has to match is the JSON.
 *
 * <p>The consequence worth being awake to: the two definitions can drift
 * silently, because nothing compiles them together. The protection is a
 * consumer-side test that feeds a REAL serialised payload produced from
 * catalog's shape through this service's actual consumer bean. Fields this
 * record does not declare are ignored rather than fatal, because Spring Boot
 * disables FAIL_ON_UNKNOWN_PROPERTIES by default and this service has no
 * override -- so catalog adding a field is safe, while renaming or removing
 * one this record reads is not.
 *
 * <p>Only the fields inventory actually uses are declared. Catalog also sends
 * a price and currency on each variant; inventory has no business knowing what
 * anything costs, so they are deliberately absent and get dropped on
 * deserialisation.
 *
 * @param variantId the id this service keys stock on
 * @param title     the product title plus this variant's option summary, e.g.
 *                  "Aurelia Solitaire Ring - Ring Size M", composed by catalog
 *                  so an operator sees something recognisable rather than a
 *                  bare UUID
 * @param active    whether catalog considers the variant sellable. Carried so
 *                  a withdrawal can be read directly instead of inferred from
 *                  a variant's absence, which is indistinguishable from one
 *                  that was never created.
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
 * and the binder sends the message to the dead-letter topic on its first
 * try, since a retry would read it no better -- but every product event after
 * such a change would stop reaching product_index until the consumer was
 * fixed and the events replayed.
 *
 * <p>Found by a test that constructed a raw ObjectMapper and failed, which is
 * exactly the condition described above.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record VariantPayload(
        UUID variantId,
        String sku,
        String title,
        BigDecimal unitPrice,
        String currency,
        boolean active
) {
}
