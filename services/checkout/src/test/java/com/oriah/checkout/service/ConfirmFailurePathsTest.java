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
import com.oriah.checkout.client.PromotionClient;
import com.oriah.checkout.client.ShippingClient;
import com.oriah.checkout.dto.ConfirmRequest;
import com.oriah.checkout.exception.CartEmptyException;
import com.oriah.checkout.exception.DownstreamRejectedException;
import com.oriah.checkout.exception.DownstreamUnavailableException;
import com.oriah.checkout.exception.PaymentCapturedWithoutOrderException;
import com.oriah.checkout.exception.PaymentConfirmationFailedException;
import com.oriah.checkout.exception.PaymentDeclinedException;
import com.oriah.checkout.exception.PromotionCodeInvalidException;
import com.oriah.checkout.resilience.ResilientFeignCaller;

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
import static org.mockito.ArgumentMatchers.anyString;
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
    private final PromotionClient promotion = mock(PromotionClient.class);
    private final ShippingClient shipping = mock(ShippingClient.class);
    private final OrderClient order = mock(OrderClient.class);
    private final PaymentClient payment = mock(PaymentClient.class);

    /**
     * Real Resilience4j registries with retry disabled, so a failing stub fails
     * once instead of three times and the test reads as one call.
     */
    private final ResilientFeignCaller resilient = new ResilientFeignCaller(
            CircuitBreakerRegistry.ofDefaults(),
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
            cart, catalog, promotion, shipping, payment, order, customer, resilient);

    /**
     * The version matching what cartHasOneItem() makes catalog answer.
     *
     * <p>Computed rather than hardcoded: these tests are about failure paths,
     * and every one of them would otherwise fail on the staleness check
     * instead of on the path it means to exercise -- a red bar that looks like
     * a pricing regression and is really a stale fixture.
     */
    private static String version() {
        return PricingVersion.of(List.of(new CatalogPricingClient.Entry(
                VARIANT, "PRICED", null, "Aurelia", "Aurelia",
                new BigDecimal("100.00"), "USD")));
    }

    private ConfirmRequest request() {
        return new ConfirmRequest(ADDRESS, "NG", "4242424242424242", null, version());
    }

    private ConfirmRequest withPromo(String code) {
        return new ConfirmRequest(ADDRESS, "NG", "4242424242424242", code, version());
    }

    private void addressAgreesWithTheBody() {
        when(customer.myAddresses()).thenReturn(
                List.of(new CustomerClient.Address(ADDRESS, "NG")));
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
                "NG", "DOMESTIC", List.of(new ShippingClient.ShippingOption(
                        "Local Courier", "Standard", BigDecimal.ZERO, "USD", 2, 4))));
    }

    private void orderIsPlaced() {
        when(order.place(any())).thenReturn(new OrderClient.OrderResponse(
                ORDER_ID, "kc-alice", "CREATED", new BigDecimal("100.00"),
                new BigDecimal("100.00"), "USD", null));
    }

    private void intentIsCreated() {
        when(payment.createIntent(any())).thenReturn(new PaymentClient.IntentResponse(
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
        verify(payment, never()).createIntent(any());
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

        verify(payment, never()).createIntent(any());
    }

    @Test
    @DisplayName("payment service unreachable is 503, and no card has been charged")
    void paymentUnreachableIsServiceUnavailable() {
        cartHasOneItem();
        shippingIsFree();
        orderIsPlaced();
        when(payment.createIntent(any())).thenThrow(new RuntimeException("connection refused"));

        assertThatThrownBy(() -> service.confirm(request(), KEY))
                .isInstanceOf(DownstreamUnavailableException.class);

        verify(payment, never()).confirm(any(), any(), any());
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
        cartHasOneItem();
        shippingIsFree();
        orderIsPlaced();
        intentIsCreated();
        when(payment.confirm(any(), any(), any())).thenThrow(new RuntimeException("read timeout"));

        assertThatThrownBy(() -> service.confirm(request(), KEY))
                .isInstanceOf(PaymentConfirmationFailedException.class);

        verify(order, never()).pay(any(), any());
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

        // The cart is deliberately NOT cleared and the promo NOT redeemed: the
        // order is still CREATED, so the state has to stay recoverable.
        verify(cart, never()).clearMyCart();
        verify(promotion, never()).redeem(anyString());
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
    // 422 vs 503 -- "your code is bad" and "we cannot check your code"
    // are not the same answer
    // ------------------------------------------------------------------

    @Test
    @DisplayName("an unknown promotion code is 422, and no order is placed")
    void invalidPromotionCodeIsUnprocessable() {
        cartHasOneItem();
        // promotion-service answers 404 for unknown/expired/disabled/exhausted
        // -- one status on purpose, so the response cannot be used to probe
        // which guard failed. Checkout must preserve that: 422 says only
        // "this code cannot be used".
        when(promotion.apply(anyString(), any())).thenThrow(answeredWith(404));

        assertThatThrownBy(() -> service.confirm(withPromo("SPRING10"), KEY))
                .isInstanceOf(PromotionCodeInvalidException.class);

        verify(order, never()).place(any());
        verify(payment, never()).createIntent(any());
    }

    @Test
    @DisplayName("promotion-service being down is 503, NOT a claim that the code is invalid")
    void promotionServiceDownIsServiceUnavailable() {
        // The distinction that matters to a shopper: 422 tells them to fix
        // their code, which is wrong and unfixable when the truth is that we
        // could not reach the service. Before this tier both were one 200.
        cartHasOneItem();
        when(promotion.apply(anyString(), any()))
                .thenThrow(new RuntimeException("connection refused"));

        assertThatThrownBy(() -> service.confirm(withPromo("SPRING10"), KEY))
                .isInstanceOf(DownstreamUnavailableException.class)
                .isNotInstanceOf(PromotionCodeInvalidException.class);
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

        verify(payment, never()).createIntent(any());
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

        verify(payment, never()).createIntent(any());
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
