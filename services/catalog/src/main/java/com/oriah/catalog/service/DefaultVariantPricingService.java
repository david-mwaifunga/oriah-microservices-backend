package com.oriah.catalog.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.oriah.catalog.dto.VariantPricingResponse;
import com.oriah.catalog.dto.VariantPricingResponse.Entry;
import com.oriah.catalog.dto.VariantPricingResponse.Status;
import com.oriah.catalog.model.Product;
import com.oriah.catalog.model.ProductKind;
import com.oriah.catalog.model.ProductStatus;
import com.oriah.catalog.model.ProductVariant;
import com.oriah.catalog.repository.ProductVariantRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Resolves what the catalogue says a set of variants costs.
 *
 * <h2>Why this service exists at all</h2>
 *
 * cart-service has no catalogue dependency: it takes {@code unitPrice} from
 * the request body and stores it. Proved against the running stack — adding an
 * item at {@code unitPrice: 0.01} produced a cart subtotal of 0.01 and a
 * checkout preview total of 5.01. This is where the real number comes from, so
 * the rules below are money rules, not presentation.
 *
 * <h2>The price is withheld unless the variant is actually sellable</h2>
 *
 * An {@code UNPUBLISHED} entry carries no price, and that is a deliberate
 * design choice rather than an omission. A caller that forgets to check
 * {@code status} and reads {@code unitPrice} straight off the entry gets null
 * and fails loudly, instead of quietly charging for something that is not for
 * sale. Returning the price alongside a status nobody checked would make the
 * safe-looking mistake the dangerous one.
 *
 * <p>Sellable means {@link ProductVariant#isPurchasable()} — active and priced
 * above zero — AND the owning product is {@link ProductStatus#ACTIVE}. Both
 * halves matter: a live product can have a withdrawn size, and a withdrawn
 * product can still have perfectly good variants. And zero is not a price: V3
 * backfilled every pre-existing variant at 0.00, so treating zero as sellable
 * would make the entire migrated catalogue free.
 */
@Service
@Slf4j
public class DefaultVariantPricingService implements VariantPricingService {

    private final ProductVariantRepository variants;

    public DefaultVariantPricingService(ProductVariantRepository variants) {
        this.variants = variants;
    }

    /**
     * One query for the whole batch, with every requested id answered.
     *
     * <p>Read-only transaction because the variant's {@code product} is
     * fetch-joined but its own collections are not; keeping the session open
     * for the mapping below avoids a lazy-load failure on the product title.
     *
     * @return one entry per requested id, in request order, including
     *         duplicates. The caller can zip it against its own lines
     *         positionally rather than building a map, and an id that resolved
     *         to nothing is reported rather than dropped — a missing entry
     *         would make cart compare list lengths to work out what happened,
     *         and leave the line holding its stale price.
     */
    @Override
    @Transactional(readOnly = true)
    public VariantPricingResponse priceAll(List<UUID> variantIds) {
        if (variantIds == null || variantIds.isEmpty()) {
            // No query for nothing to look up. cart calls this on every bag
            // read and an empty bag is the common case.
            return new VariantPricingResponse(List.of());
        }

        // Distinct for the query, because a bag can legitimately reference the
        // same variant twice in one request and the database should not be
        // asked twice.
        List<UUID> distinctIds = variantIds.stream().distinct().toList();
        Map<UUID, ProductVariant> found = variants.findAllByIdInWithProduct(distinctIds).stream()
                .collect(Collectors.toMap(ProductVariant::id, Function.identity()));

        List<Entry> results = variantIds.stream()
                .map(id -> toEntry(id, found.get(id)))
                .toList();

        long unresolved = results.stream().filter(e -> e.status() != Status.PRICED).count();
        if (unresolved > 0) {
            // Counted, not listed. A log line naming every id turns an
            // anonymous pricing lookup into a record of what someone has in
            // their bag, and this endpoint is anonymous.
            log.debug("priced {} variants, {} not sellable", variantIds.size(), unresolved);
        }
        return new VariantPricingResponse(results);
    }

    private static Entry toEntry(UUID requestedId, ProductVariant variant) {
        if (variant == null) {
            return Entry.unknown(requestedId);
        }
        Product product = variant.product();
        // Packaging is not on sale and never in a shopper's bag, so it is
        // answered as if it did not exist: no product id, no title, no price.
        // UNPUBLISHED would hand a caller who guessed a variant id the name of
        // the box, which a draft ring needs (a bag says which line is gone) and
        // a box never does.
        if (product.kind() != ProductKind.SELLABLE) {
            return Entry.unknown(requestedId);
        }
        // NOTE: a DELETED product is deliberately NOT special-cased here. It is
        // archived, so it falls into the UNPUBLISHED branch below along with
        // drafts and withdrawn products -- which is the whole point of making
        // the delete soft. A bag holding a line for a deleted product can say
        // "no longer available"; before, the row was gone and the answer was
        // UNKNOWN, which means "we have no idea what this is" and is a
        // different thing to tell a customer.
        if (product.status() != ProductStatus.ACTIVE || !variant.isPurchasable()) {
            // Product id and title are still returned so a bag can say WHICH
            // line is unavailable. The price is not.
            return Entry.unpublished(requestedId, product.id(), product.title());
        }
        return new Entry(
                variant.id(),
                Status.PRICED,
                product.id(),
                product.title(),
                variantTitle(product, variant),
                variant.unitPrice(),
                variant.currency());
    }

    /**
     * A label a human recognises: "Aurelia Solitaire Ring - Ring Size M".
     *
     * <p>cart snapshots this as the line's title, and a bag that listed bare
     * SKUs would be unreadable. A product that varies on nothing gets the
     * plain title rather than a trailing separator.
     */
    static String variantTitle(Product product, ProductVariant variant) {
        if (variant.options().isEmpty()) {
            return product.title();
        }
        // Ordered by the product's declared axes rather than by the map, so
        // two variants of one product always read in the same order.
        String options = product.optionNames().stream()
                .filter(variant.options()::containsKey)
                .map(axis -> axis + " " + variant.options().get(axis))
                .collect(Collectors.joining(", "));
        return options.isEmpty() ? product.title() : product.title() + " - " + options;
    }
}
