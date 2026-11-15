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

    /**
     * One line as catalog prices it, with the caller's quantity: the LIST price, and what a promotion takes off each piece (zero, with no
     * promotion id, when none applies). The product is carried because inventory sets a box aside per product.
     */
    public record PricedLine(UUID variantId, UUID productId, String title, int quantity, BigDecimal unitPrice,
                             BigDecimal discountPerPiece, UUID promotionId) {
    }

    /**
     * The order as order itself prices it: catalog's prices for the lines (less any promotion's discount per piece), and shipping's price for
     * the town, option and payer the shopper chose, held as the shared {@link Quote} whose {@code total()} is the one formula the digest binds.
     */
    public record PricedOrder(List<PricedLine> lines, BigDecimal subtotal, String currency, Quote quote, String optionName, String townName) {

        /** What shipping called the option and the town it priced; null for a pickup. */

        public String deliveryOption() {
            return quote.deliveryOption();
        }

        public BigDecimal shippingAmount() {
            return quote.deliveryAmount();
        }

        public java.util.UUID deliveryTownId() {
            return quote.deliveryTownId();
        }

        public String payer() {
            return quote.payer();
        }

        public String quoteDigest() {
            return QuoteDigest.of(quote);
        }

        public int pieces() {
            return lines.stream().mapToInt(PricedLine::quantity).sum();
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

        Goods goods = priceGoods(req.items().stream().map(i -> new Ask(i.variantId(), i.quantity(), i.unitPrice())).toList(), byVariant, req.currency());
        List<PricedLine> lines = goods.lines();
        BigDecimal subtotal = goods.subtotal();
        BigDecimal discount = goods.discount();
        String currency = goods.currency();

        // Delivery is priced by SHIPPING, for the town and payer the shopper
        // chose, on the goods after discount (that is what a free-delivery
        // threshold is judged on) -- asked here, outside any transaction, like
        // the catalog call above. Order never works the amount out itself.
        ShippingQuotes.Delivery delivery = shipping.quote(req.deliveryOption(), req.deliveryTownId(), req.payer(),
                subtotal.subtract(discount), currency);
        boolean collect = Quote.SELF_COLLECT.equals(req.deliveryOption());
        Quote quote = new Quote(lines.stream()
                .map(l -> new QuoteLine(l.variantId(), l.quantity(), l.unitPrice(), l.discountPerPiece(), l.promotionId()))
                .toList(), currency, req.deliveryOption(), delivery.amount(),
                collect ? null : (delivery.townId() != null ? delivery.townId() : req.deliveryTownId()),
                collect ? null : delivery.payer());

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

        return new PricedOrder(List.copyOf(lines), subtotal, currency, quote, collect ? null : delivery.optionName(), collect ? null : delivery.townName());
    }

    /** One line asked for: the piece, how many, and (a shopper's order only) the price the caller CLAIMS, which can only cause a refusal. */
    public record Ask(UUID variantId, int quantity, BigDecimal claimedUnitPrice) {
    }

    /** The lines as priced, with their sums: the subtotal at list prices, the discount (per piece, summed) and the one currency of every line. */
    public record Goods(List<PricedLine> lines, BigDecimal subtotal, BigDecimal discount, String currency) {
    }

    /**
     * Prices the lines asked for from catalog's answers, one line per ask in order (two lines may name one variant). Shared by a shopper's order (catalog's
     * storefront read, now) and a recorded sale (catalog's staff read at the sale's date): the rules about a sellable entry, one currency, the list price, the
     * promotion's discount per piece and the ceiling are the same whoever asks.
     *
     * @param claimedCurrency a currency the caller stated, checked against catalog's (null when none was)
     * @throws BagNotPurchasableException  a line is not sellable -- 409
     * @throws PriceClaimRejectedException a stated price or currency disagrees with catalog's -- 409
     * @throws MixedCurrencyException      the lines are not all one currency -- 409
     * @throws TotalTooLargeException      the sum exceeds the column -- 422
     */
    public Goods priceGoods(List<Ask> asked, Map<UUID, CatalogPricingClient.Entry> byVariant, String claimedCurrency) {
        List<PricedLine> lines = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO.setScale(MONEY_SCALE);
        BigDecimal discount = BigDecimal.ZERO.setScale(MONEY_SCALE);
        String currency = null;
        List<UUID> unsellable = new ArrayList<>();

        // Iterated over the REQUEST's lines, not over the response's entries:
        // two lines may name the same variant, and catalog was asked for
        // distinct ids. Zipping the response back by index would silently drop
        // the second line and undercharge by its whole value.
        for (Ask item : asked) {
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
            requireClaimHolds(item, price, entryCurrency, claimedCurrency);

            // A promotion takes a fixed amount off each PIECE, worked out by catalog and stored per line; it is named only where it discounts.
            BigDecimal discountPerPiece = entry.discountOrZero().setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            UUID promotionId = discountPerPiece.signum() > 0 ? entry.promotionId() : null;
            lines.add(new PricedLine(item.variantId(), entry.productId(), entry.variantTitle(),
                    item.quantity(), price, discountPerPiece, promotionId));
            subtotal = subtotal.add(
                    price.multiply(BigDecimal.valueOf(item.quantity()))
                            .setScale(MONEY_SCALE, RoundingMode.HALF_UP));
            discount = discount.add(
                    discountPerPiece.multiply(BigDecimal.valueOf(item.quantity()))
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

        return new Goods(List.copyOf(lines), subtotal, discount, currency);
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
    private void requireClaimHolds(Ask item,
                                   BigDecimal catalogPrice,
                                   String catalogCurrency,
                                   String claimedCurrency) {
        if (item.claimedUnitPrice() != null) {
            BigDecimal claimed = item.claimedUnitPrice().setScale(MONEY_SCALE, RoundingMode.HALF_UP);
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
