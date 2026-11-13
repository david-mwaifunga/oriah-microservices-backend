package com.oriah.catalog.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.oriah.catalog.config.PricingProperties;
import com.oriah.catalog.dto.BelowCostItem;
import com.oriah.catalog.dto.BelowCostPage;
import com.oriah.catalog.dto.CostView;
import com.oriah.catalog.dto.PriceChangeResponse;
import com.oriah.catalog.dto.PriceEntryResponse;
import com.oriah.catalog.dto.SetPriceRequest;
import com.oriah.catalog.dto.SuggestionView;
import com.oriah.catalog.dto.VariantPricingView;
import com.oriah.catalog.exception.PricingProblem;
import com.oriah.catalog.model.Product;
import com.oriah.catalog.model.ProductKind;
import com.oriah.catalog.model.ProductStatus;
import com.oriah.catalog.model.ProductVariant;
import com.oriah.catalog.pricing.CostFigures;
import com.oriah.catalog.pricing.GuardResult;
import com.oriah.catalog.pricing.HeldCost;
import com.oriah.catalog.pricing.PriceAssessment;
import com.oriah.catalog.pricing.PriceGuard;
import com.oriah.catalog.pricing.PriceHistory;
import com.oriah.catalog.pricing.PriceSuggestion;
import com.oriah.catalog.repository.PriceHistoryRepository;
import com.oriah.catalog.repository.ProductVariantRepository;
import com.oriah.events.EventJson;
import com.oriah.observability.ResourceNotFoundException;

import lombok.extern.slf4j.Slf4j;

/**
 * Setting a price and reading how it stands against the cost.
 *
 * <h2>The shape of a save</h2>
 *
 * Nothing is saved without the cost having been looked at, and a remote call is never made while a row is locked. So a save runs
 * in the order: read what it needs (no lock) ; ask inventory, as the person, what the stock cost ; decide (a reason is needed below
 * cost, and the person must have been shown the floor they accept) ; and only then lock the product, check again that what the
 * decision rested on has not moved, and write ({@link ProductService#setVariantPrice}). The warning never refuses a price: it makes
 * sure a price below cost is never saved unseen.
 *
 * <h2>"Re-runs" without a trigger</h2>
 *
 * Where a price stands against the cost is computed from inventory's held lots at every read, so a dearer lot received, a corrected
 * cost, a dear lot sold out of, or a changed default box show the next time anyone looks, including causes no trigger list would
 * name. Nothing is stored that could go stale.
 */
@Service
@Slf4j
public class PricingService {

    /** Who is pricing: the actor as the event log records it, and the name the token carries, if any. */
    public record Staff(String actor, String name) {
    }

    /** What is read about a variant before anything else is done: no lock is held, and no entity leaves this class. */
    private record Target(UUID productId, UUID variantId, String productTitle, String variantTitle, String sku, ProductStatus productStatus,
                          ProductKind kind, UUID boxVariantId, BigDecimal currentPrice, String currentCurrency,
                          PriceHistoryRepository.Entry latest, List<PriceHistoryRepository.Entry> history) {
    }

    private final ProductVariantRepository variants;
    private final PriceHistoryRepository priceHistory;
    private final PriceCheck check;
    private final ProductService products;
    private final PricingProperties pricing;
    private final TransactionTemplate readOnly;

    public PricingService(ProductVariantRepository variants, PriceHistoryRepository priceHistory, PriceCheck check, ProductService products,
                          PricingProperties pricing, PlatformTransactionManager transactions) {
        this.variants = variants;
        this.priceHistory = priceHistory;
        this.check = check;
        this.products = products;
        this.pricing = pricing;
        this.readOnly = new TransactionTemplate(transactions);
        this.readOnly.setReadOnly(true);
    }

    // ---------------------------------------------------------------- saving a price

    public PriceChangeResponse setPrice(UUID variantId, SetPriceRequest request, Staff staff, String bearer) {
        Target target = load(variantId, true);
        String currency = resolveCurrency(request.currency(), target.currentCurrency());

        // Cheap refusals first, so a doomed save costs inventory nothing. The same checks repeat under the lock, which is the one that counts.
        UUID latestId = target.latest() == null ? null : target.latest().id();
        if (!Objects.equals(latestId, request.expectedPriceId())) {
            throw new PricingProblem(HttpStatus.CONFLICT, "stale-price", "the price changed since it was read: someone else has priced this variant")
                    .with("currentPriceId", latestId);
        }
        PriceHistory.effectiveFrom(request.effectiveFrom(), check.now(), target.latest() == null ? null : target.latest().effectiveFrom(),
                pricing.getBackdateWindow());

        PriceCheck.Reading reading = check.read(variantId, target.boxVariantId(), bearer);
        GuardResult result = PriceGuard.evaluate(request.unitPrice(), currency, reading.goods(), target.boxVariantId(), reading.box());
        String reason = request.belowCostReason() == null ? null : request.belowCostReason().strip();
        if (reason != null && !reason.isEmpty() && (reason.length() < 5 || reason.length() > 200)) {
            // Bean validation judged the raw text; the reason is kept trimmed, and the database refuses a short one. Said here, as a 400.
            throw new IllegalArgumentException("belowCostReason must be 5 to 200 characters once the spaces around it are removed");
        }
        if (PriceCheck.needsReason(result.assessment()) && !accepted(result, reason, request.acknowledgedFloor())) {
            throw warning(result, reading);
        }

        ProductService.PriceApplied applied = products.setVariantPrice(target.productId(),
                new ProductService.PriceWrite(variantId, request.unitPrice(), currency, request.effectiveFrom(), request.expectedPriceId(),
                        target.boxVariantId(), result, reading, reason, staff.name()),
                staff.actor());
        return new PriceChangeResponse(entry(applied.row()), result.assessment(), PriceCheck.costView(reading), applied.productStatus(), applied.publishable());
    }

    /** A price below the cost is accepted only with a typed reason AND for the floor the person was shown (or a higher one). */
    private static boolean accepted(GuardResult result, String reason, BigDecimal acknowledgedFloor) {
        if (reason == null || reason.isBlank()) {
            return false;
        }
        return result.floor() == null || (acknowledgedFloor != null && acknowledgedFloor.compareTo(result.floor()) >= 0);
    }

    private static PricingProblem warning(GuardResult result, PriceCheck.Reading reading) {
        String detail = result.assessment() == PriceAssessment.BELOW
                ? "the price " + result.price().toPlainString() + " is below the cost " + result.floor().toPlainString()
                    + " (by " + result.shortfall().toPlainString() + "): type a reason and confirm the floor to save it anyway"
                : "the price is in " + result.priceCurrency() + " but the stock is costed in another currency: type a reason to save it anyway";
        return new PricingProblem(HttpStatus.CONFLICT, "below-landed-cost", detail)
                .with("assessment", result.assessment())
                .with("price", result.price())
                .with("currency", result.priceCurrency())
                .with("floor", result.floor())
                .with("shortfall", result.shortfall())
                .with("estimated", result.estimated())
                .with("cost", PriceCheck.costView(reading));
    }

    /**
     * The price's currency: the one asked for, or the variant's OWN when none is (so a request that never mentioned currency cannot quietly
     * redenominate a legacy dollar piece at the same number). Whichever it is, it must be the shop's: a piece still in another currency has
     * to be moved by saying so.
     */
    private String resolveCurrency(String requested, String variantCurrency) {
        String shop = pricing.getCurrency();
        String currency = requested == null || requested.isBlank() ? variantCurrency : requested.strip().toUpperCase();
        if (currency == null || !currency.equalsIgnoreCase(shop)) {
            throw new PricingProblem(HttpStatus.BAD_REQUEST, "unsupported-currency",
                    "prices are in " + shop + ": " + currency + " cannot be compared with what the stock cost"
                            + (requested == null || requested.isBlank() ? " (this piece is still in " + currency + ": say " + shop + " to move it)" : ""));
        }
        return shop.toUpperCase();
    }

    // ---------------------------------------------------------------- reading a variant

    public VariantPricingView view(UUID variantId, BigDecimal targetProfitPercent, String bearer) {
        Target target = load(variantId, true);
        PriceCheck.Reading reading = check.read(variantId, target.boxVariantId(), bearer);
        CostFigures figures = reading.figures();

        PriceAssessment assessment = null;
        BigDecimal shortfall = null;
        if (target.latest() != null) {
            GuardResult result = PriceGuard.evaluate(target.currentPrice(), target.currentCurrency(), reading.goods(), target.boxVariantId(), reading.box());
            assessment = result.assessment();
            shortfall = result.shortfall();
        }
        BigDecimal percent = targetProfitPercent != null ? targetProfitPercent : pricing.getTargetProfitPercent();
        SuggestionView suggestion = PriceSuggestion.of(figures.floor(), percent)
                .map(suggested -> new SuggestionView(percent, figures.floor(), suggested)).orElse(null);

        return new VariantPricingView(variantId, target.productId(), target.productTitle(), target.variantTitle(), target.sku(), target.productStatus(),
                target.boxVariantId(), target.latest() == null ? null : entry(target.latest()), assessment, shortfall, PriceCheck.costView(reading),
                suggestion, target.history().stream().map(PricingService::entry).toList());
    }

    private Target load(UUID variantId, boolean refusePackaging) {
        Target target = readOnly.execute(status -> {
            List<ProductVariant> found = variants.findAllByIdInWithProduct(List.of(variantId));
            if (found.isEmpty() || found.get(0).product().isDeleted()) {
                return null;
            }
            ProductVariant variant = found.get(0);
            Product product = variant.product();
            List<PriceHistoryRepository.Entry> history = priceHistory.history(variantId);
            return new Target(product.id(), variantId, product.title(), DefaultVariantPricingService.variantTitle(product, variant), variant.sku(),
                    product.status(), product.kind(), product.defaultPackagingVariantId(), variant.unitPrice(), variant.currency(),
                    history.isEmpty() ? null : history.get(0), history);
        });
        if (target == null) {
            throw new ResourceNotFoundException("Variant", "id", variantId.toString());
        }
        if (refusePackaging && target.kind() == ProductKind.PACKAGING) {
            throw new PricingProblem(HttpStatus.CONFLICT, "packaging-is-not-priced",
                    "'" + target.productTitle() + "' is packaging: a box is bought, stocked and costed but never priced, because it is never sold on its own");
        }
        return target;
    }

    private static PriceEntryResponse entry(PriceHistoryRepository.Entry row) {
        Object basis = row.costBasisJson() == null ? null : EventJson.mapper().readValue(row.costBasisJson(), Object.class);
        return new PriceEntryResponse(row.id(), row.unitPrice(), row.currency(), row.effectiveFrom(), row.recordedAt(), row.setBy(), row.setByName(),
                row.source(), row.assessment(), row.reason(), basis);
    }

    // ---------------------------------------------------------------- every price against the cost

    /**
     * Every priced, active variant of a sellable product that has not been deleted, compared with the stock held NOW. One inventory read per
     * 100 variants (goods and their default boxes together). Held in memory and paged afterwards: the shop has tens of pieces, and the
     * figures must all come from one reading anyway.
     */
    public BelowCostPage belowCost(PriceAssessment filter, int page, int size, String bearer) {
        List<ProductVariant> priced = readOnly.execute(status -> {
            List<ProductVariant> found = variants.findPricedForSale();
            // Read inside the transaction, so the lazy parts used below are loaded while the session is open.
            found.forEach(v -> v.product().title());
            return found;
        });
        Set<UUID> ids = new LinkedHashSet<>();
        for (ProductVariant variant : priced) {
            ids.add(variant.id());
            UUID box = variant.product().defaultPackagingVariantId();
            if (box != null) {
                ids.add(box);
            }
        }
        Map<UUID, HeldCost> held = check.readMany(ids, bearer);

        Map<PriceAssessment, Long> counts = new EnumMap<>(PriceAssessment.class);
        for (PriceAssessment each : PriceAssessment.values()) {
            counts.put(each, 0L);
        }
        List<BelowCostItem> items = new ArrayList<>();
        for (ProductVariant variant : priced) {
            Product product = variant.product();
            UUID box = product.defaultPackagingVariantId();
            GuardResult result = PriceGuard.evaluate(variant.unitPrice(), variant.currency(), held.get(variant.id()), box, box == null ? null : held.get(box));
            counts.merge(result.assessment(), 1L, Long::sum);
            if (filter == null || filter == result.assessment()) {
                items.add(new BelowCostItem(variant.id(), product.id(), product.title(), DefaultVariantPricingService.variantTitle(product, variant),
                        variant.sku(), product.status(), variant.unitPrice(), variant.currency(), result.assessment(), result.floor(), result.shortfall(),
                        result.goodsHighest(), result.boxHighest(), result.boxBasis(), result.estimated()));
            }
        }
        items.sort(Comparator.comparing(BelowCostItem::shortfall, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(BelowCostItem::productTitle).thenComparing(BelowCostItem::sku));
        int from = (int) Math.min((long) page * size, items.size());
        int to = Math.min(from + size, items.size());
        return new BelowCostPage(List.copyOf(items.subList(from, to)), counts, page, size, items.size());
    }
}
