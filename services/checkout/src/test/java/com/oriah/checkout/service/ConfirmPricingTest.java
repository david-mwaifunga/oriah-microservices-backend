package com.oriah.checkout.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.oriah.checkout.client.CartClient;
import com.oriah.checkout.client.CatalogPricingClient;
import com.oriah.checkout.client.OrderClient;
import com.oriah.checkout.client.PaymentClient;
import com.oriah.checkout.client.PromotionClient;
import com.oriah.checkout.client.ShippingClient;
import com.oriah.checkout.dto.ConfirmRequest;
import com.oriah.checkout.dto.PreviewRequest;
import com.oriah.checkout.dto.PreviewResponse;
import com.oriah.checkout.exception.PricingChangedException;
import com.oriah.checkout.resilience.ResilientFeignCaller;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What checkout charges, and where that number comes from.
 *
 * <h2>The defect at the centre of this phase</h2>
 *
 * {@code confirm} never computed a subtotal. It read {@code myCart.subtotal()}
 * and that single field was the sole input to the charged total, so whatever
 * cart reported was what the card was debited. Cart used to store the price the
 * shopper sent, which is how a K4,500 ring could be bought for one cent.
 *
 * <p>Fixing cart alone is not enough, and this is the trap worth naming: cart
 * is now the authority for what it shows a shopper, but checkout taking cart's
 * arithmetic on trust means a bug or a compromise in ONE service decides what
 * the card is charged. Checkout prices the bag itself, from catalog, and
 * charges that.
 *
 * <h2>A worse bug that the cart fix created, and this closes</h2>
 *
 * Cart's subtotal now EXCLUDES a line it cannot sell, while
 * {@code myCart.items()} still lists it. Checkout placed an order from every
 * item and charged cart's subtotal -- so a withdrawn line would have been
 * ORDERED AND NOT CHARGED. A free item, produced by the two services each
 * being individually correct. Pricing the bag in checkout removes the
 * disagreement: a line catalog will not price is refused outright, so nothing
 * is ordered and nothing is charged.
 *
 * <h2>Preview and confirm must agree</h2>
 *
 * {@link #previewChargesWhatItShowed} is the one the plan for this phase
 * missed. It replaced only the subtotal in {@code confirm}; with
 * {@code preview} still reading cart's field, a 0.01 bag previewed 0.01 and
 * confirmed at 100.00 -- charging a number the server never showed the
 * shopper, and in the reverse case showing more than it charged. Both paths go
 * through the same repricing now.
 *
 * <p>Deliberately NOT asserted here: that cart's own {@code pricingStatus} is
 * read. Checkout's own pricing call is strictly stronger than cart's opinion --
 * it is first-hand, it is current, and it cannot be stale relative to what is
 * about to be charged.
 */
class ConfirmPricingTest {

    private static final UUID ADDRESS = UUID.randomUUID();
    private static final UUID VARIANT = UUID.randomUUID();
    private static final UUID ORDER_ID = UUID.randomUUID();
    private static final UUID INTENT_ID = UUID.randomUUID();

    /** What a tampered-with bag claims. */
    private static final BigDecimal ONE_CENT = new BigDecimal("0.01");
    /** What catalog says the thing actually costs. */
    private static final BigDecimal REAL_PRICE = new BigDecimal("4500.00");

    private CartClient cart;
    private CatalogPricingClient catalog;
    private PromotionClient promotion;
    private ShippingClient shipping;
    private PaymentClient payment;
    private OrderClient order;
    private CheckoutService service;

    @BeforeEach
    void setUp() {
        cart = mock(CartClient.class);
        catalog = mock(CatalogPricingClient.class);
        promotion = mock(PromotionClient.class);
        shipping = mock(ShippingClient.class);
        payment = mock(PaymentClient.class);
        order = mock(OrderClient.class);

        ResilientFeignCaller resilient = new ResilientFeignCaller(
                CircuitBreakerRegistry.ofDefaults(),
                RetryRegistry.of(RetryConfig.custom().maxAttempts(1).build()),
                RateLimiterRegistry.of(RateLimiterConfig.ofDefaults()));
        service = new CheckoutService(cart, catalog, promotion, shipping, payment, order, resilient);

        // A bag whose stored line and subtotal both claim one cent. This is
        // what a pre-fix cart would have handed over, and what a compromised or
        // buggy cart could still hand over.
        when(cart.getMyCart()).thenReturn(new CartClient.CartView(
                UUID.randomUUID(), ONE_CENT,
                List.of(new CartClient.CartItem(UUID.randomUUID(), VARIANT,
                        "Free ring please", 1, ONE_CENT, ONE_CENT))));
        shippingIsFree();
        orderIsPlaced();
        intentSucceeds();
    }

    /** The entry the catalog stub is currently answering with. */
    private CatalogPricingClient.Entry answer;

    private void catalogSays(String status, BigDecimal price, String currency) {
        answer = new CatalogPricingClient.Entry(VARIANT, status, UUID.randomUUID(),
                "Aurelia 18k", "Aurelia 18k - Ring Size M", price, currency);
        when(catalog.price(any())).thenReturn(
                new CatalogPricingClient.PricingResponse(List.of(answer)));
    }

    /**
     * The version a client would have been handed by preview.
     *
     * <p>Computed the way the server computes it rather than hardcoded -- a
     * literal digest in a test is a value nobody can check and that has to be
     * re-pasted every time the canonical form changes.
     *
     * <p>Deliberately NOT obtained by calling preview() in every test: preview
     * also calls shipping, which would double the invocation counts the
     * shipping assertions rely on. previewChargesWhatItShowed does the real
     * round trip.
     */
    private String currentVersion() {
        // Null when a test stubs catalog directly rather than through
        // catalogSays -- the empty-answer case. The value is irrelevant there:
        // reprice() refuses the bag before the version is ever compared, so a
        // placeholder keeps the helper from throwing on List.of(null).
        return answer == null
                ? "no-version-needed"
                : PricingVersion.of(List.of(answer));
    }

    private void shippingIsFree() {
        when(shipping.rates(any())).thenReturn(new ShippingClient.RateResponse(
                "NG", "DOMESTIC", List.of(new ShippingClient.ShippingOption(
                        "Local Courier", "Standard", BigDecimal.ZERO, "ZMW", 2, 4))));
    }

    private void orderIsPlaced() {
        when(order.place(any())).thenReturn(new OrderClient.OrderResponse(
                ORDER_ID, "kc-alice", "CREATED", REAL_PRICE, REAL_PRICE, "ZMW", null));
        when(order.pay(any(), any())).thenReturn(new OrderClient.OrderResponse(
                ORDER_ID, "kc-alice", "PAID", REAL_PRICE, REAL_PRICE, "ZMW", INTENT_ID));
    }

    private void intentSucceeds() {
        when(payment.createIntent(any())).thenReturn(new PaymentClient.IntentResponse(
                INTENT_ID, ORDER_ID, REAL_PRICE, "ZMW", "PENDING", null));
        when(payment.confirm(any(), any(), any())).thenReturn(new PaymentClient.IntentResponse(
                INTENT_ID, ORDER_ID, REAL_PRICE, "ZMW", "SUCCEEDED", null));
    }

    private ConfirmRequest confirmRequest() {
        return new ConfirmRequest(ADDRESS, "NG", "4242424242424242", null, currentVersion());
    }

    // ------------------------------------------------------------- the fix

    @Test
    @DisplayName("the amount sent to payment is catalog's price, not the cart's subtotal")
    void theChargedAmountIsCatalogsNotTheCartsSubtotal() {
        catalogSays("PRICED", REAL_PRICE, "ZMW");

        service.confirm(confirmRequest(), "key-1");

        // THE assertion of this phase. Nothing else in the suite pins the
        // number that reaches payment: the older tests stub CartView.subtotal
        // directly and assert statuses.
        ArgumentCaptor<PaymentClient.CreateIntentRequest> intent =
                ArgumentCaptor.forClass(PaymentClient.CreateIntentRequest.class);
        verify(payment).createIntent(intent.capture());
        assertThat(intent.getValue().amount())
                .as("the bag said 0.01; catalog said 4500.00")
                .isEqualByComparingTo(REAL_PRICE);
    }

    @Test
    @DisplayName("the order snapshot carries catalog's price too, not the cart's")
    void theOrderSnapshotCarriesCatalogsPrice() {
        catalogSays("PRICED", REAL_PRICE, "ZMW");

        service.confirm(confirmRequest(), "key-2");

        // The order is the financial record. A correct charge against an order
        // whose lines say 0.01 would be unreconcilable, and the 'what did we
        // sell and for how much' question would have two answers.
        ArgumentCaptor<OrderClient.PlaceOrderRequest> placed =
                ArgumentCaptor.forClass(OrderClient.PlaceOrderRequest.class);
        verify(order).place(placed.capture());
        assertThat(placed.getValue().items().getFirst().unitPrice())
                .isEqualByComparingTo(REAL_PRICE);
        assertThat(placed.getValue().items().getFirst().title())
                .as("catalog's composed label, not the client's string")
                .isEqualTo("Aurelia 18k - Ring Size M");
    }

    @Test
    @DisplayName("preview charges what it showed")
    void previewChargesWhatItShowed() {
        catalogSays("PRICED", REAL_PRICE, "ZMW");

        PreviewResponse preview = service.preview(new PreviewRequest("NG", null));
        // The real round trip: confirm echoes the token preview handed out,
        // which is what a storefront does and what the staleness check exists
        // to make meaningful.
        service.confirm(new ConfirmRequest(ADDRESS, "NG", "4242424242424242", null,
                preview.pricingVersion()), "key-3");

        ArgumentCaptor<PaymentClient.CreateIntentRequest> intent =
                ArgumentCaptor.forClass(PaymentClient.CreateIntentRequest.class);
        verify(payment).createIntent(intent.capture());

        // With preview still reading cart's subtotal, this bag previewed 0.01
        // and confirmed at 4500.00 -- a number the shopper was never shown.
        assertThat(preview.subtotal()).isEqualByComparingTo(REAL_PRICE);
        assertThat(preview.total()).isEqualByComparingTo(intent.getValue().amount());
    }

    @Test
    @DisplayName("the currency comes from catalog, not from a hardcoded USD")
    void theCurrencyIsCatalogsNotHardcoded() {
        catalogSays("PRICED", REAL_PRICE, "ZMW");

        service.confirm(confirmRequest(), "key-4");

        ArgumentCaptor<OrderClient.PlaceOrderRequest> placed =
                ArgumentCaptor.forClass(OrderClient.PlaceOrderRequest.class);
        verify(order).place(placed.capture());
        assertThat(placed.getValue().currency()).isEqualTo("ZMW");

        ArgumentCaptor<PaymentClient.CreateIntentRequest> intent =
                ArgumentCaptor.forClass(PaymentClient.CreateIntentRequest.class);
        verify(payment).createIntent(intent.capture());
        assertThat(intent.getValue().currency())
                .as("a ZMW bag must not become a USD intent")
                .isEqualTo("ZMW");
    }

    // ----------------------------------------------------- the staleness guard

    @Test
    @DisplayName("a stale pricing version is refused before anything is ordered, charged or redeemed")
    void aStaleVersionIsRefusedBeforeAnythingHappens() {
        catalogSays("PRICED", REAL_PRICE, "ZMW");

        // What a shopper sends after an admin repriced the ring between their
        // preview and their Place order: a token for prices that no longer
        // exist.
        ConfirmRequest stale = new ConfirmRequest(ADDRESS, "NG", "4242424242424242", null,
                "0000000000000000000000000000000000000000000000000000000000000000");

        assertThatThrownBy(() -> service.confirm(stale, "key-12"))
                .isInstanceOf(PricingChangedException.class);

        verify(order, never()).place(any());
        verify(payment, never()).createIntent(any());
        // AND the promo is untouched. A code redeemed against an order that is
        // then refused has spent one of its uses for nothing, which is why the
        // check sits above the promotion call rather than beside the payment.
        verify(promotion, never()).apply(any(), any());
    }

    @Test
    @DisplayName("the version preview handed out is accepted")
    void theVersionFromPreviewIsAccepted() {
        catalogSays("PRICED", REAL_PRICE, "ZMW");

        var preview = service.preview(new PreviewRequest("NG", null));
        var response = service.confirm(new ConfirmRequest(ADDRESS, "NG", "4242424242424242",
                null, preview.pricingVersion()), "key-13");

        assertThat(response.orderId()).isEqualTo(ORDER_ID);
    }

    @Test
    @DisplayName("changing a quantity does not invalidate the version")
    void aQuantityEditDoesNotInvalidateTheVersion() {
        catalogSays("PRICED", REAL_PRICE, "ZMW");
        var preview = service.preview(new PreviewRequest("NG", null));

        // The shopper nudges the stepper from 1 to 3 after previewing. No price
        // changed, so their token must still be good: a version that included
        // quantity would turn the most ordinary interaction in a bag into a
        // conflict.
        when(cart.getMyCart()).thenReturn(new CartClient.CartView(
                UUID.randomUUID(), ONE_CENT,
                List.of(new CartClient.CartItem(UUID.randomUUID(), VARIANT,
                        "Free ring please", 3, ONE_CENT, ONE_CENT))));

        service.confirm(new ConfirmRequest(ADDRESS, "NG", "4242424242424242", null,
                preview.pricingVersion()), "key-14");

        ArgumentCaptor<PaymentClient.CreateIntentRequest> intent =
                ArgumentCaptor.forClass(PaymentClient.CreateIntentRequest.class);
        verify(payment).createIntent(intent.capture());
        assertThat(intent.getValue().amount())
                .as("3 x 4500.00 -- the quantity is honoured, the version is not invalidated")
                .isEqualByComparingTo("13500.00");
    }

    // ------------------------------------------------------- refuse, don't guess

    @Test
    @DisplayName("the bag's currency is sent to shipping, so it can refuse to quote in it")
    void theBagCurrencyIsSentToShipping() {
        catalogSays("PRICED", REAL_PRICE, "ZMW");

        service.confirm(confirmRequest(), "key-9");

        ArgumentCaptor<ShippingClient.RateRequest> rates =
                ArgumentCaptor.forClass(ShippingClient.RateRequest.class);
        verify(shipping).rates(rates.capture());
        assertThat(rates.getValue().currency())
                .as("shipping cannot refuse a currency it is never told about")
                .isEqualTo("ZMW");
    }

    @Test
    @DisplayName("a shipping quote in another currency is refused, not added to the total")
    void aShippingQuoteInAnotherCurrencyIsRefused() {
        catalogSays("PRICED", REAL_PRICE, "ZMW");
        // shipping-service minted every option in USD until this phase, and
        // checkout discarded the currency field entirely -- so a 45-dollar
        // courier charge landed on a Kwacha order as though it were 45 Kwacha.
        // Unlike an outage, this is a configuration mismatch: paper over it
        // with free shipping and the mistake is permanent and invisible.
        when(shipping.rates(any())).thenReturn(new ShippingClient.RateResponse(
                "NG", "DOMESTIC", List.of(new ShippingClient.ShippingOption(
                        "DHL", "International", new BigDecimal("45.00"), "USD", 7, 14))));

        assertThatThrownBy(() -> service.confirm(confirmRequest(), "key-10"))
                .isInstanceOf(RuntimeException.class);

        verify(order, never()).place(any());
        verify(payment, never()).createIntent(any());
    }

    @Test
    @DisplayName("shipping being UNREACHABLE still degrades to free, as it always has")
    void anUnreachableShippingServiceStillDegradesToFree() {
        catalogSays("PRICED", REAL_PRICE, "ZMW");
        // Pinned so the refusal above cannot quietly become a refusal here.
        // An unreachable rate service must not stop a sale -- under-charging
        // ourselves by the shipping cost is the safe direction to be wrong in,
        // and that is a deliberate, documented choice rather than an oversight.
        when(shipping.rates(any())).thenThrow(new IllegalStateException("shipping is down"));

        service.confirm(confirmRequest(), "key-11");

        ArgumentCaptor<PaymentClient.CreateIntentRequest> intent =
                ArgumentCaptor.forClass(PaymentClient.CreateIntentRequest.class);
        verify(payment).createIntent(intent.capture());
        assertThat(intent.getValue().amount())
                .as("the items, with no shipping added")
                .isEqualByComparingTo(REAL_PRICE);
    }

    @Test
    @DisplayName("a line catalog will not price is never ordered and never charged")
    void anUnpublishedLineIsRefusedEntirely() {
        catalogSays("UNPUBLISHED", null, null);

        assertThatThrownBy(() -> service.confirm(confirmRequest(), "key-5"))
                .isInstanceOf(IllegalStateException.class);

        // Cart excludes such a line from its subtotal while still listing it,
        // so an order placed from items() and charged from subtotal() would
        // have shipped this one FREE.
        verify(order, never()).place(any());
        verify(payment, never()).createIntent(any());
    }

    @Test
    @DisplayName("a bag catalog knows nothing about is refused")
    void anUnknownLineIsRefused() {
        catalogSays("UNKNOWN", null, null);

        assertThatThrownBy(() -> service.confirm(confirmRequest(), "key-6"))
                .isInstanceOf(IllegalStateException.class);
        verify(order, never()).place(any());
    }

    @Test
    @DisplayName("an empty pricing answer is refused rather than read as free")
    void anEmptyPricingAnswerIsRefused() {
        when(catalog.price(any())).thenReturn(
                new CatalogPricingClient.PricingResponse(List.of()));

        assertThatThrownBy(() -> service.confirm(confirmRequest(), "key-7"))
                .isInstanceOf(IllegalStateException.class);
        verify(payment, never()).createIntent(any());
    }

    @Test
    @DisplayName("an unreachable catalog fails before anything is ordered or charged")
    void anUnreachableCatalogFailsClosed() {
        when(catalog.price(any())).thenThrow(new IllegalStateException("catalog is down"));

        assertThatThrownBy(() -> service.confirm(confirmRequest(), "key-8"))
                .isInstanceOf(RuntimeException.class);

        // Nothing has been charged at this point in the saga, so the failure is
        // safe to report as transient -- but it must not proceed on a guess.
        verify(order, never()).place(any());
        verify(payment, never()).createIntent(any());
    }
}
