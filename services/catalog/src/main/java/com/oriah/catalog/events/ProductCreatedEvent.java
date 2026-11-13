package com.oriah.catalog.events;

import java.util.List;
import java.util.UUID;

/**
 * Domain event: a new product was created in catalog. Published on the
 * {@code catalog.product} Kafka topic; consumed by inventory (to register
 * the new variantId in its product_index) and any future subscribers
 * (search-service for indexing, recommender-service, etc.).
 *
 * <p>The event is a snapshot of the product at create time, not a delta.
 * Consumers receive enough state to act without calling back into catalog.
 *
 * <p>Tags are passed as a {@link List} (not a {@code Set}) for stable JSON
 * serialization order — easier to read in topic dumps and consumer logs.
 *
 * @param id       The product's UUID, which doubles as the variantId until
 *                 catalog grows a Variant entity (later iteration).
 * @param title    Display title at create time.
 * @param tags     Tag slugs at create time.
 *
 * <h2>Fields added by the products phase, additively</h2>
 *
 * {@code productType}, {@code status} and {@code variants} were appended; no
 * existing field was renamed or removed. That is the whole compatibility
 * story, and it rests on two facts about the consumers rather than on hope:
 * inventory binds this record with FAIL_ON_UNKNOWN_PROPERTIES disabled
 * (Spring Boot's default, with no override anywhere in that service), and
 * search never binds to this class at all -- it reads a generic tree, so a
 * field it does not know is simply never looked up. Tests in BOTH consumer
 * modules push a real serialised new-shape payload through the actual consumer
 * bean, because "the default is lenient" is an argument and not a test.
 *
 * <h2>{@code kind}, added by Phase 3, last and additively</h2>
 *
 * {@code SELLABLE} or {@code PACKAGING}: whether this entry is something Oriah
 * sells or what a sale goes out in. Appended after {@code variants}, so nothing
 * that read the earlier shape is disturbed; both consumers tolerate an unknown
 * field exactly as for the three above. <strong>A consumer must read an absent
 * {@code kind} as SELLABLE</strong>: every snapshot written before Phase 3 has
 * none, and every product that existed then was made to be sold. Search lists a
 * product only when it is ACTIVE and SELLABLE; inventory will read it in Phase 5.
 * Note that search therefore reads {@code status} and {@code kind} BY NAME from
 * the generic tree: unlike the fields above, renaming either would silently stop
 * every product being listed (search's {@code ProductEventCompatibilityTest}
 * pins both names).
 *
 * <p>{@code variants} is the field that matters. Before it, inventory wrote the
 * PRODUCT id into product_index.variant_id -- correct only while a product had
 * exactly one variant whose id was the product's own. For anything created
 * after the V3 migration that is false, and inventory would have been tracking
 * stock against an id nobody can buy.
 */
public record ProductCreatedEvent(
        UUID id,
        String title,
        List<String> tags,
        String productType,
        String status,
        List<VariantPayload> variants,
        String kind,
        UUID defaultPackagingVariantId,
        int unpublishCount
) {
    /**
     * Event-type marker used by consumers that subscribe to a single topic
     * carrying multiple event types. The convention is {@code <aggregate>.<verb>}
     * matching Eazy Bytes / Spring Cloud Stream tutorials.
     */
    public static final String TYPE = "product.created";
}
