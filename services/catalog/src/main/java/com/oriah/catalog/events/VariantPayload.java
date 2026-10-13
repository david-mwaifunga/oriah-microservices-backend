package com.oriah.catalog.events;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One purchasable configuration, as it appears on a product event.
 *
 * <h2>Why this exists at all</h2>
 *
 * Before variants, {@code product.created} carried a single {@code id} and
 * inventory wrote it into {@code product_index.variant_id} — because the
 * product id WAS the variant id. Once a product can have several variants that
 * stops being true for anything created after the V3 migration, and inventory
 * would be registering a stock reference for a thing nobody can buy while the
 * actual variants had none.
 *
 * <p>So the events carry the variants explicitly, and inventory registers one
 * reference per variant.
 *
 * <h2>The addition is backward compatible, and that was checked rather than
 * assumed</h2>
 *
 * Neither consumer breaks on an unknown field: inventory binds these records
 * with an {@code ObjectMapper} that has {@code FAIL_ON_UNKNOWN_PROPERTIES}
 * disabled (Spring Boot's default, and there is no override anywhere in the
 * service), and search never binds to the classes at all — it reads a generic
 * tree with {@code payload.path(...)}, so a field it does not know is simply
 * never looked up. Tests in BOTH consumer modules feed a real serialised
 * new-shape payload through the actual consumer bean to prove it, because "the
 * default is lenient" is an argument and not a test.
 *
 * <p>What would NOT be safe is renaming or removing {@code id} or
 * {@code title}, so neither happens.
 *
 * @param variantId the id a cart line and an order line carry. For products
 *                  that predate the variants migration this equals the product
 *                  id; for anything created since, it does not. Consumers must
 *                  treat it as opaque.
 * @param title     the product title plus a summary of this variant's options,
 *                  e.g. "Aurelia Solitaire Ring - Ring Size M". Inventory
 *                  stores this so an operator sees something recognisable
 *                  instead of a bare UUID, and the option summary is what
 *                  distinguishes two rows of the same product.
 * @param active    whether the variant is currently sellable. Carried so a
 *                  consumer can decide for itself rather than inferring
 *                  absence from a shorter list.
 */
public record VariantPayload(
        UUID variantId,
        String sku,
        String title,
        BigDecimal unitPrice,
        String currency,
        boolean active
) {
}
