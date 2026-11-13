package com.oriah.catalog.events;

import java.util.List;
import java.util.UUID;

/**
 * Domain event: an existing product was partially updated. Carries the
 * post-update snapshot (not just the deltas) — consumers don't need to
 * track previous state to react.
 *
 * <p>For partial updates where some fields stay unchanged, the unchanged
 * fields are still included in the event with their current values. Keeps
 * the event self-contained; consumers don't need a previous read.
 *
 * @see ProductCreatedEvent
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
 * {@code SELLABLE} or {@code PACKAGING}; see {@link ProductCreatedEvent} for
 * why it is appended and why a consumer must read an absent one as SELLABLE.
 *
 * <p>{@code variants} is the field that matters. Before it, inventory wrote the
 * PRODUCT id into product_index.variant_id -- correct only while a product had
 * exactly one variant whose id was the product's own. For anything created
 * after the V3 migration that is false, and inventory would have been tracking
 * stock against an id nobody can buy.
 */
public record ProductUpdatedEvent(
        UUID id,
        String title,
        String description,
        List<String> tags,
        String productType,
        String status,
        List<VariantPayload> variants,
        String kind,
        UUID defaultPackagingVariantId,
        int unpublishCount
) {
    public static final String TYPE = "product.updated";
}
