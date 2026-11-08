package com.oriah.checkout.service;

import com.oriah.observability.ProblemTypes;
import com.oriah.checkout.exception.OutsideDeliveryAreaException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.oriah.checkout.client.CartClient;
import com.oriah.checkout.client.CatalogPricingClient;
import com.oriah.checkout.client.CustomerClient;
import com.oriah.checkout.client.OrderClient;
import com.oriah.checkout.client.PaymentClient;
import com.oriah.checkout.client.PromotionClient;
import com.oriah.checkout.client.ShippingClient;
import com.oriah.checkout.dto.ConfirmRequest;
import com.oriah.checkout.dto.ConfirmResponse;
import com.oriah.checkout.dto.PreviewRequest;
import com.oriah.checkout.dto.PreviewResponse;
import com.oriah.checkout.exception.BagNotPurchasableException;
import com.oriah.checkout.exception.CartEmptyException;
import com.oriah.checkout.exception.MixedCurrencyException;
import com.oriah.checkout.exception.PricingChangedException;
import com.oriah.checkout.exception.DownstreamRejectedException;
import com.oriah.checkout.exception.DownstreamUnavailableException;
import com.oriah.checkout.exception.PaymentCapturedWithoutOrderException;
import com.oriah.checkout.exception.PaymentConfirmationFailedException;
import com.oriah.checkout.exception.PaymentDeclinedException;
import com.oriah.checkout.exception.PromotionCodeInvalidException;
import com.oriah.checkout.resilience.ResilientFeignCaller;

import lombok.extern.slf4j.Slf4j;

/**
 * Saga-style orchestrator for checkout.
 *
 * <p>Preview is a pure read: cart subtotal + (optional) promo + shipping
 * options. Confirm is the place-order sequence:
 * <ol>
 *   <li>Load the customer's cart.</li>
 *   <li>Validate the promo (if any) and compute final total.</li>
 *   <li>Pick the cheapest shipping option for the destination.</li>
 *   <li>Place the order (CREATED status, items snapshot).</li>
 *   <li>Create + confirm a PaymentIntent for the total via FakePaymentGateway.</li>
 *   <li>On payment success: attach the intent to the order (transitions to PAID),
 *       redeem the promo, clear the cart.</li>
 * </ol>
 *
 * <h2>Preview degrades, confirm does not</h2>
 *
 * <p>The two entry points treat a failed downstream call in opposite ways, and
 * the asymmetry is deliberate. Preview is a read: a missing shipping quote
 * should still render a page, so it uses
 * {@link ResilientFeignCaller#call} and tolerates nulls. Confirm moves money,
 * so it uses {@link ResilientFeignCaller#callOrThrow} and converts every
 * failure into a status.
 *
 * <p>Confirm did not always do that. Every one of its seven failure branches
 * returned <strong>{@code 200 OK}</strong> with the reason in an untyped
 * {@code message} string, and the storefront fires
 * {@code toast.success("Order placed")} on any 200. So an empty cart, an
 * invalid promo code, an unreachable order service, a <em>declined card</em>
 * and — worst — <em>money captured with no order</em> all showed the shopper
 * the same green success toast. Two consequences worth naming:
 *
 * <ul>
 *   <li>A 200 is not an error, so a transient outage could not be retried by
 *       anything — not a filter and not the client, because neither could see
 *       that it had failed.</li>
 *   <li>Dashboards and alerts count 5xx. A captured payment with no order
 *       raised nothing.</li>
 * </ul>
 *
 * <p>Every failure now carries a status a client can branch on, chosen so the
 * retryable ones are retryable and the final ones are not: 409 empty cart,
 * 422 bad promo code, 503 downstream unreachable (with {@code Retry-After}),
 * 402 declined card, 502 payment unconfirmable, 500 our bug or money stranded.
 * {@code ConfirmFailurePathsTest} pins one per branch.
 *
 * <p>Nothing auto-retries this endpoint, and that is correct: the gateway's
 * retry filter is GET-only, so a POST that moves money is never replayed for
 * the shopper. The retrying that does happen is one layer in — Resilience4j
 * retries each <em>outbound</em> call 3 times on a transport fault or a
 * downstream 5xx — so a 503 out of here means that already failed, and the
 * decision to try again is the shopper's.
 *
 * <p>This is still not a "real" saga with compensating actions across
 * services. What changed is that the gap is now <em>loud</em>: a captured
 * payment that cannot be attached to an order answers 500 and names the intent
 * to reconcile, instead of reporting success. Closing it properly needs a
 * refund-or-retry compensation (outbox, or a saga framework such as Eventuate
 * or a workflow engine such as Temporal) and is tracked separately.
 */
@Service
@Slf4j
public class CheckoutService {

    private final CartClient cart;
    private final CatalogPricingClient catalog;
    private final CustomerClient customer;
    private final PromotionClient promotion;
    private final ShippingClient shipping;
    private final PaymentClient payment;
    private final OrderClient order;
    private final ResilientFeignCaller resilient;

    public CheckoutService(CartClient cart, CatalogPricingClient catalog,
                           PromotionClient promotion, ShippingClient shipping,
                           PaymentClient payment, OrderClient order,
                           CustomerClient customer,
                           ResilientFeignCaller resilient) {
        this.cart = cart;
        this.catalog = catalog;
        this.customer = customer;
        this.promotion = promotion;
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
     * charge. Cart no longer accepts a price -- but a service that charges a
     * card must not depend on another service's arithmetic being right, so the
     * amount is derived here, first-hand, from the same authority cart uses.
     *
     * <h2>Every line, or none</h2>
     *
     * A line catalog will not price refuses the whole bag rather than being
     * dropped from it. Dropping it would place an order the shopper did not
     * agree to; charging around it would ship it free, which is exactly what
     * would have happened once cart started excluding unsellable lines from
     * its subtotal while still listing them.
     *
     * <p>Mixed currencies refuse too. Summing two units is meaningless, and
     * picking one would charge the wrong number in the wrong denomination.
     *
     * @throws IllegalStateException the bag cannot be priced as it stands --
     *         409, and the shopper has to change something. Distinct problem
     *         types for the two reasons come with the staleness guard; today
     *         both read as a conflict.
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
        // Digested over catalog's ANSWER rather than over the lines built from
        // it, so the version covers status as well as price -- a variant going
        // UNPUBLISHED changes the version even though no remaining price moved.
        return new RepricedBag(lines, subtotal, currency,
                PricingVersion.of(byVariant.values()));
    }

    /**
     * The bag as catalog prices it: order lines, their sum, one currency, and
     * the digest of the prices it was built from.
     */
    private record RepricedBag(List<OrderClient.LineItem> lines, BigDecimal subtotal,
                               String currency, String pricingVersion) {
    }

    public PreviewResponse preview(PreviewRequest req) {
        // Names below map 1:1 to resilience4j.{circuitbreaker,retry,
        // ratelimiter}.instances.<name> in application.yaml.
        // One R4J instance per Feign client (not per method) so that
        // when cart-service is unhealthy, ALL cart.* calls trip the
        // same circuit rather than each method tripping independently.
        var myCart = safeCall(cart::getMyCart, "cart");
        if (myCart == null || myCart.items() == null || myCart.items().isEmpty()) {
            // An empty bag has no prices to version, and confirm refuses it
            // on its own terms (CartEmptyException) long before the staleness
            // check -- so a null here cannot strand anyone. The currency is
            // null for the same reason: there is nothing to denominate.
            return new PreviewResponse(
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    null, null, List.of(), null
            );
        }
        // Repriced here too, not just in confirm. The plan for this phase
        // replaced only confirm's subtotal, which would have left a bag
        // previewing one number and being charged another -- the shopper
        // agreeing to a total the server never intended to take, or being
        // shown more than it charged. Both paths go through the same code.
        RepricedBag priced = reprice(myCart);
        BigDecimal subtotal = priced.subtotal();

        BigDecimal discount = BigDecimal.ZERO;
        String applied = null;
        if (req.promotionCode() != null && !req.promotionCode().isBlank()) {
            var apply = safeCall(() -> promotion.apply(req.promotionCode(),
                    new PromotionClient.ApplyRequest(subtotal)), "promotion");
            if (apply != null) {
                discount = apply.discount();
                applied = apply.code();
            }
        }

        BigDecimal afterDiscount = subtotal.subtract(discount);
        // Derived here as well as in confirm: the destination is the ADDRESS's
        // country, never the body's, so preview and confirm quote the same place
        // -- and an address abroad is refused at preview, before the shopper
        // has typed a card.
        String destination = resolveDestination(req.shippingAddressId(), req.destinationCountry());
        var rates = quoteShipping(destination, afterDiscount, priced.currency());
        requireQuotedInBagCurrency(rates, priced.currency());
        List<PreviewResponse.ShippingOptionDto> options = new ArrayList<>();
        BigDecimal cheapest = BigDecimal.ZERO;
        if (rates.options() != null) {
            for (var o : rates.options()) {
                options.add(new PreviewResponse.ShippingOptionDto(
                        o.code(), o.carrier(), o.service(), o.cost(), o.etaDaysMin(), o.etaDaysMax()));
            }
            cheapest = rates.options().stream()
                    .map(ShippingClient.ShippingOption::cost)
                    .min(BigDecimal::compareTo)
                    .orElse(BigDecimal.ZERO);
        }

        BigDecimal total = afterDiscount.add(cheapest);
        // The token needs no shipping half any more: preview never shows an
        // items-only quote, because quoteShipping refuses rather than
        // returning nothing (Phase 0, item 1), so there is no quote without
        // shipping in it for a later confirm to add shipping to.
        return new PreviewResponse(subtotal, discount, cheapest, total,
                priced.currency(), applied, options, priced.pricingVersion());
    }

    /**
     * Run a Feign call decorated with the full Resilience4j toolbox
     * (RateLimiter -> CircuitBreaker -> Retry) and return null on
     * failure. The decoration runs synchronously on the Tomcat thread
     * so the SecurityContext propagates to {@link
     * com.oriah.checkout.config.FeignTokenForwarder} -- see
     * {@link ResilientFeignCaller} for the rationale.
     *
     * <p>For {@link #preview} only. A null here means "no data, carry on",
     * which is right for a read and wrong for anything that moves money --
     * confirm uses {@link #mustCall} instead.
     */
    /**
     * Refuses a shipping quote that is not denominated in the bag's currency.
     *
     * <h2>Why this is not the same as shipping being down</h2>
     *
     * An unreachable rate service degrades to free shipping, deliberately:
     * under-charging ourselves is the safe direction to be wrong in, and a
     * courier outage should not stop a sale. A quote in the WRONG CURRENCY is
     * a different animal -- shipping answered, and the answer is unusable.
     * Treating it as an outage would add a dollar cost to a Kwacha total as
     * though the two were one unit, permanently and invisibly, which is
     * precisely what happened while checkout discarded this field.
     *
     * <p>So it is a 500, not a 409 and not a silent zero: nothing the shopper
     * did caused it and nothing they can do will fix it. It is a
     * configuration mismatch between two of our own services, and the only
     * safe behaviour is to stop and be noticed.
     *
     * <p>A null quote (the outage path) passes through here untouched.
     */
    /**
     * Refuses a confirm whose prices are not the ones the shopper was quoted.
     *
     * <h2>Placed before the promo call, deliberately</h2>
     *
     * Every refusal in this method has to happen before anything is ordered,
     * charged or redeemed. A promotion redeemed against an order that is then
     * refused has consumed one of its uses for nothing, which is why this sits
     * above the promo call rather than next to the payment.
     *
     * <p>The token is only ever COMPARED, never used as an input to a price, so
     * a forged value buys nothing: the worst a client can do with a wrong one
     * is refuse its own checkout.
     *
     * <p>A RETRY after this 409 is safe without a new Idempotency-Key, which is
     * worth stating because it was not always true. Payment used to replay a
     * stored response keyed on the key STRING alone, so a retry after a
     * pricing failure could be handed an earlier success with no charge taken.
     * That replay is now scoped to the owner and to the intent the key was
     * bound to, and a key presented against a different intent is refused -- so
     * the ordinary storefront behaviour of reusing the key is no longer a hole.
     */
    private void requireAgreedPrices(String echoed, RepricedBag priced) {
        if (!priced.pricingVersion().equals(PricingVersion.catalogHalfOf(echoed))) {
            log.info("409 stale pricing: client echoed {}, current catalog digest is {}",
                    echoed, priced.pricingVersion());
            throw new PricingChangedException(
                    priced.lines().stream().map(OrderClient.LineItem::variantId).toList());
        }
    }

    /**
     * Quotes shipping, distinguishing "did not answer" from "answered and
     * refused".
     *
     * <h2>The bug this replaces</h2>
     *
     * Both paths used {@code safeCall}, whose contract is "any failure becomes
     * null". shipping-service refuses to quote a currency its rate card is not
     * written in, with an IllegalArgumentException the shared advice renders
     * as 400 -- so a currency mismatch became {@code rates == null}, which is
     * the documented outage path, which charges ZERO shipping. Every affected
     * bag shipped free, consistently, so nothing looked wrong to anyone; and
     * {@link #requireQuotedInBagCurrency} could never fire, because a refusing
     * shipping-service never returns an option in the wrong currency for it to
     * inspect.
     *
     * <p>So the two are separated here, exactly as {@code mustCall}'s
     * {@code classify} already does for the confirm path: a 4xx means shipping
     * rejected what WE sent, which is our bug and must be loud; anything else
     * means no answer, which is a courier outage and still degrades to free
     * shipping. {@code requireQuotedInBagCurrency} stays as belt and braces for
     * an older or third-party shipping build that ignores the currency field.
     */
    /**
     * The country to quote shipping for: the one on the address being shipped
     * to.
     *
     * <h2>Why the request body cannot be trusted with this</h2>
     *
     * {@code destinationCountry} decided the zone, and the zone decides both
     * the shipping cost and the free-shipping threshold -- 125.00 and free over
     * 3,750.00 for DOMESTIC against 1,125.00 and free over 12,500.00 for
     * INTERNATIONAL. Nothing reconciled it with {@code shippingAddressId}, so a
     * caller could pay domestic rates to ship abroad, and the order recorded
     * only the address, so fulfilment shipped to the real one. Exactly the
     * shape of defect this phase removed for prices.
     *
     * <p>So the body's value is ignored whenever an address is available. The
     * storefront already derives it the same way
     * ({@code selectedAddress?.country}), so only a hand-rolled caller could
     * ever have diverged -- which is precisely who this is for.
     *
     * @throws IllegalArgumentException the id is not one of the caller's
     *         addresses -- 400. customer-service lists only the caller's own,
     *         so "not yours" and "does not exist" are indistinguishable here,
     *         which is the right amount for checkout to know.
     */
    private String resolveDestination(UUID shippingAddressId, String fallbackCountry) {
        if (shippingAddressId == null) {
            // preview only, and only for a caller that sent no address. A quote
            // is not a charge.
            return fallbackCountry;
        }
        // mustCall: the zone decides money, so a checkout that cannot
        // establish it must not guess. Nothing is charged at this point, so
        // the failure is classified as transient and is safe to retry.
        List<CustomerClient.Address> addresses =
                mustCall(customer::myAddresses, "customer");
        return addresses.stream()
                .filter(a -> shippingAddressId.equals(a.id()))
                .map(CustomerClient.Address::country)
                .findFirst()
                .orElseThrow(() -> {
                    log.warn("Refusing checkout: addressId={} is not one of the caller's",
                            shippingAddressId);
                    return new IllegalArgumentException(
                            "That shipping address is not one of yours.");
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
    private ShippingClient.RateResponse quoteShipping(String country, BigDecimal afterDiscount,
                                                      String currency) {
        ShippingClient.RateResponse rates;
        try {
            rates = resilient.callOrThrow("shipping", () -> shipping.rates(
                    new ShippingClient.RateRequest(country, afterDiscount, currency)));
        } catch (RuntimeException e) {
            int answered = ResilientFeignCaller.answeredStatus(e);
            if (answered >= 400 && answered < 500) {
                if (ResilientFeignCaller.answeredProblemType(e)
                        .filter(ProblemTypes.OUTSIDE_DELIVERY_AREA::equals).isPresent()) {
                    log.info("422 no delivery to {}: outside Zambia", country);
                    throw new OutsideDeliveryAreaException();
                }
                log.error("shipping REFUSED a {} quote for {} with {} -- configuration "
                                + "mismatch, not an outage", currency, country, answered, e);
                throw new DownstreamRejectedException("shipping", answered, e);
            }
            throw new DownstreamUnavailableException("shipping", e);
        }
        if (rates == null) {
            throw new DownstreamRejectedException("shipping");
        }
        return rates;
    }

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

    private <T> T safeCall(java.util.function.Supplier<T> call, String name) {
        return resilient.call(name, call, null);
    }

    /**
     * The confirm-path counterpart of {@link #safeCall}: same decoration, but
     * a failure becomes a typed exception instead of a null.
     *
     * <p>Classification is the whole job, and it turns on one question --
     * <em>did the downstream answer?</em>
     *
     * <ul>
     *   <li><strong>It answered 4xx</strong> -> it rejected what we sent, which
     *       is a checkout bug: {@link DownstreamRejectedException}, 500. Not a
     *       503, because the same payload will be rejected on every retry and
     *       {@code Retry-After} would start a loop that cannot succeed.</li>
     *   <li><strong>It never answered</strong>, or answered 5xx, or the circuit
     *       is open, or the rate limiter is starved ->
     *       {@link DownstreamUnavailableException}, 503 with
     *       {@code Retry-After}. Genuinely transient, and now genuinely
     *       retryable.</li>
     *   <li><strong>It answered 2xx with no body</strong> -> also
     *       {@link DownstreamRejectedException}. Reachable in practice: with
     *       {@code spring.cloud.openfeign.circuitbreaker.enabled = false} the
     *       {@code fallback} declared on each client is dead code, so a null is
     *       a real empty body rather than a fallback firing. Dropping this
     *       check would turn it into an NPE and an unnamed 500.</li>
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
        if (answered >= 400 && answered < 500) {
            return new DownstreamRejectedException(name, answered, cause);
        }
        return new DownstreamUnavailableException(name, cause);
    }

    /**
     * Place and pay for the cart's contents.
     *
     * @throws CartEmptyException                    409, nothing to buy
     * @throws PromotionCodeInvalidException         422, the code cannot be used
     * @throws DownstreamUnavailableException        503, a dependency is down
     * @throws DownstreamRejectedException           500, a dependency refused our request
     * @throws PaymentDeclinedException              402, the card was refused
     * @throws PaymentConfirmationFailedException    502, the processor gave no usable answer
     * @throws PaymentCapturedWithoutOrderException  500, money moved and the order did not
     *
     * @param idempotencyKey the browser's {@code Idempotency-Key}, forwarded
     *     verbatim to payment-service so that a retry replays the stored
     *     response instead of charging again. May be null, in which case there
     *     is no such protection -- deliberately not generated here, because a
     *     key minted per attempt is fresh per attempt and guards nothing.
     */
    public ConfirmResponse confirm(ConfirmRequest req, String idempotencyKey) {
        var myCart = mustCall(cart::getMyCart, "cart");
        if (myCart.items() == null || myCart.items().isEmpty()) {
            // An empty cart is now distinguishable from an unreachable
            // cart-service: mustCall has already thrown 503 for the latter,
            // so reaching here means the cart really is empty.
            throw new CartEmptyException();
        }

        // 0) Price the bag. THE fix: this used to be
        //    `BigDecimal subtotal = myCart.subtotal();`, which charged whatever
        //    cart reported.
        RepricedBag priced = reprice(myCart);

        requireAgreedPrices(req.pricingVersion(), priced);


        // 1) Promo
        BigDecimal subtotal = priced.subtotal();
        BigDecimal discount = BigDecimal.ZERO;
        String appliedPromo = null;
        if (req.promotionCode() != null && !req.promotionCode().isBlank()) {
            var apply = applyPromotion(req.promotionCode(), subtotal);
            discount = apply.discount();
            appliedPromo = apply.code();
        }
        BigDecimal afterDiscount = subtotal.subtract(discount);

        // 2) Shipping (cheapest). An unreachable rate service is a 503 and an
        // address abroad a 422 -- never a sale with delivery charged at zero
        // (Phase 0, item 1): the delivery price is agreed before anything is
        // charged (Q24).
        BigDecimal shippingCost = BigDecimal.ZERO;
        // confirm ALWAYS has an address, so the body's country is always
        // ignored here. This is the call that decides the charge.
        String destination = resolveDestination(req.shippingAddressId(), req.destinationCountry());
        var rates = quoteShipping(destination, afterDiscount, priced.currency());
        requireQuotedInBagCurrency(rates, priced.currency());
        if (rates != null && rates.options() != null && !rates.options().isEmpty()) {
            shippingCost = rates.options().stream()
                    .map(ShippingClient.ShippingOption::cost)
                    .min(BigDecimal::compareTo)
                    .orElse(BigDecimal.ZERO);
        } else {
            log.warn("No shipping rates for country={}; charging zero shipping",
                    req.destinationCountry());
        }
        BigDecimal total = afterDiscount.add(shippingCost);

        // 3) Place order in CREATED state, from the REPRICED lines. Mapping
        //    cart's items here instead would record a sale at the cart's
        //    prices while charging catalog's -- two answers to "what did we
        //    sell and for how much", on the one record that is supposed to
        //    settle it.
        var placed = mustCall(() -> order.place(new OrderClient.PlaceOrderRequest(
                req.shippingAddressId(), priced.currency(), priced.lines())), "order");
        log.info("Order placed orderId={} status={} total={}", placed.id(), placed.status(), placed.total());

        // 4) Payment. Nothing has been charged yet, so a failure up to and
        // including createIntent is safe to report as transient.
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
            // intent. Calling it a decline (402) would tell the shopper their
            // card was refused when it may well have been charged.
            throw new PaymentConfirmationFailedException(
                    "the payment service did not answer", e);
        }
        if (confirmed == null) {
            throw new PaymentConfirmationFailedException(
                    "the payment service answered with no body", null);
        }
        if (!"SUCCEEDED".equals(confirmed.status())) {
            // Terminal: payment-service only ever lands an intent in SUCCEEDED
            // or FAILED, so anything else here is a refusal. 402 and NOT
            // retryable -- the same card will be refused again.
            log.info("Payment declined intentId={} status={} reason={}",
                    intent.id(), confirmed.status(), confirmed.failureReason());
            throw new PaymentDeclinedException(confirmed.failureReason());
        }
        log.info("Payment SUCCEEDED intentId={}", intent.id());

        // 5) Pay -> attach intent + transition order to PAID.
        //
        // Past this line the money is gone, so a failure is no longer
        // transient and must not be reported as one: a 503 invites a retry
        // that would charge the card a second time.
        OrderClient.OrderResponse paid;
        try {
            paid = mustCall(() -> order.pay(placed.id(),
                    new OrderClient.AttachPaymentRequest(intent.id())), "order");
        } catch (RuntimeException e) {
            log.error("PAYMENT CAPTURED WITHOUT ORDER orderId={} intentId={} amount={} -- "
                            + "needs manual reconciliation (refund the intent or replay the "
                            + "transition)",
                    placed.id(), intent.id(), total, e);
            throw new PaymentCapturedWithoutOrderException(placed.id(), intent.id(), e);
        }

        // 6) Redeem promo + clear cart (post-success bookkeeping).
        //
        // Deliberately still best-effort: the order is PAID and the shopper
        // owns it, so failing the response now would report a loss on a sale
        // that completed. A stale cart or an unredeemed code is a bookkeeping
        // drift to reconcile, not a reason to tell the shopper their purchase
        // failed. Both are logged by ResilientFeignCaller.
        final String promoToRedeem = appliedPromo;
        if (promoToRedeem != null) {
            safeCall(() -> { promotion.redeem(promoToRedeem); return null; }, "promotion");
        }
        safeCall(() -> { cart.clearMyCart(); return null; }, "cart");

        return new ConfirmResponse(paid.id(), paid.status(), intent.id(),
                confirmed.status(), total, priced.currency(), "order placed and paid");
    }

    /**
     * Validate the promo code and compute its discount.
     *
     * <p>The one place a 404 is not an error: promotion-service answers 404 for
     * unknown, expired, disabled and exhausted codes alike, as a single status
     * on purpose so the response cannot be used to probe which guard failed.
     * That becomes 422 here -- "this code cannot be used" -- and the reason is
     * not elaborated, because elaborating it would hand back the signal the
     * single 404 exists to withhold.
     *
     * <p>Any other failure keeps {@link #mustCall}'s classification. Telling a
     * shopper their code is invalid when the truth is that promotion-service is
     * down is both wrong and unactionable, and it is what the old
     * {@code 200 OK} with {@code "promotion code invalid: X"} did on every
     * outage.
     */
    private PromotionClient.ApplyResponse applyPromotion(String code, BigDecimal subtotal) {
        try {
            return mustCall(() -> promotion.apply(code,
                    new PromotionClient.ApplyRequest(subtotal)), "promotion");
        } catch (DownstreamRejectedException e) {
            if (e.status() == HttpStatus.NOT_FOUND.value()) {
                log.info("Promotion code rejected by promotion-service code={}", code);
                throw new PromotionCodeInvalidException(code);
            }
            // 400 from promotion-service means WE sent a bad subtotal: our
            // bug, so it keeps its 500 rather than being blamed on the code.
            throw e;
        }
    }
}
