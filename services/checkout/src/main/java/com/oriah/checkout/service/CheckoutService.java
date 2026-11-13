package com.oriah.checkout.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.oriah.checkout.client.CartClient;
import com.oriah.checkout.client.CatalogPricingClient;
import com.oriah.checkout.client.CustomerClient;
import com.oriah.checkout.client.OrderClient;
import com.oriah.checkout.client.ShippingClient;
import com.oriah.checkout.dto.ConfirmRequest;
import com.oriah.checkout.dto.ConfirmResponse;
import com.oriah.checkout.dto.PreviewRequest;
import com.oriah.checkout.dto.PreviewResponse;
import com.oriah.checkout.exception.BagNotPurchasableException;
import com.oriah.checkout.exception.CartEmptyException;
import com.oriah.checkout.exception.DownstreamRejectedException;
import com.oriah.checkout.exception.DownstreamUnavailableException;
import com.oriah.checkout.exception.MixedCurrencyException;
import com.oriah.checkout.exception.PricingChangedException;
import com.oriah.checkout.exception.RefusalPassedOnException;
import com.oriah.checkout.resilience.DownstreamProblems;
import com.oriah.checkout.resilience.ResilientFeignCaller;
import com.oriah.money.Ngwee;
import com.oriah.observability.ProblemTypes;
import com.oriah.quote.Quote;
import com.oriah.quote.QuoteDigest;
import com.oriah.quote.QuoteLine;

import lombok.extern.slf4j.Slf4j;

/**
 * Orchestrator for checkout: quote, then place (Phase 7).
 *
 * <p>Preview is a priced QUOTE. It prices the bag from catalog -- the LIST price of each piece and what a promotion takes off it, worked out per piece --
 * and, once the shopper has chosen how the pieces reach them (an option, a town, who pays the courier), asks SHIPPING what that costs and shows the total
 * and the digest of the whole quote. Confirm is the place-order sequence:
 * <ol>
 *   <li>Load the customer's cart and price it from catalog.</li>
 *   <li>Quote delivery with shipping, for the choice the shopper NAMED, on the goods after discount.</li>
 *   <li>Refuse with 409 unless the digest of that quote is the one preview showed (Q24: "before we commit, we all know the price").</li>
 *   <li>Place the order. Order prices everything again, checks the same digest, and takes a HOLD on the pieces at inventory.</li>
 *   <li>Clear the cart: the hold is the commitment.</li>
 * </ol>
 * Paying is the step after: the shopper pays by mobile money or bank transfer and gives order the reference, and staff confirm the money. Checkout never
 * touches money, so it has no card, no payment intent, and nothing to reconcile.
 *
 * <h2>Preview degrades, confirm does not</h2>
 *
 * Preview reads the cart with {@link ResilientFeignCaller#call} and treats a missing cart as an empty one. Confirm places an order that holds stock, so it
 * uses {@link ResilientFeignCaller#callOrThrow} and converts every failure into a status. Pricing is never degraded on either path: a quote nobody stands
 * behind is worse than no quote.
 *
 * <h2>What a shopper can be told, and what stays a 500</h2>
 *
 * Every failure carries a status a client can branch on: 409 an empty cart, a quote that changed, a piece gone or a limit reached (order's and shipping's own
 * refusals are passed on under THEIR names, {@code shortages} included), 503 a dependency unreachable (with {@code Retry-After}), 500 our bug. Nothing
 * auto-retries the placement unless a second attempt cannot do anything twice: the gateway's retry filter is GET-only, and Resilience4j repeats an outbound call
 * only when its request never arrived or the downstream said it is unavailable without asking for time. The browser's {@code Idempotency-Key} is forwarded to
 * order, which makes the placement itself safe to repeat.
 */
@Service
@Slf4j
public class CheckoutService {

    private static final BigDecimal NO_DISCOUNT = new BigDecimal("0.00");

    /**
     * The refusals of ORDER (inventory's, passed through it) that the shopper can act on, and that keep their own name when checkout passes them on: no
     * stock for a piece or its box, too many holds, a repeated key for another bag, a product with no box or no longer for sale. Any other 409 from order is
     * not something checkout knows how to explain.
     */
    private static final Set<String> ORDER_REFUSALS_FOR_THE_SHOPPER = Set.of(
            "insufficient-stock", "hold-limit-reached", "idempotency-key-reused", "no-default-box", "not-a-sellable-product", "product-state-not-received");

    /** The refusals of SHIPPING the shopper can act on: choose another town, option or payer. */
    private static final Set<String> SHIPPING_REFUSALS_FOR_THE_SHOPPER = Set.of("town-not-offered", "payer-not-offered", "town-not-found");

    private final CartClient cart;
    private final CatalogPricingClient catalog;
    private final CustomerClient customer;
    private final ShippingClient shipping;
    private final OrderClient order;
    private final ResilientFeignCaller resilient;

    public CheckoutService(CartClient cart, CatalogPricingClient catalog, ShippingClient shipping, OrderClient order, CustomerClient customer,
                           ResilientFeignCaller resilient) {
        this.cart = cart;
        this.catalog = catalog;
        this.customer = customer;
        this.shipping = shipping;
        this.order = order;
        this.resilient = resilient;
    }

    /** One piece as catalog prices it: the LIST price, and what a promotion takes off (zero, with no promotion id, when none applies). */
    private record PricedLine(UUID variantId, String title, int quantity, BigDecimal unitPrice, BigDecimal discountPerPiece, UUID promotionId) {

        BigDecimal netUnitPrice() {
            return unitPrice.subtract(discountPerPiece);
        }
    }

    /** What shipping priced: the amount that goes on the order, and the town and payer it resolved (null for self-collect). */
    private record Delivery(BigDecimal amount, UUID townId, String payer) {
    }

    /** The bag as catalog prices it: its lines, their sums, one currency. */
    private record RepricedBag(List<PricedLine> lines, BigDecimal subtotal, BigDecimal discount, String currency) {

        /** The goods AFTER discount: what a free-delivery threshold is judged on. */
        BigDecimal goodsAfterDiscount() {
            return subtotal.subtract(discount);
        }

        /**
         * The whole payable quote for this bag and one delivery -- the shared {@link Quote}, so preview, confirm and order all hash the same thing the same
         * way. A promotion is named on the line it discounts, and the town and payer are part of what the shopper agreed to.
         */
        Quote quoteFor(String deliveryOption, Delivery delivery) {
            boolean collect = Quote.SELF_COLLECT.equals(deliveryOption);
            return new Quote(lines.stream()
                    .map(l -> new QuoteLine(l.variantId(), l.quantity(), l.unitPrice(), l.discountPerPiece(), l.promotionId()))
                    .toList(), currency, deliveryOption, delivery.amount(), collect ? null : delivery.townId(), collect ? null : delivery.payer());
        }

        List<UUID> variantIds() {
            return lines.stream().map(PricedLine::variantId).toList();
        }

        List<PreviewResponse.LineDto> views() {
            return lines.stream().map(l -> new PreviewResponse.LineDto(l.variantId(), l.title(), l.quantity(), l.unitPrice(), l.discountPerPiece(),
                    l.promotionId(), l.netUnitPrice())).toList();
        }
    }

    /**
     * What the bag really costs, priced by catalog rather than taken on trust.
     *
     * <p>The amount is derived here, first-hand, from the same authority cart uses: a service that places an order must not depend on another service's
     * arithmetic being right. Every line, or none: a line catalog will not price refuses the whole bag rather than being dropped from it (dropping it
     * would place an order the shopper did not agree to), and mixed currencies refuse too -- summing two units is meaningless.
     */
    private RepricedBag reprice(CartClient.CartView myCart) {
        List<UUID> variantIds = myCart.items().stream().map(CartClient.CartItem::variantId).distinct().toList();

        // mustCall, not safeCall: a preview or a confirm that cannot price the bag must fail rather than show a number nobody stands behind. Nothing has
        // been ordered at this point, so the failure is classified as transient and is safe to retry.
        var response = mustCall(() -> catalog.price(new CatalogPricingClient.PricingRequest(variantIds)), "catalog");

        Map<UUID, CatalogPricingClient.Entry> byVariant = response.results() == null
                ? Map.of()
                : response.results().stream()
                        .filter(e -> e.variantId() != null)
                        .collect(java.util.stream.Collectors.toMap(CatalogPricingClient.Entry::variantId, e -> e, (a, b) -> a));

        List<PricedLine> lines = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal discount = BigDecimal.ZERO;
        String currency = null;
        for (CartClient.CartItem item : myCart.items()) {
            CatalogPricingClient.Entry entry = byVariant.get(item.variantId());
            if (entry == null || !entry.isPriced()) {
                log.warn("Refusing checkout: variantId={} prices as {}", item.variantId(), entry == null ? "(absent from the pricing response)" : entry.status());
                throw new BagNotPurchasableException(List.of(item.variantId()));
            }
            if (currency == null) {
                currency = entry.currency();
            } else if (!currency.equals(entry.currency())) {
                log.warn("Refusing checkout: bag mixes {} and {}", currency, entry.currency());
                throw new MixedCurrencyException(currency, entry.currency());
            }
            int quantity = item.quantity() == null ? 0 : item.quantity();
            BigDecimal discountPerPiece = entry.discountOrZero().setScale(2, java.math.RoundingMode.UNNECESSARY);
            subtotal = subtotal.add(entry.unitPrice().multiply(BigDecimal.valueOf(quantity)));
            discount = discount.add(discountPerPiece.multiply(BigDecimal.valueOf(quantity)));
            // Catalog's label, not the stored one: the order is the record of what was sold, and it should say what the shop calls it now.
            lines.add(new PricedLine(item.variantId(), entry.variantTitle(), quantity, entry.unitPrice(), discountPerPiece,
                    discountPerPiece.signum() > 0 ? entry.promotionId() : null));
        }
        return new RepricedBag(List.copyOf(lines), subtotal.setScale(2, java.math.RoundingMode.UNNECESSARY), discount.setScale(2, java.math.RoundingMode.UNNECESSARY),
                currency);
    }

    /**
     * Price the caller's bag and -- for the delivery the shopper chose -- the whole quote: its total and the digest of exactly it.
     *
     * <p>No delivery chosen gives the priced bag and NO total and NO digest: there is nothing to agree to until the shopper has chosen how the piece
     * reaches them. A choice shipping does not offer (a town not yet priced, a courier nobody may pay) is its own refusal, passed on, not a silent "no quote".
     */
    public PreviewResponse preview(PreviewRequest req) {
        // One R4J instance per Feign client, so when cart-service is unhealthy, ALL cart.* calls trip the same circuit.
        var myCart = safeCall(cart::getMyCart, "cart");
        if (myCart == null || myCart.items() == null || myCart.items().isEmpty()) {
            // Nothing to quote: confirm refuses an empty bag on its own terms (CartEmptyException), so no digest is needed to strand anyone.
            return new PreviewResponse(BigDecimal.ZERO, BigDecimal.ZERO, null, null, null, null, null, null, null, List.of());
        }
        // Repriced here too, not just in confirm, through the same code: a bag previewing one number and being placed at another is the bug this service
        // exists to prevent.
        RepricedBag priced = reprice(myCart);
        if (req.shippingAddressId() != null) {
            requireOwnAddress(req.shippingAddressId());
        }
        if (req.deliveryOption() == null) {
            return new PreviewResponse(priced.subtotal(), priced.discount(), null, null, priced.currency(), null, null, null, null, priced.views());
        }
        Delivery delivery = quoteDelivery(req.deliveryOption(), req.deliveryTownId(), req.payer(), priced);
        Quote quote = priced.quoteFor(req.deliveryOption(), delivery);
        return new PreviewResponse(quote.subtotal(), quote.discount(), quote.deliveryAmount(), quote.total(), priced.currency(), quote.deliveryOption(),
                quote.deliveryTownId(), quote.payer(), QuoteDigest.of(quote), priced.views());
    }

    /**
     * The caller's own address, or a 400. Customer lists only the caller's own, so "not yours" and "does not exist" are indistinguishable here. Order checks
     * it again with the caller's token before it prices anything.
     *
     * @throws IllegalArgumentException the id is not one of the caller's addresses -- 400
     */
    private void requireOwnAddress(UUID shippingAddressId) {
        // mustCall: a checkout that cannot establish whose address it is must not guess. Nothing has been ordered at this point.
        List<CustomerClient.Address> addresses = mustCall(customer::myAddresses, "customer");
        if (addresses.stream().noneMatch(a -> shippingAddressId.equals(a.id()))) {
            log.warn("Refusing checkout: addressId={} is not one of the caller's", shippingAddressId);
            throw new IllegalArgumentException("That shipping address is not one of yours.");
        }
    }

    /**
     * What delivery to this town by this option costs, asked of SHIPPING (the single place that works the amount out). Never a quiet zero:
     *
     * <ul>
     *   <li>shipping does not answer, or answers 5xx -> 503, nothing ordered. (It used to degrade to free delivery; a price the shopper never agreed is not
     *       a safe direction to be wrong in.)</li>
     *   <li>shipping refuses the choice (409 {@code town-not-offered} / {@code payer-not-offered}, 404 {@code town-not-found}) -> passed on under its
     *       own name: the shopper can choose another;</li>
     *   <li>shipping refuses any other way, or answers something unusable (not whole ngwee, another currency, a courier the recipient pays that still costs
     *       something) -> 500, our fault, never rounded into a quote.</li>
     * </ul>
     */
    private Delivery quoteDelivery(String option, UUID townId, String payer, RepricedBag priced) {
        ShippingClient.QuoteReply reply;
        try {
            reply = resilient.callOrThrow("shipping", () -> shipping.quote(
                    new ShippingClient.QuoteAsk(townId, option, payer, priced.goodsAfterDiscount(), priced.currency())));
        } catch (RuntimeException e) {
            int answered = ResilientFeignCaller.answeredStatus(e);
            if (isOutage(answered)) {
                throw new DownstreamUnavailableException("shipping", e);
            }
            var problem = DownstreamProblems.of(e);
            if ((answered == 409 || answered == 404) && problem.filter(p -> SHIPPING_REFUSALS_FOR_THE_SHOPPER.contains(p.code())).isPresent()) {
                log.info("409 shipping does not offer {} to {}: {}", option, townId, problem.get().code());
                throw new RefusalPassedOnException(HttpStatus.CONFLICT, problem.get().code(), problem.get().detail(), problem.get().extras());
            }
            log.error("shipping REFUSED a {} quote for {} to {} with {} -- configuration mismatch or shipping's own failure, not an outage",
                    priced.currency(), option, townId, answered, e);
            throw new DownstreamRejectedException("shipping", answered, e);
        }
        if (reply == null || reply.amount() == null) {
            throw new DownstreamRejectedException("shipping");
        }
        if (reply.currency() == null || !reply.currency().equalsIgnoreCase(priced.currency())) {
            // Adding a dollar cost to a Kwacha total as though the two were one unit is a configuration mismatch between two of our own services. 500, not a
            // 409 and not a silent zero -- nothing the shopper did caused it.
            log.error("shipping quoted {} for a {} bag -- refusing rather than adding one currency to another", reply.currency(), priced.currency());
            throw new DownstreamRejectedException("shipping");
        }
        BigDecimal amount = reply.amount();
        if (amount.signum() < 0 || !Ngwee.isWholeNgwee(amount)) {
            log.error("shipping quoted an unusable cost {} for option {}", amount, option);
            throw new DownstreamRejectedException("shipping");
        }
        String resolvedPayer = reply.payer();
        boolean knownPayer = resolvedPayer == null || Quote.ORIAH_COLLECTS.equals(resolvedPayer) || Quote.RECIPIENT_PAYS.equals(resolvedPayer);
        if (!knownPayer || (Quote.RECIPIENT_PAYS.equals(resolvedPayer) && amount.signum() != 0)) {
            log.error("shipping quoted payer {} with cost {} for {}: unusable", resolvedPayer, amount, option);
            throw new DownstreamRejectedException("shipping");
        }
        return new Delivery(amount, reply.townId() != null ? reply.townId() : townId, resolvedPayer);
    }

    /**
     * Run a Feign call with the full Resilience4j decoration and return null on failure. For {@link #preview}'s cart read only: a null here means "no data,
     * carry on", which is right for a read and wrong for anything that places an order.
     */
    private <T> T safeCall(java.util.function.Supplier<T> call, String name) {
        return resilient.call(name, call, null);
    }

    /**
     * The confirm-path counterpart of {@link #safeCall}: same decoration, but a failure becomes a typed exception instead of a null.
     *
     * <ul>
     *   <li><strong>It answered 4xx</strong> -> it rejected what we sent, which is a checkout bug: {@link DownstreamRejectedException}, 500. Not a 503,
     *       because the same payload will be rejected on every retry.</li>
     *   <li><strong>It never answered</strong>, or answered 5xx, or the circuit is open -> {@link DownstreamUnavailableException}, 503 with
     *       {@code Retry-After}.</li>
     *   <li><strong>It answered 2xx with no body</strong> -> also {@link DownstreamRejectedException}, rather than an NPE and an unnamed 500.</li>
     * </ul>
     */
    private <T> T mustCall(java.util.function.Supplier<T> call, String name) {
        T result;
        try {
            result = resilient.callOrThrow(name, call);
        } catch (RuntimeException e) {
            throw classify(name, e);
        }
        if (result == null) {
            throw new DownstreamRejectedException(name);
        }
        return result;
    }

    private RuntimeException classify(String name, RuntimeException cause) {
        int answered = ResilientFeignCaller.answeredStatus(cause);
        if (isOutage(answered)) {
            return new DownstreamUnavailableException(name, cause);
        }
        return new DownstreamRejectedException(name, answered, cause);
    }

    /**
     * Whether a failed call is an OUTAGE -- worth a 503 and a retry -- rather than an answer that will be the same next time.
     *
     * <p>No answer at all, or 502, 503 or 504: the downstream could not be reached or said it is unavailable. Everything else it ANSWERED is final: a 4xx
     * rejects what we sent, and a 500 is the downstream's own failure -- order raises one on purpose for its own misconfiguration (a wrong client secret).
     * Telling a shopper to "try again shortly" about that would invite them to, for ever; and order.place is a POST, so a 500 that came after the order was
     * saved must not be retried into a second one. The retry rule ({@code RetryForAnyCall}) repeats the same three statuses unless they carry
     * {@code Retry-After}, and of "no answer" only a connection never opened.
     */
    private static boolean isOutage(int answered) {
        return answered < 0 || answered == 502 || answered == 503 || answered == 504;
    }

    /**
     * Place the cart's contents -- exactly the quote the shopper saw, or nothing -- and hold its pieces.
     *
     * @throws CartEmptyException              409, nothing to buy
     * @throws PricingChangedException         409, the quote changed since preview
     * @throws RefusalPassedOnException        409, a refusal of order's or shipping's the shopper can act on, under its own name
     * @throws DownstreamUnavailableException  503, a dependency is down
     * @throws DownstreamRejectedException     500, a dependency refused our request
     *
     * @param idempotencyKey the browser's {@code Idempotency-Key}, forwarded verbatim to order so that a retry of the same submission places the same order.
     *     May be null -- deliberately not generated here, because a key minted per attempt guards nothing (a reload of the same bag still finds the live
     *     order, by the quote's digest).
     */
    public ConfirmResponse confirm(ConfirmRequest req, String idempotencyKey) {
        var myCart = mustCall(cart::getMyCart, "cart");
        if (myCart.items() == null || myCart.items().isEmpty()) {
            throw new CartEmptyException();
        }

        // 1) Price the bag from catalog -- never cart's subtotal.
        RepricedBag priced = reprice(myCart);

        // 2) Whose address it is, when there is one. (Order asks again, with the caller's own token, before it prices anything.)
        if (req.shippingAddressId() != null) {
            requireOwnAddress(req.shippingAddressId());
        }

        // 3) Delivery: the choice the shopper NAMED, priced by shipping on the goods after discount. A choice not offered any more -- withdrawn since preview,
        //    or never offered -- has no price to place at: the refusal is passed on so the storefront shows what IS offered.
        Delivery delivery = quoteDelivery(req.deliveryOption(), req.deliveryTownId(), req.payer(), priced);

        // 4) Quote, then commit: only the quote the shopper saw can be placed.
        Quote quote = priced.quoteFor(req.deliveryOption(), delivery);
        requireTheQuoteTheShopperSaw(req.quoteDigest(), quote, priced);

        // 5) Place the order from the REPRICED lines. Order prices everything again, checks the same digest before it saves anything, and holds the pieces.
        OrderClient.OrderResponse placed = place(req, priced, idempotencyKey);
        log.info("Order placed orderId={} status={} total={} held until {}", placed.id(), placed.status(), placed.total(), placed.holdExpiresAt());
        requireOrderAgrees(placed, quote);

        // 6) Clear the cart -- best-effort: the order is placed and its pieces held, and the shopper owns it, so a stale cart is drift to reconcile, not a failure.
        safeCall(() -> { cart.clearMyCart(); return null; }, "cart");

        return new ConfirmResponse(placed.id(), placed.status(), placed.total(), priced.currency(), placed.holdExpiresAt(),
                "Your order is placed and its pieces are held for you until the time shown. Pay by mobile money or bank transfer, then give us the reference.");
    }

    /**
     * Refuses a confirm whose quote is not the one the shopper was shown: a quantity edited in another tab, a price, a promotion or a delivery price changed
     * since preview. 409 before anything is ordered.
     *
     * <p>The digest is only ever COMPARED, never used as an input to a price, so a forged value buys nothing: the worst a client can do with a wrong one is
     * refuse its own checkout.
     */
    private static void requireTheQuoteTheShopperSaw(String echoed, Quote quote, RepricedBag priced) {
        if (!QuoteDigest.of(quote).equals(echoed)) {
            log.info("409 the quote changed since the shopper saw it");
            throw new PricingChangedException(priced.variantIds());
        }
    }

    /**
     * Places the order, passing order's refusals on: "the quote changed" as checkout's own pricing-changed, a piece gone or a limit reached under order's own
     * name (recognised by its problem TYPE, so another 409 from order is not mistaken for either). Each used to become a generic 500 for an answer the shopper
     * can act on.
     */
    private OrderClient.OrderResponse place(ConfirmRequest req, RepricedBag priced, String idempotencyKey) {
        OrderClient.OrderResponse placed;
        List<OrderClient.LineItem> items = priced.lines().stream()
                .map(l -> new OrderClient.LineItem(l.variantId(), l.title(), l.quantity(), l.unitPrice())).toList();
        try {
            placed = resilient.callOrThrow("order", () -> order.place(idempotencyKey, new OrderClient.PlaceOrderRequest(
                    req.shippingAddressId(), priced.currency(), items, req.deliveryOption(), req.deliveryTownId(), req.payer(), req.quoteDigest())));
        } catch (RuntimeException e) {
            java.util.Optional<java.net.URI> type = ResilientFeignCaller.answeredProblemType(e);
            if (type.filter(ProblemTypes.PRICING_CHANGED::equals).isPresent()) {
                log.info("409 order found the quote changed since the shopper saw it");
                throw new PricingChangedException(priced.variantIds());
            }
            if (type.filter(BagNotPurchasableException.TYPE::equals).isPresent()) {
                // order does not say which line; the bag itself shows the shopper which piece is no longer for sale.
                log.info("409 order found a line no longer sellable");
                throw new BagNotPurchasableException(List.of());
            }
            if (type.filter(MixedCurrencyException.TYPE::equals).isPresent()) {
                log.info("409 order found the bag mixes currencies");
                throw new MixedCurrencyException();
            }
            var problem = DownstreamProblems.of(e);
            if (ResilientFeignCaller.answeredStatus(e) == 409 && problem.filter(p -> ORDER_REFUSALS_FOR_THE_SHOPPER.contains(p.code())).isPresent()) {
                log.info("409 order refused the placement: {}", problem.get().code());
                throw new RefusalPassedOnException(HttpStatus.CONFLICT, problem.get().code(), problem.get().detail(), problem.get().extras());
            }
            throw classify("order", e);
        }
        if (placed == null) {
            throw new DownstreamRejectedException("order");
        }
        return placed;
    }

    /**
     * The order is for the total the shopper agreed to. The digests matched, so a difference here should be impossible -- which is exactly when carrying on
     * anyway would be a mistake nobody notices. 500, and the order is left for staff: it is HELD and will lapse.
     */
    private static void requireOrderAgrees(OrderClient.OrderResponse placed, Quote quote) {
        if (placed.total() == null || placed.total().compareTo(quote.total()) != 0) {
            log.error("order {} recorded total {} but the agreed quote totals {} -- refusing to go on", placed.id(), placed.total(), quote.total());
            throw new DownstreamRejectedException("order");
        }
    }
}
