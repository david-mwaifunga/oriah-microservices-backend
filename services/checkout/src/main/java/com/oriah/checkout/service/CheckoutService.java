package com.oriah.checkout.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.oriah.checkout.client.CartClient;
import com.oriah.checkout.client.CatalogPricingClient;
import com.oriah.checkout.client.CustomerClient;
import com.oriah.checkout.client.OrderClient;
import com.oriah.checkout.client.PaymentClient;
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
import com.oriah.checkout.exception.OutsideDeliveryAreaException;
import com.oriah.checkout.exception.PaymentCapturedWithoutOrderException;
import com.oriah.checkout.exception.PaymentConfirmationFailedException;
import com.oriah.checkout.exception.PaymentDeclinedException;
import com.oriah.checkout.exception.PricingChangedException;
import com.oriah.checkout.exception.PromotionCodesNotAcceptedException;
import com.oriah.checkout.resilience.ResilientFeignCaller;
import com.oriah.money.Ngwee;
import com.oriah.observability.ProblemTypes;
import com.oriah.quote.Quote;
import com.oriah.quote.QuoteDigest;
import com.oriah.quote.QuoteLine;

import lombok.extern.slf4j.Slf4j;

/**
 * Saga-style orchestrator for checkout: quote, then commit.
 *
 * <p>Preview is a priced QUOTE. It prices the bag from catalog, lists the
 * delivery options for the destination and -- once the shopper has chosen
 * one -- shows that option's charge, the total, and the digest of the whole
 * quote. Confirm is the place-order sequence:
 * <ol>
 *   <li>Load the customer's cart and price it from catalog.</li>
 *   <li>Quote delivery, and take the option the shopper NAMED.</li>
 *   <li>Refuse with 409 unless the digest of that quote is the one preview
 *       showed (Q24: "before we commit, we all know the price").</li>
 *   <li>Place the order. order prices everything again and checks the same
 *       digest before it saves anything.</li>
 *   <li>Create + confirm a PaymentIntent for the total ORDER recorded.</li>
 *   <li>On payment success: attach the intent to the order (PAID), clear the
 *       cart.</li>
 * </ol>
 *
 * <h2>Preview degrades, confirm does not</h2>
 *
 * <p>The two entry points treat a failed downstream call in opposite ways, and
 * the asymmetry is deliberate. Preview reads the cart with
 * {@link ResilientFeignCaller#call} and treats a missing cart as an empty one.
 * Confirm moves money, so it uses {@link ResilientFeignCaller#callOrThrow} and
 * converts every failure into a status. Pricing is never degraded on either
 * path: a quote nobody stands behind is worse than no quote.
 *
 * <p>Confirm did not always do that. Every one of its failure branches
 * returned <strong>{@code 200 OK}</strong> with the reason in an untyped
 * {@code message} string, and the storefront fires
 * {@code toast.success("Order placed")} on any 200 -- so a <em>declined
 * card</em> and <em>money captured with no order</em> showed the shopper a
 * green success toast. Every failure now carries a status a client can branch
 * on, chosen so the retryable ones are retryable and the final ones are not:
 * 409 empty cart or a quote that changed, 422 a promotion code or an address
 * abroad, 503 downstream unreachable (with {@code Retry-After}), 402 declined
 * card, 502 payment unconfirmable, 500 our bug or money stranded.
 * {@code ConfirmFailurePathsTest} pins one per branch.
 *
 * <p>Nothing auto-retries this endpoint, and that is correct: the gateway's
 * retry filter is GET-only, so a POST that moves money is never replayed for
 * the shopper. Resilience4j retries each <em>outbound</em> call on a transport
 * fault or a downstream 5xx, so a 503 out of here means that already failed.
 *
 * <p>This is still not a "real" saga with compensating actions across
 * services. A captured payment that cannot be attached to an order answers 500
 * and names the intent to reconcile; closing that properly needs a
 * refund-or-retry compensation and is tracked separately.
 */
@Service
@Slf4j
public class CheckoutService {

    /** Oriah delivers within Zambia only, so a quote with no address is for here. */
    static final String HOME_COUNTRY = "ZM";

    private static final BigDecimal NO_DISCOUNT = new BigDecimal("0.00");

    private final CartClient cart;
    private final CatalogPricingClient catalog;
    private final CustomerClient customer;
    private final ShippingClient shipping;
    private final PaymentClient payment;
    private final OrderClient order;
    private final ResilientFeignCaller resilient;

    public CheckoutService(CartClient cart, CatalogPricingClient catalog,
                           ShippingClient shipping,
                           PaymentClient payment, OrderClient order,
                           CustomerClient customer,
                           ResilientFeignCaller resilient) {
        this.cart = cart;
        this.catalog = catalog;
        this.customer = customer;
        this.shipping = shipping;
        this.payment = payment;
        this.order = order;
        this.resilient = resilient;
    }

    /**
     * What the bag really costs, priced by catalog rather than taken on trust.
     *
     * <h2>This replaced the single most important line in the service</h2>
     *
     * {@code confirm} read {@code BigDecimal subtotal = myCart.subtotal();} and
     * that one field was the sole input to the charged total. Cart stored the
     * price the shopper sent, so a request naming one cent produced a one-cent
     * charge. A service that charges a card must not depend on another
     * service's arithmetic being right, so the amount is derived here,
     * first-hand, from the same authority cart uses.
     *
     * <h2>Every line, or none</h2>
     *
     * A line catalog will not price refuses the whole bag rather than being
     * dropped from it: dropping it would place an order the shopper did not
     * agree to, and charging around it would ship it free. Mixed currencies
     * refuse too -- summing two units is meaningless.
     */
    private RepricedBag reprice(CartClient.CartView myCart) {
        List<UUID> variantIds = myCart.items().stream()
                .map(CartClient.CartItem::variantId)
                .distinct()
                .toList();

        // mustCall, not safeCall: a preview or a confirm that cannot price the
        // bag must fail rather than show or charge a number nobody stands
        // behind. Nothing has been ordered or charged at this point, so the
        // failure is classified as transient and is safe to retry.
        var response = mustCall(() -> catalog.price(
                new CatalogPricingClient.PricingRequest(variantIds)), "catalog");

        Map<UUID, CatalogPricingClient.Entry> byVariant = response.results() == null
                ? Map.of()
                : response.results().stream()
                        .filter(e -> e.variantId() != null)
                        .collect(java.util.stream.Collectors.toMap(
                                CatalogPricingClient.Entry::variantId, e -> e, (a, b) -> a));

        List<OrderClient.LineItem> lines = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO;
        String currency = null;
        for (CartClient.CartItem item : myCart.items()) {
            CatalogPricingClient.Entry entry = byVariant.get(item.variantId());
            if (entry == null || !entry.isPriced()) {
                log.warn("Refusing checkout: variantId={} prices as {}", item.variantId(),
                        entry == null ? "(absent from the pricing response)" : entry.status());
                throw new BagNotPurchasableException(List.of(item.variantId()));
            }
            if (currency == null) {
                currency = entry.currency();
            } else if (!currency.equals(entry.currency())) {
                log.warn("Refusing checkout: bag mixes {} and {}", currency, entry.currency());
                throw new MixedCurrencyException(currency, entry.currency());
            }
            int quantity = item.quantity() == null ? 0 : item.quantity();
            subtotal = subtotal.add(entry.unitPrice().multiply(BigDecimal.valueOf(quantity)));
            // Catalog's label, not the stored one: the order is the record of
            // what was sold, and it should say what the shop calls it now.
            lines.add(new OrderClient.LineItem(
                    item.variantId(), entry.variantTitle(), quantity, entry.unitPrice()));
        }
        return new RepricedBag(List.copyOf(lines), subtotal, currency);
    }

    /** The bag as catalog prices it: order lines, their sum, one currency. */
    private record RepricedBag(List<OrderClient.LineItem> lines, BigDecimal subtotal, String currency) {

        /**
         * The whole payable quote for this bag and one delivery option -- the
         * shared {@link Quote}, so preview, confirm and order all hash the same
         * thing the same way. No per-piece discount until Phase 7.
         */
        Quote quoteFor(String deliveryOption, BigDecimal deliveryAmount) {
            return new Quote(lines.stream()
                    .map(l -> new QuoteLine(l.variantId(), l.quantity(), l.unitPrice(), BigDecimal.ZERO))
                    .toList(), currency, deliveryOption, deliveryAmount);
        }

        List<UUID> variantIds() {
            return lines.stream().map(OrderClient.LineItem::variantId).toList();
        }
    }

    /**
     * Price the caller's bag, list the delivery options, and -- for the option
     * the shopper chose -- the total and the digest of that whole quote.
     *
     * <p>No option chosen, or one that is not offered for this destination,
     * gives the options and NO total and NO digest: there is nothing to agree
     * to until the shopper has chosen how the piece reaches them. Showing the
     * cheapest as "the" total is how a K0 option used to make every delivery
     * free.
     */
    public PreviewResponse preview(PreviewRequest req) {
        refusePromotionCode(req.promotionCode());

        // One R4J instance per Feign client (not per method), so when
        // cart-service is unhealthy, ALL cart.* calls trip the same circuit.
        var myCart = safeCall(cart::getMyCart, "cart");
        if (myCart == null || myCart.items() == null || myCart.items().isEmpty()) {
            // Nothing to quote: confirm refuses an empty bag on its own terms
            // (CartEmptyException), so no digest is needed to strand anyone.
            return new PreviewResponse(
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    null, List.of(), null, null);
        }
        // Repriced here too, not just in confirm, through the same code: a bag
        // previewing one number and being charged another is the bug this
        // service exists to prevent.
        RepricedBag priced = reprice(myCart);

        // The ADDRESS's country, never the body's, whenever there is an
        // address -- so preview and confirm quote the same place, and an
        // address abroad is refused here, before the shopper has typed a card.
        String destination = resolveDestination(req.shippingAddressId(),
                req.destinationCountry() == null ? HOME_COUNTRY : req.destinationCountry());
        var rates = quoteShipping(destination, priced.subtotal(), priced.currency());
        requireQuotedInBagCurrency(rates, priced.currency());

        List<PreviewResponse.ShippingOptionDto> options = rates.options() == null
                ? List.of()
                : rates.options().stream()
                        .map(o -> new PreviewResponse.ShippingOptionDto(
                                o.code(), o.carrier(), o.service(), o.cost(), o.etaDaysMin(), o.etaDaysMax()))
                        .toList();

        Optional<ShippingClient.ShippingOption> chosen = offered(rates, req.deliveryOption());
        if (chosen.isEmpty()) {
            if (req.deliveryOption() != null) {
                log.info("Preview: option {} is not offered for {}, so no quote", req.deliveryOption(), destination);
            }
            return new PreviewResponse(priced.subtotal(), NO_DISCOUNT, null, null,
                    priced.currency(), options, null, null);
        }

        Quote quote = priced.quoteFor(chosen.get().code(), deliveryCost(chosen.get()));
        return new PreviewResponse(quote.subtotal(), quote.discount(), quote.deliveryAmount(), quote.total(),
                priced.currency(), options, quote.deliveryOption(), QuoteDigest.of(quote));
    }

    /**
     * Promotion codes are retired until Phase 7's scoped promotions (MVP_PLAN
     * Phase 0, item 4.2) -- refused out loud, before anything is read, priced,
     * ordered or charged. Silently ignoring one would leave a shopper believing
     * a discount was applied. A blank code is no code.
     */
    private static void refusePromotionCode(String code) {
        if (code != null && !code.isBlank()) {
            log.info("422 a promotion code was sent; codes are not accepted until Phase 7");
            throw new PromotionCodesNotAcceptedException();
        }
    }

    /** The option with exactly this code, if shipping offered it. */
    private static Optional<ShippingClient.ShippingOption> offered(ShippingClient.RateResponse rates,
                                                                   String code) {
        if (code == null || rates.options() == null) {
            return Optional.empty();
        }
        return rates.options().stream().filter(o -> code.equals(o.code())).findFirst();
    }

    /**
     * An option's charge, as money we can stand behind: present, not negative,
     * and in whole ngwee. Anything else is shipping answering with something
     * unusable -- our fault, 500 -- and never rounded into a quote.
     */
    private static BigDecimal deliveryCost(ShippingClient.ShippingOption option) {
        BigDecimal cost = option.cost();
        if (cost == null || cost.signum() < 0 || !Ngwee.isWholeNgwee(cost)) {
            log.error("shipping quoted an unusable cost {} for option {}", cost, option.code());
            throw new DownstreamRejectedException("shipping");
        }
        return cost;
    }

    /**
     * The country to quote shipping for: the one on the address being shipped
     * to, never the request body's.
     *
     * <p>The body's country decided the zone once, and nothing reconciled it
     * with {@code shippingAddressId} -- a caller could pay one zone's rate to
     * ship to another. So the body's value is used only when there is no
     * address at all: a preview, or a self-collect confirm, where the answer is
     * Zambia.
     *
     * @throws IllegalArgumentException the id is not one of the caller's
     *         addresses -- 400. customer-service lists only the caller's own,
     *         so "not yours" and "does not exist" are indistinguishable here.
     */
    private String resolveDestination(UUID shippingAddressId, String fallbackCountry) {
        if (shippingAddressId == null) {
            return fallbackCountry;
        }
        // mustCall: the destination decides money, so a checkout that cannot
        // establish it must not guess. Nothing is charged at this point.
        List<CustomerClient.Address> addresses = mustCall(customer::myAddresses, "customer");
        return addresses.stream()
                .filter(a -> shippingAddressId.equals(a.id()))
                .map(CustomerClient.Address::country)
                .findFirst()
                .orElseThrow(() -> {
                    log.warn("Refusing checkout: addressId={} is not one of the caller's", shippingAddressId);
                    return new IllegalArgumentException("That shipping address is not one of yours.");
                });
    }

    /**
     * The delivery options for a destination. Never null, and never a quiet
     * zero (Phase 0, item 1):
     *
     * <ul>
     *   <li>shipping refuses with {@code outside-delivery-area} -- an address
     *       abroad -> {@link OutsideDeliveryAreaException}, 422, in checkout's
     *       own words; the shopper can act on it;</li>
     *   <li>shipping refuses with any other 4xx -> we sent something wrong:
     *       {@link DownstreamRejectedException}, 500;</li>
     *   <li>shipping does not answer, or answers 5xx -> 503, nothing charged.
     *       This used to degrade to free delivery; a price the shopper never
     *       agreed is not a safe direction to be wrong in.</li>
     * </ul>
     */
    private ShippingClient.RateResponse quoteShipping(String country, BigDecimal subtotal, String currency) {
        ShippingClient.RateResponse rates;
        try {
            rates = resilient.callOrThrow("shipping", () -> shipping.rates(
                    new ShippingClient.RateRequest(country, subtotal, currency)));
        } catch (RuntimeException e) {
            int answered = ResilientFeignCaller.answeredStatus(e);
            if (isOutage(answered)) {
                throw new DownstreamUnavailableException("shipping", e);
            }
            if (ResilientFeignCaller.answeredProblemType(e)
                    .filter(ProblemTypes.OUTSIDE_DELIVERY_AREA::equals).isPresent()) {
                log.info("422 no delivery to {}: outside Zambia", country);
                throw new OutsideDeliveryAreaException();
            }
            log.error("shipping REFUSED a {} quote for {} with {} -- configuration "
                            + "mismatch or shipping's own failure, not an outage", currency, country, answered, e);
            throw new DownstreamRejectedException("shipping", answered, e);
        }
        if (rates == null) {
            throw new DownstreamRejectedException("shipping");
        }
        return rates;
    }

    /**
     * Refuses a shipping quote that is not denominated in the bag's currency:
     * adding a dollar cost to a Kwacha total as though the two were one unit
     * is a configuration mismatch between two of our own services. 500, not a
     * 409 and not a silent zero -- nothing the shopper did caused it.
     */
    private void requireQuotedInBagCurrency(ShippingClient.RateResponse rates, String bagCurrency) {
        if (rates == null || rates.options() == null) {
            return;
        }
        for (ShippingClient.ShippingOption option : rates.options()) {
            if (option.currency() != null && !option.currency().equalsIgnoreCase(bagCurrency)) {
                log.error("shipping quoted {} for a {} bag -- refusing rather than adding "
                        + "one currency to another", option.currency(), bagCurrency);
                throw new DownstreamRejectedException("shipping");
            }
        }
    }

    /**
     * Run a Feign call with the full Resilience4j decoration and return null on
     * failure. For {@link #preview}'s cart read only: a null here means "no
     * data, carry on", which is right for a read and wrong for anything that
     * moves money.
     */
    private <T> T safeCall(java.util.function.Supplier<T> call, String name) {
        return resilient.call(name, call, null);
    }

    /**
     * The confirm-path counterpart of {@link #safeCall}: same decoration, but
     * a failure becomes a typed exception instead of a null.
     *
     * <ul>
     *   <li><strong>It answered 4xx</strong> -> it rejected what we sent, which
     *       is a checkout bug: {@link DownstreamRejectedException}, 500. Not a
     *       503, because the same payload will be rejected on every retry.</li>
     *   <li><strong>It never answered</strong>, or answered 5xx, or the circuit
     *       is open -> {@link DownstreamUnavailableException}, 503 with
     *       {@code Retry-After}.</li>
     *   <li><strong>It answered 2xx with no body</strong> -> also
     *       {@link DownstreamRejectedException}, rather than an NPE and an
     *       unnamed 500.</li>
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
     * Whether a failed call is an OUTAGE -- worth a 503 and a retry -- rather
     * than an answer that will be the same next time.
     *
     * <p>No answer at all, or 502, 503 or 504: the downstream could not be
     * reached or said it is unavailable. Everything else it ANSWERED is final:
     * a 4xx rejects what we sent, and a 500 is the downstream's own failure --
     * order raises one on purpose for its own misconfiguration (a wrong client
     * secret, shipping refusing it). Telling a shopper to "try again shortly"
     * about that would invite them to, for ever; and order.place is a POST, so
     * a 500 that came after the order was saved must not be retried into a
     * second one. Resilience4j's retry-exceptions name the same three
     * statuses.
     */
    private static boolean isOutage(int answered) {
        return answered < 0 || answered == 502 || answered == 503 || answered == 504;
    }

    /**
     * Place and pay for the cart's contents -- exactly the quote the shopper
     * saw, or nothing.
     *
     * @throws PromotionCodesNotAcceptedException    422, a promotion code was sent
     * @throws CartEmptyException                    409, nothing to buy
     * @throws PricingChangedException               409, the quote changed since preview
     * @throws DownstreamUnavailableException        503, a dependency is down
     * @throws DownstreamRejectedException           500, a dependency refused our request
     * @throws PaymentDeclinedException              402, the card was refused
     * @throws PaymentConfirmationFailedException    502, the processor gave no usable answer
     * @throws PaymentCapturedWithoutOrderException  500, money moved and the order did not
     *
     * @param idempotencyKey the browser's {@code Idempotency-Key}, forwarded
     *     verbatim to payment-service so that a retry replays the stored
     *     response instead of charging again. May be null -- deliberately not
     *     generated here, because a key minted per attempt guards nothing.
     */
    public ConfirmResponse confirm(ConfirmRequest req, String idempotencyKey) {
        refusePromotionCode(req.promotionCode());

        var myCart = mustCall(cart::getMyCart, "cart");
        if (myCart.items() == null || myCart.items().isEmpty()) {
            throw new CartEmptyException();
        }

        // 1) Price the bag from catalog -- never cart's subtotal.
        RepricedBag priced = reprice(myCart);

        // 2) Delivery: the option the shopper NAMED. It used to be the cheapest
        //    offered, so a K0 option made every delivery free. An option not
        //    offered -- withdrawn since preview, or never offered -- has no
        //    price to charge: 409, the same answer order gives, so the
        //    storefront re-previews and shows what IS offered. With no address
        //    (self-collect, enforced by validation) the quote is for Zambia.
        String destination = resolveDestination(req.shippingAddressId(), HOME_COUNTRY);
        var rates = quoteShipping(destination, priced.subtotal(), priced.currency());
        requireQuotedInBagCurrency(rates, priced.currency());
        ShippingClient.ShippingOption chosen = offered(rates, req.deliveryOption()).orElseThrow(() -> {
            log.info("409 option {} is not offered for {}", req.deliveryOption(), destination);
            return new PricingChangedException(priced.variantIds());
        });

        // 3) Quote, then commit: only the quote the shopper saw can be charged.
        Quote quote = priced.quoteFor(chosen.code(), deliveryCost(chosen));
        requireTheQuoteTheShopperSaw(req.quoteDigest(), quote, priced);

        // 4) Place the order from the REPRICED lines. order prices everything
        //    again and checks the same digest before it saves anything.
        OrderClient.OrderResponse placed = place(req, priced);
        log.info("Order placed orderId={} status={} total={}", placed.id(), placed.status(), placed.total());
        requireOrderAgrees(placed, quote);

        // 5) Payment, for the total ORDER recorded. Nothing has been charged
        //    yet, so a failure up to and including createIntent is transient.
        BigDecimal total = placed.total();
        var intent = mustCall(() -> payment.createIntent(
                new PaymentClient.CreateIntentRequest(placed.id(), total, priced.currency())),
                "payment");

        PaymentClient.IntentResponse confirmed;
        try {
            confirmed = resilient.callOrThrow("payment", () -> payment.confirm(intent.id(),
                    idempotencyKey, new PaymentClient.ConfirmRequest(req.cardNumber())));
        } catch (RuntimeException e) {
            // 502, not 503 and not 402. The charge may or may not have gone
            // through, so this is the one ambiguous outcome: the order stays
            // CREATED and the cart stays intact, which keeps it recoverable,
            // and a retry is safe because confirmation is idempotent on the
            // intent.
            throw new PaymentConfirmationFailedException("the payment service did not answer", e);
        }
        if (confirmed == null) {
            throw new PaymentConfirmationFailedException("the payment service answered with no body", null);
        }
        if (!"SUCCEEDED".equals(confirmed.status())) {
            // Terminal: 402 and NOT retryable -- the same card will be refused again.
            log.info("Payment declined intentId={} status={} reason={}",
                    intent.id(), confirmed.status(), confirmed.failureReason());
            throw new PaymentDeclinedException(confirmed.failureReason());
        }
        log.info("Payment SUCCEEDED intentId={}", intent.id());

        // 6) Attach the intent and transition the order to PAID. Past this line
        //    the money is gone, so a failure must not be reported as transient:
        //    a 503 invites a retry that would charge the card a second time.
        OrderClient.OrderResponse paid;
        try {
            // Its OWN resilience instance, "order-pay". Placement shares "order",
            // and a burst of placement failures -- Keycloak or shipping briefly
            // down for other shoppers -- must never open the breaker in front of
            // the one call that turns a charged card into a paid order.
            paid = mustCall(() -> order.pay(placed.id(),
                    new OrderClient.AttachPaymentRequest(intent.id())), "order-pay");
        } catch (RuntimeException e) {
            log.error("PAYMENT CAPTURED WITHOUT ORDER orderId={} intentId={} amount={} -- "
                            + "needs manual reconciliation (refund the intent or replay the transition)",
                    placed.id(), intent.id(), total, e);
            throw new PaymentCapturedWithoutOrderException(placed.id(), intent.id(), e);
        }

        // 7) Clear the cart -- best-effort: the order is PAID and the shopper
        //    owns it, so a stale cart is drift to reconcile, not a failure.
        safeCall(() -> { cart.clearMyCart(); return null; }, "cart");

        return new ConfirmResponse(paid.id(), paid.status(), intent.id(),
                confirmed.status(), paid.total(), priced.currency(), "order placed and paid");
    }

    /**
     * Refuses a confirm whose quote is not the one the shopper was shown: a
     * quantity edited in another tab, a price or a delivery price changed
     * since preview. 409 before anything is ordered or charged.
     *
     * <p>The digest is only ever COMPARED, never used as an input to a price,
     * so a forged value buys nothing: the worst a client can do with a wrong
     * one is refuse its own checkout.
     */
    private static void requireTheQuoteTheShopperSaw(String echoed, Quote quote, RepricedBag priced) {
        if (!QuoteDigest.of(quote).equals(echoed)) {
            log.info("409 the quote changed since the shopper saw it");
            throw new PricingChangedException(priced.variantIds());
        }
    }

    /**
     * Places the order, passing order's own "the quote changed" on as
     * checkout's pricing-changed -- recognised by its problem TYPE, so another
     * 409 from order is not mistaken for it. It used to become a generic 500
     * for an answer the shopper can act on by reviewing their bag.
     */
    private OrderClient.OrderResponse place(ConfirmRequest req, RepricedBag priced) {
        OrderClient.OrderResponse placed;
        try {
            placed = resilient.callOrThrow("order", () -> order.place(new OrderClient.PlaceOrderRequest(
                    req.shippingAddressId(), priced.currency(), priced.lines(),
                    req.deliveryOption(), req.quoteDigest())));
        } catch (RuntimeException e) {
            // The three refusals the shopper can act on keep their type. Every
            // other answer is classified as usual.
            java.util.Optional<java.net.URI> type = ResilientFeignCaller.answeredProblemType(e);
            if (type.filter(ProblemTypes.PRICING_CHANGED::equals).isPresent()) {
                log.info("409 order found the quote changed since the shopper saw it");
                throw new PricingChangedException(priced.variantIds());
            }
            if (type.filter(BagNotPurchasableException.TYPE::equals).isPresent()) {
                // order does not say which line; the bag itself shows the
                // shopper which piece is no longer for sale.
                log.info("409 order found a line no longer sellable");
                throw new BagNotPurchasableException(List.of());
            }
            if (type.filter(MixedCurrencyException.TYPE::equals).isPresent()) {
                log.info("409 order found the bag mixes currencies");
                throw new MixedCurrencyException();
            }
            throw classify("order", e);
        }
        if (placed == null) {
            throw new DownstreamRejectedException("order");
        }
        return placed;
    }

    /**
     * The intent is for the total ORDER recorded, and only when it is the
     * total the shopper agreed to. The digests matched, so a difference here
     * should be impossible -- which is exactly when charging anyway would be a
     * mistake nobody notices. 500, and nothing is charged.
     */
    private static void requireOrderAgrees(OrderClient.OrderResponse placed, Quote quote) {
        if (placed.total() == null || placed.total().compareTo(quote.total()) != 0) {
            log.error("order {} recorded total {} but the agreed quote totals {} -- refusing to charge",
                    placed.id(), placed.total(), quote.total());
            throw new DownstreamRejectedException("order");
        }
    }
}
