package com.oriah.checkout.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import com.oriah.checkout.client.CartClient;
import com.oriah.checkout.client.CatalogPricingClient;
import com.oriah.checkout.client.CustomerClient;
import com.oriah.checkout.client.OrderClient;
import com.oriah.checkout.client.PaymentClient;
import com.oriah.checkout.client.PaymentCreateClient;
import com.oriah.checkout.client.ShippingClient;
import com.oriah.checkout.dto.ConfirmRequest;
import com.oriah.checkout.exception.CartEmptyException;
import com.oriah.checkout.exception.DownstreamRejectedException;
import com.oriah.checkout.exception.DownstreamUnavailableException;
import com.oriah.checkout.exception.PaymentCapturedWithoutOrderException;
import com.oriah.checkout.exception.PaymentConfirmationFailedException;
import com.oriah.checkout.exception.PaymentDeclinedException;
import com.oriah.checkout.resilience.ResilientFeignCaller;
import com.oriah.quote.Quote;
import com.oriah.quote.QuoteDigest;
import com.oriah.quote.QuoteLine;

import feign.FeignException;
import feign.Request;
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
 * <p>Before it, the saga reported <strong>all six</strong> of its failures as
 * {@code 200 OK} with the reason in an untyped {@code message} string. The
 * frontend fires {@code toast.success("Order placed")} on any 200 and uses
 * {@code res.message} as the description, so:
 *
 * <ul>
 *   <li>an <strong>empty cart</strong> showed a green "Order placed";</li>
 *   <li>a <strong>declined card</strong> showed a green "Order placed" whose
 *       subtitle read "payment declined: card declined", and because
 *       {@code if (res.orderId)} never fired the shopper was left on the
 *       checkout page believing they had bought something;</li>
 *   <li><strong>money captured with no order</strong> — the worst state this
 *       system can reach — also showed "Order placed".</li>
 * </ul>
 *
 * <p>And a 200 is not an error, so a transient downstream outage reported as
 * one could not be retried by anybody — not by a gateway filter and not by the
 * shopper, because neither could tell it had failed.
 *
 * <p>Each test below names the status and the problem type a client can branch
 * on. The statuses are chosen so that the retryable cases are retryable: 503
 * and 502 are, 402 and 409 deliberately are not, because re-sending a declined
 * card or an empty cart cannot succeed.
 */
class ConfirmFailurePathsTest {

    private static final UUID ADDRESS = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID VARIANT = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ORDER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID INTENT_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

    /**
     * The browser sends one of these per logical submission. It is a real
     * parameter, not noise: see {@code theIdempotencyKeyReachesPaymentService}.
     */
    private static final String KEY = "idem-key-for-this-submission";

    private final CartClient cart = mock(CartClient.class);
    private final ShippingClient shipping = mock(ShippingClient.class);
    private final OrderClient order = mock(OrderClient.class);
    private final PaymentCreateClient paymentCreate = mock(PaymentCreateClient.class);
    private final PaymentClient payment = mock(PaymentClient.class);

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
     * catalog is new: checkout derives the charged amount from it rather than
     * trusting cart's subtotal. Every test in this class routes through
     * cartHasOneItem(), which now also programs the pricing answer -- without
     * it every confirm here would fail on the pricing call instead of on the
     * path it means to exercise.
     */
    private final CatalogPricingClient catalog = mock(CatalogPricingClient.class);

    /**
     * customer answers "which addresses are yours", which is how the shipping
     * ZONE is decided now -- the request body's destinationCountry is ignored
     * when an address is present. Stubbed to agree with the body so every test
     * here still fails or passes for its own reason.
     */
    private final CustomerClient customer = mock(CustomerClient.class);

    private final CheckoutService service = new CheckoutService(
            cart, catalog, shipping, paymentCreate, payment, order, customer, resilient);

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
                BigDecimal.ZERO)), "USD", "SELF_COLLECT", BigDecimal.ZERO));
    }

    private ConfirmRequest request() {
        return new ConfirmRequest(ADDRESS, "SELF_COLLECT", "4242424242424242", null, seen());
    }

    private void addressAgreesWithTheBody() {
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
                        "Aurelia", "Aurelia", new BigDecimal("100.00"), "USD"))));
        addressAgreesWithTheBody();
    }

    private void shippingIsFree() {
        when(shipping.rates(any())).thenReturn(new ShippingClient.RateResponse(
                "ZM", List.of(new ShippingClient.ShippingOption(
                        "SELF_COLLECT", "Local Courier", "Standard", BigDecimal.ZERO, "USD", 2, 4))));
    }

    private void orderIsPlaced() {
        when(order.place(any())).thenReturn(new OrderClient.OrderResponse(
                ORDER_ID, "kc-alice", "CREATED", new BigDecimal("100.00"),
                new BigDecimal("100.00"), "USD", null));
    }

    private void intentIsCreated() {
        when(paymentCreate.createIntent(any())).thenReturn(new PaymentClient.IntentResponse(
                INTENT_ID, ORDER_ID, new BigDecimal("100.00"), "USD",
                "REQUIRES_CONFIRMATION", null));
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

        verify(order, never()).place(any());
        verify(paymentCreate, never()).createIntent(any());
    }

    // ------------------------------------------------------------------
    // 503 -- a downstream is unreachable, and this IS retryable
    // ------------------------------------------------------------------

    @Test
    @DisplayName("order service unreachable is 503, so the stack can retry it")
    void orderUnreachableIsServiceUnavailable() {
        cartHasOneItem();
        shippingIsFree();
        when(order.place(any())).thenThrow(new RuntimeException("connection refused"));

        assertThatThrownBy(() -> service.confirm(request(), KEY))
                .isInstanceOf(DownstreamUnavailableException.class)
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(
                        ResponseStatusException.class))
                .satisfies(ex -> assertThat(ex.getStatusCode().value())
                        .as("503 is the one failure raised before any money moves, so it is "
                                + "the one the storefront may offer a retry for; the 200 this "
                                + "used to return could not be retried by anyone, because "
                                + "nothing could see that it had failed")
                        .isEqualTo(503));

        verify(paymentCreate, never()).createIntent(any());
    }

    @Test
    @DisplayName("payment service unreachable is 503, and no card has been charged")
    void paymentUnreachableIsServiceUnavailable() {
        cartHasOneItem();
        shippingIsFree();
        orderIsPlaced();
        when(paymentCreate.createIntent(any())).thenThrow(new RuntimeException("connection refused"));

        assertThatThrownBy(() -> service.confirm(request(), KEY))
                .isInstanceOf(DownstreamUnavailableException.class);

        verify(payment, never()).confirm(any(), any(), any());
    }

    @Test
    @DisplayName("payment answering 503 to createIntent (order could not be asked) is 503, and nothing is charged")
    void paymentCouldNotAskOrderIsServiceUnavailable() {
        // Payment asks order whose order an intent is for, and answers 503
        // when order is down or slow. Nothing was created or charged, so this
        // is the retryable 503, the same as payment itself being down.
        cartHasOneItem();
        shippingIsFree();
        orderIsPlaced();
        when(paymentCreate.createIntent(any())).thenThrow(answeredWith(503));

        assertThatThrownBy(() -> service.confirm(request(), KEY))
                .isInstanceOf(DownstreamUnavailableException.class)
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(
                        ResponseStatusException.class))
                .satisfies(ex -> assertThat(ex.getStatusCode().value()).isEqualTo(503));

        verify(payment, never()).confirm(any(), any(), any());
    }

    @Test
    @DisplayName("payment answering 404 to createIntent (no such order of yours) is our 500, never a 503 retry")
    void paymentNotFindingTheOrderIsOurFault() {
        // Checkout has just placed this order with the shopper's own token and
        // asks payment with the same token, so payment saying "no order of
        // yours has this id" means the services disagree -- a fault in the
        // shop's set-up. A 503 would have the shopper retry for ever; it is
        // checkout's 500, and no card is charged.
        cartHasOneItem();
        shippingIsFree();
        orderIsPlaced();
        when(paymentCreate.createIntent(any())).thenThrow(answeredWith(404));

        assertThatThrownBy(() -> service.confirm(request(), KEY))
                .isInstanceOf(DownstreamRejectedException.class)
                .isNotInstanceOf(DownstreamUnavailableException.class);

        verify(payment, never()).confirm(any(), any(), any());
        verify(order, never()).pay(any(), any());
    }

    // ------------------------------------------------------------------
    // 402 / 502 -- the two payment outcomes, which must not be conflated
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a DECLINED card is 402 and carries the reason")
    void declinedCardIsPaymentRequired() {
        // The headline bug. This showed a green "Order placed" toast whose
        // subtitle read "payment declined: ...", and left the shopper on the
        // checkout page thinking the purchase had gone through.
        cartHasOneItem();
        shippingIsFree();
        orderIsPlaced();
        intentIsCreated();
        when(payment.confirm(any(), any(), any())).thenReturn(new PaymentClient.IntentResponse(
                INTENT_ID, ORDER_ID, new BigDecimal("100.00"), "USD", "FAILED",
                "card declined"));

        assertThatThrownBy(() -> service.confirm(request(), KEY))
                .isInstanceOf(PaymentDeclinedException.class)
                .hasMessageContaining("card declined");

        verify(order, never()).pay(any(), any());
    }

    @Test
    @DisplayName("an UNREACHABLE payment gateway is 502, not 402")
    void confirmCallFailingIsBadGateway() {
        // Distinct from a decline on purpose. 402 means "your card was
        // refused", which is final and must not be retried; 502 means "we could
        // not reach the processor", which is transient and should be. Both used
        // to be the same 200 with a different message string.
        //
        // A REAL read timeout, as Feign raises it: the request reached payment
        // and the answer never came, so the charge may have happened. This used
        // to throw a hand-made RuntimeException("read timeout"), which carries
        // none of what checkout reads to decide "was anything sent?" -- so it
        // stayed green when a read timeout was mislabelled "nothing charged".
        cartHasOneItem();
        shippingIsFree();
        orderIsPlaced();
        intentIsCreated();
        RuntimeException readTimeout;
        try (var failures = new com.oriah.checkout.resilience.RealFeignFailures()) {
            readTimeout = failures.readTimeout();
        }
        when(payment.confirm(any(), any(), any())).thenThrow(readTimeout);

        assertThatThrownBy(() -> service.confirm(request(), KEY))
                .isInstanceOf(PaymentConfirmationFailedException.class);

        verify(order, never()).pay(any(), any());
    }

    // ------------------------------------------------------------------
    // 503, not 502 -- confirm was never sent, so nothing was charged
    // ------------------------------------------------------------------

    @Test
    @DisplayName("confirm's breaker open: 503 'nothing charged', never the 502 'may have gone through'")
    void anOpenBreakerInFrontOfConfirmIsServiceUnavailable() {
        // An open breaker refuses the call inside checkout: nothing went to
        // payment. The 502 says "the charge may or may not have gone through"
        // and tells the shopper not to pay again -- untrue here,
        // and it withholds the retry the storefront offers only on a 503.
        cartHasOneItem();
        shippingIsFree();
        orderIsPlaced();
        when(paymentCreate.createIntent(any())).thenAnswer(inv -> {
            // Other shoppers' failed confirms opened it a moment ago.
            breakers.circuitBreaker("payment").transitionToOpenState();
            return new PaymentClient.IntentResponse(INTENT_ID, ORDER_ID, new BigDecimal("100.00"), "USD",
                    "PENDING", null);
        });

        assertThatThrownBy(() -> service.confirm(request(), KEY))
                .isInstanceOf(DownstreamUnavailableException.class)
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(ResponseStatusException.class))
                .satisfies(ex -> assertThat(ex.getStatusCode().value()).isEqualTo(503));

        verify(payment, never()).confirm(any(), any(), any());
        verify(order, never()).pay(any(), any());
    }

    @Test
    @DisplayName("confirm refused by checkout's own rate limiter: 503, nothing was sent")
    void aRateLimiterRefusingConfirmIsServiceUnavailable() {
        // The limiter, like the breaker, stops the call before it leaves.
        RateLimiterRegistry limiters = RateLimiterRegistry.of(RateLimiterConfig.ofDefaults());
        limiters.rateLimiter("payment", RateLimiterConfig.custom()
                .limitForPeriod(1).limitRefreshPeriod(java.time.Duration.ofHours(1))
                .timeoutDuration(java.time.Duration.ZERO).build()).acquirePermission();
        CheckoutService checkout = new CheckoutService(cart, catalog, shipping, paymentCreate, payment, order, customer,
                new ResilientFeignCaller(breakers, RetryRegistry.of(RetryConfig.custom().maxAttempts(1).build()),
                        limiters));
        cartHasOneItem();
        shippingIsFree();
        orderIsPlaced();
        intentIsCreated();

        assertThatThrownBy(() -> checkout.confirm(request(), KEY))
                .isInstanceOf(DownstreamUnavailableException.class);

        verify(payment, never()).confirm(any(), any(), any());
    }

    @Test
    @DisplayName("confirm's connection refused: 503, nothing was sent")
    void aConfirmThatCouldNotConnectIsServiceUnavailable() {
        // A real refused connection, raised by Feign: the request never left
        // checkout, so payment cannot have charged anything.
        cartHasOneItem();
        shippingIsFree();
        orderIsPlaced();
        intentIsCreated();
        when(payment.confirm(any(), any(), any())).thenAnswer(inv -> {
            throw com.oriah.checkout.resilience.RealFeignFailures.refused();
        });

        assertThatThrownBy(() -> service.confirm(request(), KEY))
                .isInstanceOf(DownstreamUnavailableException.class);

        verify(order, never()).pay(any(), any());
    }

    @Test
    @DisplayName("confirm answered 503 ('outcome unknown') and then the breaker opened: still 502, it may have charged")
    void aConfirmThatReachedPaymentStays502EvenIfTheRetryFindsTheBreakerOpen() {
        // Payment's 503 on confirm means the gateway gave no answer: the money
        // may have been taken. The retry then finds the breaker open. The
        // failure checkout ends with is the open breaker -- but the FIRST
        // attempt reached payment, so "nothing was charged" would be a lie.
        //
        // Confirm's real rule (RetryForChargingCall) no longer repeats an
        // answered 503 at all; this test keeps a rule that does, on purpose,
        // to guard ResilientFeignCaller itself: once ANY attempt may have
        // arrived, no later failure may be reported as "nothing was sent".
        CheckoutService checkout = new CheckoutService(cart, catalog, shipping, paymentCreate, payment, order, customer,
                new ResilientFeignCaller(breakers, RetryRegistry.of(RetryConfig.custom().maxAttempts(3)
                        .waitDuration(java.time.Duration.ofMillis(1))
                        .retryOnException(new com.oriah.checkout.resilience.RetryForAnyCall()).build()),
                        RateLimiterRegistry.of(RateLimiterConfig.ofDefaults())));
        cartHasOneItem();
        shippingIsFree();
        orderIsPlaced();
        intentIsCreated();
        when(payment.confirm(any(), any(), any())).thenAnswer(inv -> {
            breakers.circuitBreaker("payment").transitionToOpenState();
            throw answeredWith(503);
        });

        assertThatThrownBy(() -> checkout.confirm(request(), KEY))
                .isInstanceOf(PaymentConfirmationFailedException.class);

        verify(payment, org.mockito.Mockito.times(1)).confirm(any(), any(), any());
    }

    // ------------------------------------------------------------------
    // 500 -- money has moved and the order has not. The worst state.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("payment captured but order not updated is 500, naming the intent to reconcile")
    void paymentCapturedWithoutOrderIsAnInternalError() {
        cartHasOneItem();
        shippingIsFree();
        orderIsPlaced();
        intentIsCreated();
        when(payment.confirm(any(), any(), any())).thenReturn(new PaymentClient.IntentResponse(
                INTENT_ID, ORDER_ID, new BigDecimal("100.00"), "USD", "SUCCEEDED", null));
        when(order.pay(any(), any())).thenThrow(new RuntimeException("order service died"));

        assertThatThrownBy(() -> service.confirm(request(), KEY))
                .isInstanceOf(PaymentCapturedWithoutOrderException.class)
                .satisfies(ex -> {
                    var e = (PaymentCapturedWithoutOrderException) ex;
                    assertThat(e.paymentIntentId())
                            .as("the operator cannot reconcile a captured payment without "
                                    + "the intent id, and this is the one failure where "
                                    + "money has already moved")
                            .isEqualTo(INTENT_ID);
                    assertThat(e.orderId()).isEqualTo(ORDER_ID);
                });

        // The cart is deliberately NOT cleared: the order is still CREATED, so
        // the state has to stay recoverable.
        verify(cart, never()).clearMyCart();
    }

    @Test
    @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
    @DisplayName("captured without order: neither the cause nor the log says 'nothing has been charged'")
    void capturedWithoutOrderNeverSaysNothingWasCharged(org.springframework.boot.test.system.CapturedOutput output) {
        // The card HAS been charged here. The cause this failure carries -- in
        // the ERROR log an operator reconciles from, and in the 500 handler's
        // log -- used to be checkout's 503 exception, reading "The order-pay
        // service is temporarily unavailable. Nothing has been charged; retry
        // shortly." Untrue, and the opposite of what the operator must know.
        cartHasOneItem();
        shippingIsFree();
        orderIsPlaced();
        intentIsCreated();
        when(payment.confirm(any(), any(), any())).thenReturn(new PaymentClient.IntentResponse(
                INTENT_ID, ORDER_ID, new BigDecimal("100.00"), "USD", "SUCCEEDED", null));
        when(order.pay(any(), any())).thenThrow(answeredWith(503));

        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> service.confirm(request(), KEY));

        assertThat(thrown).isInstanceOf(PaymentCapturedWithoutOrderException.class);
        for (Throwable c = thrown.getCause(); c != null; c = c.getCause()) {
            assertThat(c).as("a cause of the captured-without-order failure")
                    .isNotInstanceOf(DownstreamUnavailableException.class);
            assertThat(String.valueOf(c.getMessage())).as("the message of %s", c.getClass().getSimpleName())
                    .doesNotContain("Nothing has been charged");
        }
        assertThat(output.getAll())
                .contains("PAYMENT CAPTURED WITHOUT ORDER")
                .doesNotContain("Nothing has been charged");
    }

    @Test
    @DisplayName("captured, and order answers the pay call with no body: still 'captured without order', naming the intent")
    void capturedAndAnEmptyPayAnswerIsStillCapturedWithoutOrder() {
        // order answered, but with nothing checkout can read: whether the order
        // is now PAID is unknown, and the card has been charged. That is the
        // reconcile-by-hand case, not a bare NullPointerException.
        cartHasOneItem();
        shippingIsFree();
        orderIsPlaced();
        intentIsCreated();
        when(payment.confirm(any(), any(), any())).thenReturn(new PaymentClient.IntentResponse(
                INTENT_ID, ORDER_ID, new BigDecimal("100.00"), "USD", "SUCCEEDED", null));
        when(order.pay(any(), any())).thenReturn(null);

        assertThatThrownBy(() -> service.confirm(request(), KEY))
                .isInstanceOf(PaymentCapturedWithoutOrderException.class)
                .satisfies(ex -> assertThat(((PaymentCapturedWithoutOrderException) ex).paymentIntentId())
                        .isEqualTo(INTENT_ID));
        verify(cart, never()).clearMyCart();
    }


    /**
     * The exception a Feign client really throws when a downstream answers
     * with an error status. Built for real rather than stubbed with a bare
     * {@code RuntimeException}, because telling "the service answered 400"
     * apart from "the service never answered" is the whole point of the two
     * tests below, and a mock that cannot tell them apart would pass against
     * code that cannot either.
     */
    private static FeignException answeredWith(int status) {
        Request request = Request.create(Request.HttpMethod.POST, "http://svc/x",
                Map.of(), new byte[0], java.nio.charset.StandardCharsets.UTF_8);
        return FeignException.errorStatus("Client#call()", Response.builder()
                .status(status).reason("from the test").request(request)
                .headers(Map.of()).build());
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
        when(order.place(any())).thenThrow(answeredWith(400));

        assertThatThrownBy(() -> service.confirm(request(), KEY))
                .isInstanceOf(DownstreamRejectedException.class)
                .isNotInstanceOf(DownstreamUnavailableException.class);

        verify(paymentCreate, never()).createIntent(any());
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
        when(order.place(any())).thenThrow(answeredWith(500));

        assertThatThrownBy(() -> service.confirm(request(), KEY))
                .isInstanceOf(DownstreamRejectedException.class)
                .isNotInstanceOf(DownstreamUnavailableException.class);
        verify(paymentCreate, never()).createIntent(any());
    }

    @Test
    @DisplayName("order answering 503 is still an outage: 503, safe to try again")
    void anOrderServiceUnavailableIsRetryable() {
        cartHasOneItem();
        shippingIsFree();
        when(order.place(any())).thenThrow(answeredWith(503));

        assertThatThrownBy(() -> service.confirm(request(), KEY))
                .isInstanceOf(DownstreamUnavailableException.class);
        verify(paymentCreate, never()).createIntent(any());
    }

    @Test
    @DisplayName("a burst of placement failures cannot stop a CHARGED order from being marked paid")
    void placementFailuresDoNotBlockMarkingAChargedOrderPaid() {
        // Placement and the post-payment "mark paid" call used to share one
        // circuit breaker. Other shoppers' placements failing -- Keycloak or
        // shipping briefly down -- could open it while this shopper was inside
        // payment.confirm, and then marking their CHARGED order paid failed
        // fast: money taken, order unpaid, a manual reconciliation.
        cartHasOneItem();
        shippingIsFree();
        orderIsPlaced();
        intentIsCreated();
        when(payment.confirm(any(), any(), any())).thenAnswer(inv -> {
            breakers.circuitBreaker("order").transitionToOpenState();
            return new PaymentClient.IntentResponse(
                    INTENT_ID, ORDER_ID, new BigDecimal("100.00"), "USD", "SUCCEEDED", null);
        });
        when(order.pay(any(), any())).thenReturn(new OrderClient.OrderResponse(
                ORDER_ID, "kc-alice", "PAID", new BigDecimal("100.00"),
                new BigDecimal("100.00"), "USD", INTENT_ID));

        var response = service.confirm(request(), KEY);

        assertThat(response.orderStatus()).isEqualTo("PAID");
        verify(order).pay(eq(ORDER_ID), any());
    }

    @Test
    @DisplayName("other shoppers' failed createIntents cannot stop THIS shopper confirming the intent just made")
    void createIntentFailuresDoNotBlockConfirmingAnIntentJustMade() {
        // createIntent and confirm used to share the "payment" breaker.
        // Payment answers createIntent 503 whenever order is slow (it asks
        // order whose order it is), so a few slow seconds for OTHER shoppers
        // -- 5 failures in a window of 10 -- opened the breaker, and a shopper
        // whose intent had just been made could not confirm it.
        //
        // The breakers here are set as application.yaml sets them: a window
        // of 10 calls, open at 50% failures.
        CircuitBreakerRegistry asConfigured = CircuitBreakerRegistry.of(
                io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.custom()
                        .slidingWindowSize(10).failureRateThreshold(50).build());
        CheckoutService checkout = new CheckoutService(cart, catalog, shipping, paymentCreate, payment, order, customer,
                new ResilientFeignCaller(asConfigured, RetryRegistry.of(RetryConfig.custom().maxAttempts(1).build()),
                        RateLimiterRegistry.of(RateLimiterConfig.ofDefaults())));
        cartHasOneItem();
        shippingIsFree();
        orderIsPlaced();
        java.util.concurrent.atomic.AtomicBoolean othersAreCheckingOut = new java.util.concurrent.atomic.AtomicBoolean();
        when(paymentCreate.createIntent(any())).thenAnswer(inv -> {
            if (othersAreCheckingOut.get()) {
                throw answeredWith(503);   // payment could not ask order in time
            }
            // While this shopper's intent is being made, ten other shoppers'
            // checkouts fail at createIntent.
            othersAreCheckingOut.set(true);
            for (int i = 0; i < 10; i++) {
                String theirKey = "another-shopper-" + i;
                assertThatThrownBy(() -> checkout.confirm(request(), theirKey))
                        .isInstanceOf(DownstreamUnavailableException.class);
            }
            othersAreCheckingOut.set(false);
            return new PaymentClient.IntentResponse(INTENT_ID, ORDER_ID, new BigDecimal("100.00"), "USD",
                    "PENDING", null);
        });
        when(payment.confirm(any(), any(), any())).thenReturn(new PaymentClient.IntentResponse(
                INTENT_ID, ORDER_ID, new BigDecimal("100.00"), "USD", "SUCCEEDED", null));
        when(order.pay(any(), any())).thenReturn(new OrderClient.OrderResponse(
                ORDER_ID, "kc-alice", "PAID", new BigDecimal("100.00"),
                new BigDecimal("100.00"), "USD", INTENT_ID));

        var response = checkout.confirm(request(), KEY);

        assertThat(response.orderStatus()).isEqualTo("PAID");
        verify(payment).confirm(eq(INTENT_ID), eq(KEY), any());
    }

    @Test
    @DisplayName("a downstream answering 200 with no body is 500, not a silent NPE")
    void emptyDownstreamBodyIsAnInternalError() {
        // Reachable through a real misconfiguration: with
        // spring.cloud.openfeign.circuitbreaker.enabled = false the
        // `fallback = ...Fallback.class` on every client is dead code, so a
        // null here means order-service really did answer 2xx with no body.
        // The old code conflated that with "unavailable"; dropping the check
        // entirely would turn it into an NPE and an unnamed 500.
        cartHasOneItem();
        shippingIsFree();
        when(order.place(any())).thenReturn(null);

        assertThatThrownBy(() -> service.confirm(request(), KEY))
                .isInstanceOf(DownstreamRejectedException.class);

        verify(paymentCreate, never()).createIntent(any());
    }


    // ------------------------------------------------------------------
    // The one protection that makes a retry survivable
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the browser's Idempotency-Key is forwarded to payment-service's confirm")
    void theIdempotencyKeyReachesPaymentService() {
        // payment-service replays a stored response for a repeated key and
        // does NOT re-charge the gateway. That protection was unreachable:
        // the browser sent the header to /checkout/confirm, checkout forwarded
        // only Authorization, and so every "safe" retry of a confirm was a
        // fresh charge. The 502 this tier introduces explicitly invites a
        // retry, which makes forwarding the key a precondition of it rather
        // than an improvement on it.
        cartHasOneItem();
        shippingIsFree();
        orderIsPlaced();
        intentIsCreated();
        when(payment.confirm(any(), any(), any())).thenReturn(new PaymentClient.IntentResponse(
                INTENT_ID, ORDER_ID, new BigDecimal("100.00"), "USD", "SUCCEEDED", null));
        when(order.pay(any(), any())).thenReturn(new OrderClient.OrderResponse(
                ORDER_ID, "kc-alice", "PAID", new BigDecimal("100.00"),
                new BigDecimal("100.00"), "USD", INTENT_ID));

        service.confirm(request(), KEY);

        verify(payment).confirm(eq(INTENT_ID), eq(KEY), any());
    }

    // ------------------------------------------------------------------
    // The happy path must still work
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the happy path still returns a 200 body and clears the cart")
    void happyPathIsUnchanged() {
        cartHasOneItem();
        shippingIsFree();
        orderIsPlaced();
        intentIsCreated();
        when(payment.confirm(any(), any(), any())).thenReturn(new PaymentClient.IntentResponse(
                INTENT_ID, ORDER_ID, new BigDecimal("100.00"), "USD", "SUCCEEDED", null));
        when(order.pay(any(), any())).thenReturn(new OrderClient.OrderResponse(
                ORDER_ID, "kc-alice", "PAID", new BigDecimal("100.00"),
                new BigDecimal("100.00"), "USD", INTENT_ID));

        var response = service.confirm(request(), KEY);

        assertThat(response.orderId()).isEqualTo(ORDER_ID);
        assertThat(response.orderStatus()).isEqualTo("PAID");
        assertThat(response.paymentStatus()).isEqualTo("SUCCEEDED");
        verify(cart).clearMyCart();
    }
}
