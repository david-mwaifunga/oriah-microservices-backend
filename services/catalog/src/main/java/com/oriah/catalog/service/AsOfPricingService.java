package com.oriah.catalog.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.oriah.catalog.dto.AsOfPricingResponse;
import com.oriah.catalog.dto.AsOfPricingResponse.Entry;
import com.oriah.catalog.dto.AsOfPricingResponse.Status;
import com.oriah.catalog.model.Product;
import com.oriah.catalog.model.ProductKind;
import com.oriah.catalog.model.ProductVariant;
import com.oriah.catalog.pricing.PriceHistory;
import com.oriah.catalog.pricing.PriceRow;
import com.oriah.catalog.promotion.Discount;
import com.oriah.catalog.repository.PriceHistoryRepository;
import com.oriah.catalog.repository.ProductVariantRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Prices variants at a past instant: the staff twin of the storefront's read, for a sale keyed after the day it was made.
 *
 * <p>The price is the row of the variant's dated history in force at the instant ({@link PriceHistory#priceAt}, the one rule for "which row
 * applies"), and the promotion is whatever promotion says was in force at that same instant, so the answer for a past moment never changes
 * with what is on the price list or on promotion today. It ignores today's publication state: a piece withdrawn since was still sold at its
 * price on the day. Only packaging is refused (answered as unknown). A piece with no price yet at that instant has none: it is never priced at
 * zero and never at today's price.
 *
 * <p>As in the storefront's read, the database transaction ends before promotion is asked, and a promotion that cannot be asked is a 503
 * ({@link com.oriah.catalog.exception.PromotionsUnavailableException}): a sale is never priced without knowing what applied.
 */
@Service
@Slf4j
public class AsOfPricingService {

    /** Two services' clocks never agree exactly: an instant this far ahead is "now" from the caller's side. */
    static final Duration CLOCK_SKEW = Duration.ofSeconds(30);

    private final ProductVariantRepository variants;
    private final PriceHistoryRepository priceHistory;
    private final PromotionPricing promotions;
    private final TransactionTemplate readOnly;
    private final Clock clock;

    public AsOfPricingService(ProductVariantRepository variants, PriceHistoryRepository priceHistory, PromotionPricing promotions,
                              PlatformTransactionManager transactions, Clock clock) {
        this.variants = variants;
        this.priceHistory = priceHistory;
        this.promotions = promotions;
        this.readOnly = new TransactionTemplate(transactions);
        this.readOnly.setReadOnly(true);
        this.clock = clock;
    }

    /**
     * @param at         the moment of the sale
     * @param variantIds one entry is returned per id, in order, duplicates included
     * @throws IllegalArgumentException (400) if {@code at} has not happened yet
     */
    public AsOfPricingResponse priceAt(Instant at, List<UUID> variantIds) {
        if (at.isAfter(clock.instant().plus(CLOCK_SKEW))) {
            throw new IllegalArgumentException("a sale can only be priced at a moment that has happened: " + at + " is in the future");
        }
        List<UUID> distinct = variantIds.stream().distinct().toList();
        java.util.Set<UUID> markedDown = new java.util.HashSet<>();
        List<Entry> listed = readOnly.execute(status -> {
            Map<UUID, ProductVariant> found = variants.findAllByIdInWithProduct(distinct).stream()
                    .collect(Collectors.toMap(ProductVariant::id, Function.identity()));
            found.values().stream().filter(ProductVariant::isMarkedDown).forEach(v -> markedDown.add(v.id()));
            return variantIds.stream().map(id -> entry(id, found.get(id), at)).toList();
        });

        Map<UUID, PromotionPricing.Piece> toDiscount = new LinkedHashMap<>();
        for (Entry entry : listed) {
            if (entry.status() == Status.PRICED) {
                toDiscount.putIfAbsent(entry.variantId(),
                        new PromotionPricing.Piece(entry.variantId(), entry.productId(), entry.unitPrice(), markedDown.contains(entry.variantId())));
            }
        }
        Map<UUID, Discount> discounts = promotions.discountsAt(toDiscount.values(), at);

        List<Entry> results = listed.stream().map(entry -> entry.status() == Status.PRICED ? discounted(entry, discounts.get(entry.variantId())) : entry).toList();
        return new AsOfPricingResponse(at, results);
    }

    private Entry entry(UUID requestedId, ProductVariant variant, Instant at) {
        if (variant == null || variant.product().kind() != ProductKind.SELLABLE) {
            // A box is never sold on its own, and one that was never a variant is no answer either.
            return Entry.unknown(requestedId);
        }
        Product product = variant.product();
        String variantTitle = DefaultVariantPricingService.variantTitle(product, variant);
        List<PriceHistoryRepository.Entry> history = priceHistory.history(variant.id());
        Optional<PriceRow> row = PriceHistory.priceAt(history.stream().map(PriceHistoryRepository.Entry::asRow).toList(), at);
        if (row.isEmpty()) {
            return Entry.noPriceAtDate(variant.id(), product.id(), product.title(), variantTitle);
        }
        PriceHistoryRepository.Entry priced = history.stream().filter(h -> h.seq() == row.get().seq()).findFirst().orElseThrow();
        return new Entry(variant.id(), Status.PRICED, product.id(), product.title(), variantTitle, priced.unitPrice(), priced.currency(),
                priced.id(), null, null, null);
    }

    /** The entry with its discount: none is 0.00 off and the list price as the net price. */
    private static Entry discounted(Entry entry, Discount discount) {
        BigDecimal off = discount == null ? BigDecimal.ZERO.setScale(2) : discount.perPiece();
        return new Entry(entry.variantId(), entry.status(), entry.productId(), entry.productTitle(), entry.variantTitle(), entry.unitPrice(),
                entry.currency(), entry.priceId(), off, discount == null ? null : discount.promotionId(), entry.unitPrice().subtract(off));
    }
}
