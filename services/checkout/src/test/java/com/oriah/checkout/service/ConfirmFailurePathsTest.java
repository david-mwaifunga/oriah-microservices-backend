package com.oriah.checkout.service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.oriah.checkout.client.CartClient;
import com.oriah.checkout.client.CatalogPricingClient;
import com.oriah.checkout.client.CustomerClient;
import com.oriah.checkout.client.OrderClient;
import com.oriah.checkout.client.ShippingClient;
import com.oriah.checkout.dto.ConfirmRequest;
import com.oriah.checkout.exception.CartEmptyException;
import com.oriah.checkout.exception.DownstreamRejectedException;
import com.oriah.checkout.exception.DownstreamUnavailableException;
import com.oriah.checkout.exception.RefusalPassedOnException;
import com.oriah.checkout.resilience.ResilientFeignCaller;
import com.oriah.quote.Quote;
import com.oriah.quote.QuoteDigest;
import com.oriah.quote.QuoteLine;

import feign.FeignException;
import feign.Request;
import feign.RequestTemplate;
import feign.Response;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Every way {@code POST /api/v1/checkout/confirm} can fail, and the status each
 * one must produce.
 *
 * <h2>Why this is the most important test in the service</h2>
 *
 * <p>Before it, the saga reported its failures as {@code 200 OK} with the
 * reason in an untyped {@code message} string. The frontend fires
 * {@code toast.success("Order placed")} on any 200 and uses
 * {@code res.message} as the description, so:
 *
 * <ul>
 *   <li>an <strong>empty cart</strong> showed a green "Order placed";</li>
 *   <li>a failure to place the order showed the same green "Order placed", and
 *       because {@code if (res.orderId)} never fired the shopper was left on the
 *       checkout page believing they had bought something.</li>
 * </ul>
 *
 * <p>And a 200 is not an error, so a transient downstream outage reported as
 * one could not be retried by anybody -- not by a gateway filter and not by the
 * shopper, because neither could tell it had failed.
 *
 * <p>Checkout no longer touches money (Phase 7): confirm places the order and
 * order holds its pieces; paying is the step after. So a 200 here means exactly
 * "placed and held", and there is no card to decline and no charge to
 * reconcile. Each test below names the status and the problem type a client can
 * branch on. The statuses are chosen so that the retryable cases are
 * retryable: 503 is, and nothing was ordered when it is raised; 409 and 400
 * deliberately are not, because re-sending an empty cart or a stale quote
 * cannot succeed; a 500 from a downstream is final.
 */
class ConfirmFailurePathsTest {

    private static final UUID ADDRESS = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID VARIANT = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ORDER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID BOX_VARIANT = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID TOWN = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final Instant HOLD_EXPIRES = Instant.parse("2026-11-13T10:10:00Z");

    /**
     * The browser sends one of these per logical submission. It is a real
     * parameter, not noise: see {@code theIdempotencyKeyReachesOrder}.
     */
    private static final String KEY = "idem-key-for-this-submission";

    private final CartClient cart = mock(CartClient.class);
    private final ShippingClient shipping = mock(ShippingClient.class);
    private final OrderClient order = mock(OrderClient.class);

    /**
     * Real Resilience4j registries with retry disabled, so a failing stub fails
     * once instead of three times and the test reads as one call.
     */
    private final CircuitBreakerRegistry breakers = CircuitBreakerRegistry.ofDefaults();

    private final ResilientFeignCaller resilient = new ResilientFeignCaller(
            breakers,
            RetryRegistry.of(RetryConfig.custom().maxAttempts(1).build()),
            RateLimiterRegistry.of(RateLimiterConfig.ofDefaults()));

    /**
     * catalog prices the bag: checkout derives every amount from it rather than
     * trusting cart's subtotal. Every test in this class routes through
     * cartHasOneItem(), which also programs the pricing answer -- without it
     * every confirm here would fail on the pricing call instead of on the
     * path it means to exercise.
     */
    private final CatalogPricingClient catalog = mock(CatalogPricingClient.class);

    /**
     * customer answers "which addresses are yours", which is how checkout knows
     * an address id is the caller's own. Stubbed to agree with the body so every
     * test here still fails or passes for its own reason.
     */
    private final CustomerClient customer = mock(CustomerClient.class);

    private final CheckoutService service = new CheckoutService(
            cart, catalog, shipping, order, customer, resilient);

    /**
     * The quote digest matching what cartHasOneItem() makes catalog answer,
     * self-collected.
     *
     * <p>Computed rather than hardcoded: these tests are about failure paths,
     * and every one of them would otherwise fail on the staleness check
     * instead of on the path it means to exercise -- a red bar that looks like
     * a pricing regression and is really a stale fixture.
     */
    private static String seen() {
        return QuoteDigest.of(new Quote(List.of(new QuoteLine(VARIANT, 1, new BigDecimal("100.00"),
                BigDecimal.ZERO)), "ZMW", "SELF_COLLECT", BigDecimal.ZERO));
    }

    private ConfirmRequest request() {
        return new ConfirmRequest(ADDRESS, "SELF_COLLECT", null, null, seen());
    }

    /** A delivered choice, to a town: the one that needs shipping to say the town is offered. */
    private ConfirmRequest deliveredRequest() {
        return new ConfirmRequest(ADDRESS, "OWN_DELIVERY", TOWN, null, seen(), "Mulenga Chanda", "0971234567");
    }

    private void addressIsTheCallers() {
        when(customer.myAddresses()).thenReturn(
                List.of(new CustomerClient.Address(ADDRESS, "ZM")));
    }

    private void cartHasOneItem() {
        when(cart.getMyCart()).thenReturn(new CartClient.CartView(
                UUID.randomUUID(), new BigDecimal("100.00"),
                List.of(new CartClient.CartItem(UUID.randomUUID(), VARIANT, "Aurelia", 1,
                        new BigDecimal("100.00"), new BigDecimal("100.00")))));
        // Catalog agrees with the cart at 100.00, so the money in this class
        // is unchanged and each test still fails or passes for its own reason.
        // ConfirmPricingTest is where the two are made to DISAGREE.
        when(catalog.price(any())).thenReturn(new CatalogPricingClient.PricingResponse(
                List.of(new CatalogPricingClient.Entry(VARIANT, "PRICED", UUID.randomUUID(),
                        "Aurelia", "Aurelia", new BigDecimal("100.00"), "ZMW"))));
        addressIsTheCallers();
    }

    /** What shipping answers for self-collect: no town, no courier, K0. */
    private static ShippingClient.QuoteReply freeSelfCollect() {
        return new ShippingClient.QuoteReply(null, null, "SELF_COLLECT", "Self-collect", null,
                new BigDecimal("0.00"), "ZMW", new BigDecimal("0.00"), false, null, null);
    }

    private void shippingIsFree() {
        when(shipping.quote(any())).thenReturn(freeSelfCollect());
    }

    /** order as the real one answers a placement: CREATED, the pieces held until HOLD_EXPIRES, at the total IT priced. */
    private static OrderClient.OrderResponse placedOrder() {
        return new OrderClient.OrderResponse(
                ORDER_ID, "kc-alice", "CREATED", new BigDecimal("100.00"),
                new BigDecimal("0.00"), new BigDecimal("0.00"), new BigDecimal("100.00"), "ZMW",
                null, null, HOLD_EXPIRES);
    }

    private void orderIsPlaced() {
        when(order.place(any(), any())).thenReturn(placedOrder());
    }

    // ------------------------------------------------------------------
    // 409 -- the caller's state is wrong and retrying cannot help
    // ------------------------------------------------------------------

    @Test
    @DisplayName("an empty cart is 409, not a green success toast")
    void emptyCartIsAConflict() {
        when(cart.getMyCart()).thenReturn(new CartClient.CartView(
                UUID.randomUUID(), BigDecimal.ZERO, List.of()));

        assertThatThrownBy(() -> service.confirm(request(), KEY))
                .isInstanceOf(CartEmptyException.class);

        verify(order, never()).place(any(), any());
        verify(cart, never()).clearMyCart();
    }

    // ------------------------------------------------------------------
    // 409 -- a refusal of order's or shipping's that the shopper can act on,
    // passed on under ITS OWN name
    // ------------------------------------------------------------------

    @Test
    @DisplayName("order's 409 insufficient-stock is passed on under its own name, naming every short line, and the cart is kept")
    void insufficientStockIsPassedOnWithItsShortages() {
        // Inventory refuses a hold it cannot make and names every line it is
        // short of; order passes that through. The shopper can act on it --
        // take less, or drop the piece -- only if they are told WHICH piece,
        // so the shortages ride along; "something went wrong" is not an answer.
        cartHasOneItem();
        shippingIsFree();
        when(order.place(any(), any())).thenThrow(answeredWithProblem(409, "insufficient-stock",
                "\"shortages\":[{\"variantId\":\"" + VARIANT + "\",\"kind\":\"GOODS\",\"requested\":2,\"available\":1},"
                        + "{\"variantId\":\"" + BOX_VARIANT + "\",\"kind\":\"BOX\",\"requested\":1,\"available\":0}]"));

        assertThatThrownBy(() -> service.confirm(request(), KEY))
                .isInstanceOf(RefusalPassedOnException.class)
                .isNotInstanceOf(DownstreamRejectedException.class)
                .satisfies(ex -> {
                    var refusal = (RefusalPassedOnException) ex;
                    assertThat(refusal.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(refusal.code()).isEqualTo("insufficient-stock");
                    assertThat(shortagesOf(refusal))
                            .extracting(s -> s.get("variantId") + "/" + s.get("kind") + "/" + s.get("requested") + "/" + s.get("available"))
                            .containsExactly(VARIANT + "/GOODS/2/1", BOX_VARIANT + "/BOX/1/0");
                });

        // Nothing is held, so the bag is exactly what the shopper still wants to change.
        verify(cart, never()).clearMyCart();
    }

    @Test
    @DisplayName("order's 409 hold-limit-reached is passed on under its own name, and the cart is kept")
    void holdLimitReachedIsPassedOn() {
        cartHasOneItem();
        shippingIsFree();
        when(order.place(any(), any())).thenThrow(answeredWithProblem(409, "hold-limit-reached", ""));

        assertThatThrownBy(() -> service.confirm(request(), KEY))
                .isInstanceOf(RefusalPassedOnException.class)
                .satisfies(ex -> {
                    var refusal = (RefusalPassedOnException) ex;
                    assertThat(refusal.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(refusal.code()).isEqualTo("hold-limit-reached");
                });

        verify(cart, never()).clearMyCart();
    }

    @Test
    @DisplayName("a 409 from order of a type checkout does not know is NOT passed on: it is our generic 500")
    void anUnknownOrderRefusalIsNotPassedOn() {
        // Checkout passes on only the refusals it KNOWS the shopper can act
        // on. Any other 409 is a disagreement between our own services, and
        // putting its type in front of a shopper would be showing them our
        // internals.
        cartHasOneItem();
        shippingIsFree();
        when(order.place(any(), any())).thenThrow(answeredWithProblem(409, "something-new", ""));

        assertThatThrownBy(() -> service.confirm(request(), KEY))
                .isInstanceOf(DownstreamRejectedException.class)
                .isNotInstanceOf(RefusalPassedOnException.class);

        verify(cart, never()).clearMyCart();
    }

    @Test
    @DisplayName("shipping's 409 town-not-offered is passed on under its own name, and order is never asked")
    void aTownNotOfferedIsPassedOnAndNothingIsOrdered() {
        // The shop has not priced that town (or has withdrawn it since the
        // shopper's preview). There is no price to place at; the shopper
        // chooses another town or option.
        cartHasOneItem();
        when(shipping.quote(any())).thenThrow(answeredWithProblem(409, "town-not-offered", ""));

        assertThatThrownBy(() -> service.confirm(deliveredRequest(), KEY))
                .isInstanceOf(RefusalPassedOnException.class)
                .satisfies(ex -> {
                    var refusal = (RefusalPassedOnException) ex;
                    assertThat(refusal.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(refusal.code()).isEqualTo("town-not-offered");
                });

        verify(order, never()).place(any(), any());
        verify(cart, never()).clearMyCart();
    }

    @Test
    @DisplayName("shipping's 404 town-not-found is passed on as a 409 under its own name, and order is never asked")
    void aTownNotFoundIsPassedOnAsAConflict() {
        // Shipping answers 404 for a town it does not know. For the shopper it
        // is a choice to change, not a missing page, so it is a 409 here.
        cartHasOneItem();
        when(shipping.quote(any())).thenThrow(answeredWithProblem(404, "town-not-found", ""));

        assertThatThrownBy(() -> service.confirm(deliveredRequest(), KEY))
                .isInstanceOf(RefusalPassedOnException.class)
                .satisfies(ex -> {
                    var refusal = (RefusalPassedOnException) ex;
                    assertThat(refusal.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(refusal.code()).isEqualTo("town-not-found");
                });

        verify(order, never()).place(any(), any());
    }

    // ------------------------------------------------------------------
    // 503 -- a downstream is unreachable, and this IS retryable
    // ------------------------------------------------------------------

    @Test
    @DisplayName("order service unreachable is 503, so the stack can retry it")
    void orderUnreachableIsServiceUnavailable() {
        cartHasOneItem();
        shippingIsFree();
        when(order.place(any(), any())).thenThrow(new RuntimeException("connection refused"));

        assertThatThrownBy(() -> service.confirm(request(), KEY))
                .isInstanceOf(DownstreamUnavailableException.class)
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(
                        ResponseStatusException.class))
                .satisfies(ex -> assertThat(ex.getStatusCode().value())
                        .as("503 is the one failure that says nothing was ordered, so it is "
                                + "the one the storefront may offer a retry for; the 200 this "
                                + "used to return could not be retried by anyone, because "
                                + "nothing could see that it had failed")
                        .isEqualTo(503));

        verify(cart, never()).clearMyCart();
    }

    // ------------------------------------------------------------------
    // 503, not 500 -- the call was never sent, so nothing was ordered
    // ------------------------------------------------------------------

    @Test
    @DisplayName("order's breaker open: 503 'nothing ordered', and order is never called")
    void anOpenBreakerInFrontOfOrderIsServiceUnavailable() {
        // An open breaker refuses the call inside checkout: nothing went to
        // order, so nothing was placed and nothing is held. The shopper may
        // try again, which is the 503.
        cartHasOneItem();
        when(shipping.quote(any())).thenAnswer(inv -> {
            // Other shoppers' failed placements opened it a moment ago.
            breakers.circuitBreaker("order").transitionToOpenState();
            return freeSelfCollect();
        });

        assertThatThrownBy(() -> service.confirm(request(), KEY))
                .isInstanceOf(DownstreamUnavailableException.class)
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(ResponseStatusException.class))
                .satisfies(ex -> assertThat(ex.getStatusCode().value()).isEqualTo(503));

        verify(order, never()).place(any(), any());
        verify(cart, never()).clearMyCart();
    }

    @Test
    @DisplayName("placement refused by checkout's own rate limiter: 503, nothing was sent")
    void aRateLimiterRefusingThePlacementIsServiceUnavailable() {
        // The limiter, like the breaker, stops the call before it leaves.
        RateLimiterRegistry limiters = RateLimiterRegistry.of(RateLimiterConfig.ofDefaults());
        limiters.rateLimiter("order", RateLimiterConfig.custom()
                .limitForPeriod(1).limitRefreshPeriod(java.time.Duration.ofHours(1))
                .timeoutDuration(java.time.Duration.ZERO).build()).acquirePermission();
        CheckoutService checkout = new CheckoutService(cart, catalog, shipping, order, customer,
                new ResilientFeignCaller(breakers, RetryRegistry.of(RetryConfig.custom().maxAttempts(1).build()),
                        limiters));
        cartHasOneItem();
        shippingIsFree();
        orderIsPlaced();

        assertThatThrownBy(() -> checkout.confirm(request(), KEY))
                .isInstanceOf(DownstreamUnavailableException.class);

        verify(order, never()).place(any(), any());
        verify(cart, never()).clearMyCart();
    }

    @Test
    @DisplayName("order's connection refused: 503, nothing was sent")
    void aPlacementThatCouldNotConnectIsServiceUnavailable() {
        // A real refused connection, raised by Feign: the request never left
        // checkout, so order cannot have saved anything.
        cartHasOneItem();
        shippingIsFree();
        when(order.place(any(), any())).thenAnswer(inv -> {
            throw com.oriah.checkout.resilience.RealFeignFailures.refused();
        });

        assertThatThrownBy(() -> service.confirm(request(), KEY))
                .isInstanceOf(DownstreamUnavailableException.class);

        verify(cart, never()).clearMyCart();
    }

    /**
     * The exception a Feign client really throws when a downstream answers
     * with an error status. Built for real rather than stubbed with a bare
     * {@code RuntimeException}, because telling "the service answered 400"
     * apart from "the service never answered" is the whole point of the
     * tests below, and a mock that cannot tell them apart would pass against
     * code that cannot either.
     */
    private static FeignException answeredWith(int status) {
        return FeignException.errorStatus("Client#call()", Response.builder()
                .status(status).reason("from the test").request(downstreamRequest())
                .headers(Map.of()).build());
    }

    /**
     * As {@link #answeredWith}, with the problem+json body one of OUR services
     * would send: its {@code type}, a detail, and any extra members
     * ({@code extras} is the raw JSON of those, such as the shortages, or empty).
     */
    private static FeignException answeredWithProblem(int status, String type, String extras) {
        String body = "{\"type\":\"https://oriah.dev/problems/" + type + "\",\"status\":" + status
                + ",\"detail\":\"refused\"" + (extras.isEmpty() ? "" : "," + extras) + "}";
        return FeignException.errorStatus("Client#call()", Response.builder()
                .status(status).reason("from the test").request(downstreamRequest())
                .headers(Map.of("Content-Type", List.of("application/problem+json")))
                .body(body.getBytes(StandardCharsets.UTF_8)).build());
    }

    private static Request downstreamRequest() {
        return Request.create(Request.HttpMethod.POST, "http://svc/x", Map.of(),
                new byte[0], StandardCharsets.UTF_8, new RequestTemplate());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> shortagesOf(RefusalPassedOnException refusal) {
        return (List<Map<String, Object>>) refusal.extras().get("shortages");
    }

    // ------------------------------------------------------------------
    // 500 -- a downstream refusing OUR request is our bug, not a retry hint
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a downstream rejecting our request is 500, not a 503 'retry shortly'")
    void downstreamRejectingOurRequestIsNotRetryable() {
        // order-service answering 400 means checkout built a bad payload.
        // Reporting that as 503 would invite a retry loop that can never
        // succeed, and would hide a checkout bug as a downstream outage.
        cartHasOneItem();
        shippingIsFree();
        when(order.place(any(), any())).thenThrow(answeredWith(400));

        assertThatThrownBy(() -> service.confirm(request(), KEY))
                .isInstanceOf(DownstreamRejectedException.class)
                .isNotInstanceOf(DownstreamUnavailableException.class);

        verify(cart, never()).clearMyCart();
    }


    @Test
    @DisplayName("order answering 500 is final: checkout's own 500, not a 503 'try again shortly'")
    void anOrderInternalServerErrorIsNotRetryable() {
        // order answers 500 deliberately for its own misconfiguration -- a
        // wrong client secret, shipping refusing order's request. Retrying
        // cannot fix that, and telling the shopper "try again shortly" would
        // invite them to, for ever.
        cartHasOneItem();
        shippingIsFree();
        when(order.place(any(), any())).thenThrow(answeredWith(500));

        assertThatThrownBy(() -> service.confirm(request(), KEY))
                .isInstanceOf(DownstreamRejectedException.class)
                .isNotInstanceOf(DownstreamUnavailableException.class);
        verify(cart, never()).clearMyCart();
    }

    @Test
    @DisplayName("order answering 503 is still an outage: 503, safe to try again")
    void anOrderServiceUnavailableIsRetryable() {
        cartHasOneItem();
        shippingIsFree();
        when(order.place(any(), any())).thenThrow(answeredWith(503));

        assertThatThrownBy(() -> service.confirm(request(), KEY))
                .isInstanceOf(DownstreamUnavailableException.class);
        verify(cart, never()).clearMyCart();
    }

    @Test
    @DisplayName("a downstream answering 200 with no body is 500, not a silent NPE")
    void emptyDownstreamBodyIsAnInternalError() {
        // Reachable through a real misconfiguration: with
        // spring.cloud.openfeign.circuitbreaker.enabled = false the
        // `fallback = ...Fallback.class` on a client is dead code, so a
        // null here means order-service really did answer 2xx with no body.
        // The old code conflated that with "unavailable"; dropping the check
        // entirely would turn it into an NPE and an unnamed 500.
        cartHasOneItem();
        shippingIsFree();
        when(order.place(any(), any())).thenReturn(null);

        assertThatThrownBy(() -> service.confirm(request(), KEY))
                .isInstanceOf(DownstreamRejectedException.class);

        verify(cart, never()).clearMyCart();
    }


    // ------------------------------------------------------------------
    // The one protection that makes a retry survivable
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the browser's Idempotency-Key is forwarded to order-service's placement")
    void theIdempotencyKeyReachesOrder() {
        // order places the SAME order for a key it has seen, rather than a
        // second one. That protection was unreachable unless checkout forwards
        // the header the browser sent to /checkout/confirm. The 503 this tier
        // raises explicitly invites a retry, which makes forwarding the key a
        // precondition of it rather than an improvement on it.
        cartHasOneItem();
        shippingIsFree();
        orderIsPlaced();

        service.confirm(request(), KEY);

        verify(order).place(eq(KEY), any());
    }

    @Test
    @DisplayName("no Idempotency-Key from the browser is no key for order: one is never made up here")
    void anAbsentIdempotencyKeyIsNotInvented() {
        // A key minted per attempt guards nothing: a retry would carry a
        // different one and order would place a second order. Absent stays
        // absent; a reload of the same bag still finds the live order by the
        // quote's digest.
        cartHasOneItem();
        shippingIsFree();
        orderIsPlaced();

        service.confirm(request(), null);

        verify(order).place(isNull(), any());
    }

    // ------------------------------------------------------------------
    // The happy path must still work
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the happy path returns a 200 body -- placed and held, not paid -- and clears the cart")
    void happyPathPlacesAndHolds() {
        cartHasOneItem();
        shippingIsFree();
        orderIsPlaced();

        var response = service.confirm(request(), KEY);

        assertThat(response.orderId()).isEqualTo(ORDER_ID);
        assertThat(response.orderStatus()).isEqualTo("CREATED");
        assertThat(response.total()).isEqualByComparingTo("100.00");
        assertThat(response.currency()).isEqualTo("ZMW");
        assertThat(response.holdExpiresAt()).isEqualTo(HOLD_EXPIRES);
        assertThat(response.message()).isNotBlank();
        verify(cart).clearMyCart();
    }

    @Test
    @DisplayName("a cart that cannot be cleared after the order is placed is still a 200: clearing is best effort")
    void aCartThatCannotBeClearedDoesNotFailAPlacedOrder() {
        // The order is placed and its pieces are held, and the shopper owns it.
        // Telling them it failed would invite a second order for the same bag;
        // a stale cart is drift to reconcile, not a failure.
        cartHasOneItem();
        shippingIsFree();
        orderIsPlaced();
        doThrow(new RuntimeException("cart is down")).when(cart).clearMyCart();

        var response = service.confirm(request(), KEY);

        assertThat(response.orderId()).isEqualTo(ORDER_ID);
        assertThat(response.orderStatus()).isEqualTo("CREATED");
        verify(cart).clearMyCart();
    }
}
