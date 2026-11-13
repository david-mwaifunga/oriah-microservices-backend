package com.oriah.catalog.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

import com.oriah.catalog.model.Availability;

/**
 * Wire-format response for a single product. Carries only what we want to
 * expose publicly — fields the entity holds but the API shouldn't surface
 * (audit timestamps, soft-delete flags, internal flags) stay off this record.
 *
 * <p>Mapping lives in {@link com.oriah.catalog.mapper.ProductMapper}, not on
 * this record. The enriched overload takes a SERVICE-layer type, so having it
 * here made com.oriah.catalog.dto depend on com.oriah.catalog.service -- the
 * wire format depending on the orchestration layer, which is backwards for a
 * boundary type. ProductMapperTest asserts reflectively that no service type
 * reappears in this record's signatures.
 *
 * <h2>Which fields a list omits, and why that is not arbitrary</h2>
 *
 * Every addition here is nullable and omitted by {@code @JsonInclude(NON_NULL)}
 * when it is not populated, following the precedent {@code availability} set:
 * it is filled on the by-id endpoint, where one inventory call is proportionate,
 * and left null on list endpoints, where it would be one call per row.
 *
 * <p>A list response carries {@code priceFrom}/{@code priceTo} and the
 * {@code images} needed for a card, because a product grid without a price or
 * a picture is the grid this phase set out to replace. It omits
 * {@code variants} and {@code attributes}, which only a detail page renders.
 *
 * <h2>Three things a client must not infer</h2>
 *
 * <p>{@code id} is the PRODUCT id and is no longer a variant id. Before the
 * variants migration those were the same value, so code that posted
 * {@code product.id} to the cart worked by coincidence. Each
 * {@link VariantResponse#id()} is the thing to buy.
 *
 * <p>{@code priceFrom} is the cheapest PURCHASABLE variant — active and priced
 * above zero. It is not the minimum over all variants: advertising a price
 * from a deactivated variant, or the 0.00 that the variants migration wrote
 * for every pre-existing product, would quote a figure nobody can pay.
 *
 * <p>{@code currency} is one code for the whole product, which holds because a
 * product's variants are required to share a currency. A product has no
 * currency at all until it has a variant, so this is null rather than a
 * guessed default.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProductResponse(
        UUID id,
        String title,
        String description,
        List<String> tags,
        Availability availability,

        // --- added by the products phase; all nullable, all omitted when unset ---

        /** URL identity, unique across the catalogue. */
        String slug,

        /** DRAFT, ACTIVE or ARCHIVED. Public endpoints only ever return ACTIVE. */
        String status,

        /** JEWELLERY or WATCH; selects which spec fields {@code attributes} holds. */
        String productType,

        /**
         * SELLABLE or PACKAGING. Public reads only ever return SELLABLE (packaging
         * is never published); the field is there so an admin screen can tell a
         * gift box from a ring without guessing from the title.
         */
        String kind,

        /** Type-specific specs, keyed by the type's declared attribute keys. */
        Map<String, Object> attributes,

        /**
         * The axes this product's variants vary on, in display order: {@code
         * ["Ring Size"]}, or {@code ["Strap", "Case Size"]}. Empty for a product
         * that does not vary. Every variant's option keys equal this set. Detail
         * and admin reads only; a list row omits it.
         */
        List<String> optionNames,

        /** Every category the product belongs to, without children populated. */
        List<CategoryResponse> categories,

        /** The category for breadcrumbs and the canonical URL; may be absent. */
        CategoryResponse primaryCategory,

        /** Cheapest purchasable variant's price. */
        BigDecimal priceFrom,

        /** Dearest purchasable variant's price; equal to {@code priceFrom} when one price. */
        BigDecimal priceTo,

        /** Shared across all variants of the product. */
        String currency,

        /** Populated on detail only. */
        List<VariantResponse> variants,

        /** Ordered; the first is the card image. */
        List<ProductImageResponse> images,

        /**
         * The box this product normally goes out in (a variant of a packaging entry), when one is chosen. Admin reads only; a shopper is
         * never told how a piece is packed, so the public reads omit it.
         */
        UUID defaultPackagingVariantId
) {

    /** This response with the product's default box set: for the admin reads only, because how a piece is packed is for staff. */
    public ProductResponse withDefaultPackagingVariantId(UUID boxVariantId) {
        return new ProductResponse(id, title, description, tags, availability, slug, status, productType, kind, attributes, optionNames, categories,
                primaryCategory, priceFrom, priceTo, currency, variants, images, boxVariantId);
    }
}
