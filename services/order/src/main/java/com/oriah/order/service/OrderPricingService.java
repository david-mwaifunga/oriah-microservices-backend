package com.oriah.order.service;

import com.oriah.quote.QuoteLine;
import com.oriah.quote.Quote;
import com.oriah.quote.QuoteDigest;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.oriah.order.client.CatalogPricingClient;
import com.oriah.order.dto.PlaceOrderRequest;
import com.oriah.order.exception.BagNotPurchasableException;
import com.oriah.order.exception.MixedCurrencyException;
import com.oriah.order.exception.PriceClaimRejectedException;
import com.oriah.order.exception.QuoteChangedException;
import com.oriah.order.exception.TotalTooLargeException;

import lombok.extern.slf4j.Slf4j;

/**
 * Prices an order's lines from catalog, and checks what the caller claimed.
 *
 * <h2>Deliberately NOT transactional, and a separate bean for that reason</h2>
 *
 * This makes an outbound HTTP call. {@code OrderService}'s own javadoc, on
 * {@code verifyPaymentClaim}, already states the rule: "it must not run inside
 * a transaction: holding a database connection open across a network call to
 * another service is how a slow dependency becomes an exhausted connection
 * pool."
 *
 * <p>It is its own bean rather than another method on {@code OrderService}
 * because a non-transactional method calling a {@code @Transactional} one on
 * the SAME bean is self-invocation, which bypasses the proxy entirely -- the
 * trap that same javadoc warns about. Two beans means two real proxy
 * crossings, and {@code PlaceOrderPricingTest
 * .pricingHappensOutsideTheTransaction} asserts the boundary rather than
 * trusting it.
 *
 * <h2>The claim</h2>
 *
 * The caller may state a price. If they do and it disagrees with catalog's,
 * the order is REFUSED rather than silently repriced -- see
 * {@link CatalogPricingClient} for why overwriting would have introduced a
 * worse bug than it closed. The claim can only cause a refusal; it can never
 * lower what is charged.
 *
 * <p>The title is overwritten, not asserted: an admin renaming a product must
 * not refuse a sale.
 */
@Service
@Slf4j
public class OrderPricingService {

    /**
     * {@code orders.subtotal} is {@code NUMERIC(12,2)}, so this is the largest
     * value the column can hold. The per-line caps bound each input but
     * nothing bounded the SUM, and catalog's {@code unit_price} has a floor
     * with no ceiling -- 100 lines x 99 units x a mistyped admin price
     * overflows the column and becomes a 500 on insert.
     */
    static final BigDecimal MAX_TOTAL = new BigDecimal("9999999999.99");

    /** Money is stored at scale 2, so it is compared and summed at scale 2. */
    private static final int MONEY_SCALE = 2;

    private final CatalogPricingClient catalog;
    private final ShippingQuotes shipping;

    public OrderPricingService(CatalogPricingClient catalog, ShippingQuotes shipping) {
        this.catalog = catalog;
        this.shipping = shipping;
    }

    /** One line as catalog prices it, with the caller's quantity. */
    public record PricedLine(UUID variantId, String title, int quantity, BigDecimal unitPrice) {
    }

    /**
     * The order as order itself prices it: catalog's prices for the lines, and
     * shipping's price for the option the shopper chose, held as the shared
     * {@link Quote} whose {@code total()} is the one formula the digest binds.
     */
    public record PricedOrder(List<PricedLine> lines, BigDecimal subtotal, String currency, Quote quote) {

        public String deliveryOption() {
            return quote.deliveryOption();
        }

        public BigDecimal shippingAmount() {
            return quote.deliveryAmount();
        }
    }

    /**
     * @throws BagNotPurchasableException  a line is not sellable -- 409
     * @throws PriceClaimRejectedException a stated price or currency disagrees
     *                                     with catalog's -- 409
     * @throws MixedCurrencyException      the lines are not all one currency -- 409
     * @throws TotalTooLargeException      the sum exceeds the column -- 422
     */
    public PricedOrder price(PlaceOrderRequest req) {
        List<UUID> distinctIds = req.items().stream()
                .map(PlaceOrderRequest.LineItem::variantId)
                .distinct()
                .toList();

        CatalogPricingClient.PricingResponse response =
                catalog.price(new CatalogPricingClient.PricingRequest(distinctIds));

        // Null-safe, and keyed BY ID. A null results list is "catalog answered
        // nothing", which is a refusal rather than an NPE; an entry with a null
        // variantId cannot be matched to a line, so it is dropped before it can
        // break the map build.
        Map<UUID, CatalogPricingClient.Entry> byVariant =
                response == null || response.results() == null
                        ? Map.of()
                        : response.results().stream()
                                .filter(e -> e.variantId() != null)
                                // (a, b) -> a: catalog should never return two
                                // entries for one id, but if it does the choice
                                // must be deterministic rather than whichever
                                // the collector kept. checkout's own toMap
                                // resolves it the same way.
                                .collect(Collectors.toMap(
                                        CatalogPricingClient.Entry::variantId,
                                        e -> e,
                                        (a, b) -> a));

        List<PricedLine> lines = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO.setScale(MONEY_SCALE);
        String currency = null;
        List<UUID> unsellable = new ArrayList<>();

        // Iterated over the REQUEST's lines, not over the response's entries:
        // two lines may name the same variant, and catalog was asked for
        // distinct ids. Zipping the response back by index would silently drop
        // the second line and undercharge by its whole value.
        for (PlaceOrderRequest.LineItem item : req.items()) {
            CatalogPricingClient.Entry entry = byVariant.get(item.variantId());
            if (entry == null || !entry.isSellable()) {
                unsellable.add(item.variantId());
                continue;
            }

            String entryCurrency = entry.currency().toUpperCase();
            if (currency == null) {
                currency = entryCurrency;
            } else if (!currency.equals(entryCurrency)) {
                log.warn("Refusing order: lines mix {} and {}", currency, entryCurrency);
                throw new MixedCurrencyException();
            }

            BigDecimal price = entry.unitPrice().setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            requireClaimHolds(item, price, entryCurrency, req.currency());

            lines.add(new PricedLine(item.variantId(), entry.variantTitle(),
                    item.quantity(), price));
            subtotal = subtotal.add(
                    price.multiply(BigDecimal.valueOf(item.quantity()))
                            .setScale(MONEY_SCALE, RoundingMode.HALF_UP));
        }

        if (!unsellable.isEmpty()) {
            log.warn("Refusing order: {} line(s) not sellable, variantIds={}",
                    unsellable.size(), unsellable);
            throw new BagNotPurchasableException(unsellable);
        }

        if (subtotal.compareTo(MAX_TOTAL) > 0) {
            // No figure in the message: the cap is an implementation limit, and
            // a caller who reached it learns nothing useful from the number.
            log.error("Refusing order: repriced subtotal {} exceeds the {} the column holds",
                    subtotal, MAX_TOTAL);
            throw new TotalTooLargeException();
        }

        // Delivery is priced by order itself, from the option the shopper
        // chose -- outside any transaction, like the catalog call above.
        BigDecimal delivery = shipping.costOf(req.deliveryOption(), subtotal, currency);
        Quote quote = new Quote(lines.stream()
                .map(l -> new QuoteLine(l.variantId(), l.quantity(), l.unitPrice(), BigDecimal.ZERO))
                .toList(), currency, req.deliveryOption(), delivery);

        // The bound is on what is CHARGED, delivery included: a subtotal just
        // under the column's capacity plus delivery would overflow orders.total.
        if (quote.total().compareTo(MAX_TOTAL) > 0) {
            log.error("Refusing order: total {} exceeds the {} the column holds", quote.total(), MAX_TOTAL);
            throw new TotalTooLargeException();
        }

        // Quote, then commit: only the quote the shopper saw can be charged.
        // order has priced every line and the delivery itself; if the digest
        // of THAT differs from the one preview showed, something moved in
        // between -- a quantity, a price, the delivery price -- and nothing is
        // saved. Logged without either digest: they identify nothing useful.
        if (!QuoteDigest.of(quote).equals(req.quoteDigest())) {
            log.warn("Refusing order: the quote changed since the shopper saw it");
            throw new QuoteChangedException();
        }

        return new PricedOrder(List.copyOf(lines), subtotal, currency, quote);
    }

    /**
     * The caller's stated price and currency, checked against catalog's.
     *
     * <p>Compared with {@code compareTo} and at scale 2, so {@code 100.0} is
     * not a different price from {@code 100.00}; and case-insensitively for
     * currency, so {@code zmw} is not a different currency from {@code ZMW}. A
     * formatting difference is not a money difference, and refusing one would
     * be a refusal nobody could act on.
     */
    private void requireClaimHolds(PlaceOrderRequest.LineItem item,
                                   BigDecimal catalogPrice,
                                   String catalogCurrency,
                                   String claimedCurrency) {
        if (item.unitPrice() != null) {
            BigDecimal claimed = item.unitPrice().setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            if (claimed.compareTo(catalogPrice) != 0) {
                log.warn("Refusing order: variantId={} claimed at {} but catalog prices it at {}",
                        item.variantId(), claimed, catalogPrice);
                throw new PriceClaimRejectedException();
            }
        }
        if (claimedCurrency != null
                && !claimedCurrency.equalsIgnoreCase(catalogCurrency)) {
            log.warn("Refusing order: claimed currency {} but catalog prices variantId={} in {}",
                    claimedCurrency, item.variantId(), catalogCurrency);
            throw new PriceClaimRejectedException();
        }
    }
}
