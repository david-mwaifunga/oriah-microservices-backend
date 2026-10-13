package com.oriah.checkout.service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.oriah.checkout.client.CartClient;
import com.oriah.checkout.client.CatalogPricingClient;
import com.oriah.checkout.client.CustomerClient;
import com.oriah.checkout.client.OrderClient;
import com.oriah.checkout.client.PaymentClient;
import com.oriah.checkout.client.PromotionClient;
import com.oriah.checkout.client.ShippingClient;
import com.oriah.checkout.dto.ConfirmRequest;
import com.oriah.checkout.dto.PreviewRequest;
import com.oriah.checkout.dto.PreviewResponse;
import com.oriah.checkout.exception.BagNotPurchasableException;
import com.oriah.checkout.exception.DownstreamRejectedException;
import com.oriah.checkout.exception.MixedCurrencyException;
import com.oriah.checkout.exception.PricingChangedException;
import com.oriah.checkout.resilience.ResilientFeignCaller;
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
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
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
    private CustomerClient customer;
    private CheckoutService service;

    @BeforeEach
    void setUp() {
        cart = mock(CartClient.class);
        catalog = mock(CatalogPricingClient.class);
        promotion = mock(PromotionClient.class);
        shipping = mock(ShippingClient.class);
        payment = mock(PaymentClient.class);
        order = mock(OrderClient.class);
        customer = mock(CustomerClient.class);

        ResilientFeignCaller resilient = new ResilientFeignCaller(
                CircuitBreakerRegistry.ofDefaults(),
                RetryRegistry.of(RetryConfig.custom().maxAttempts(1).build()),
                RateLimiterRegistry.of(RateLimiterConfig.ofDefaults()));
        service = new CheckoutService(cart, catalog, promotion, shipping, payment, order,
                customer, resilient);

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
        // By default the address agrees with the request body, so the other
        // tests in this class exercise their own subject rather than this one.
        addressIsIn("NG");
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

    /** A shipping quote at a real cost, in the bag's currency. */
    private void shippingCosts(String cost) {
        // doReturn, not when(...): rates() may already be stubbed to throw, and
        // when(mock.rates(any())) would CALL it while setting up the new stub.
        doReturn(new ShippingClient.RateResponse(
                "NG", "DOMESTIC", List.of(new ShippingClient.ShippingOption(
                        "Local Courier", "Standard", new BigDecimal(cost), "ZMW", 2, 4))))
                .when(shipping).rates(any());
    }

    /** A FeignException carrying a real status, as the client would throw. */
    private static FeignException feign(int status) {
        Request request = Request.create(Request.HttpMethod.POST,
                "http://shipping/api/v1/shipping/rates", Map.of(),
                new byte[0], StandardCharsets.UTF_8, new RequestTemplate());
        return FeignException.errorStatus("ShippingClient#rates(RateRequest)",
                Response.builder().status(status).reason("generated")
                        .request(request).headers(Map.of()).build());
    }

    /** The caller's one address, in the given country. */
    private void addressIsIn(String country) {
        doReturn(List.of(new CustomerClient.Address(ADDRESS, country)))
                .when(customer).myAddresses();
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

        PreviewResponse preview = service.preview(new PreviewRequest("NG", null, ADDRESS));
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

    // ---------------------------------- the zone is not the client's to choose

    @Test
    @DisplayName("the shipping zone comes from the ADDRESS, not from the request body")
    void theZoneComesFromTheAddressNotTheRequest() {
        catalogSays("PRICED", REAL_PRICE, "ZMW");
        // The shopper's address is in the United States, which is
        // INTERNATIONAL -- 1,125.00 standard, free only over 12,500.00. The
        // request claims NG, which is DOMESTIC: 125.00 standard, free over
        // 3,750.00. A K4,000 bag shipped internationally would be charged
        // 0.00 instead of 1,125.00.
        addressIsIn("US");

        service.confirm(confirmRequest(), "key-30");

        ArgumentCaptor<ShippingClient.RateRequest> rates =
                ArgumentCaptor.forClass(ShippingClient.RateRequest.class);
        verify(shipping).rates(rates.capture());
        assertThat(rates.getValue().destinationCountry())
                .as("the country of the address being shipped to, not the one the "
                        + "caller typed -- this is the same defect the phase fixed for "
                        + "prices, one leg over")
                .isEqualTo("US");
    }

    @Test
    @DisplayName("an address that is not the shopper's is refused, and nothing is ordered")
    void anAddressThatIsNotTheShoppersIsRefused() {
        catalogSays("PRICED", REAL_PRICE, "ZMW");
        // customer-service lists the CALLER's addresses only, so an id that is
        // not in that list is either somebody else's or does not exist. The two
        // are deliberately indistinguishable in the response.
        doReturn(List.of()).when(customer).myAddresses();

        assertThatThrownBy(() -> service.confirm(confirmRequest(), "key-31"))
                .isInstanceOf(IllegalArgumentException.class);

        verify(order, never()).place(any());
        verify(payment, never()).createIntent(any());
    }

    @Test
    @DisplayName("customer-service being unreachable fails closed before anything is charged")
    void customerUnreachableFailsClosed() {
        catalogSays("PRICED", REAL_PRICE, "ZMW");
        // The zone decides the shipping charge, so a checkout that cannot
        // establish it must not guess. Nothing has been charged at this point,
        // so this is safe to report as transient.
        doThrow(feign(503)).when(customer).myAddresses();

        assertThatThrownBy(() -> service.confirm(confirmRequest(), "key-32"))
                .isInstanceOf(RuntimeException.class);

        verify(order, never()).place(any());
        verify(payment, never()).createIntent(any());
    }

    @Test
    @DisplayName("preview quotes the address's zone too, so its total matches what confirm charges")
    void previewUsesTheAddressZoneAsWell() {
        catalogSays("PRICED", REAL_PRICE, "ZMW");
        addressIsIn("US");

        service.preview(new PreviewRequest("NG", null, ADDRESS));

        ArgumentCaptor<ShippingClient.RateRequest> rates =
                ArgumentCaptor.forClass(ShippingClient.RateRequest.class);
        verify(shipping).rates(rates.capture());
        // If only confirm derived the country, a mismatched request would make
        // preview quote the cheap zone and confirm the dear one -- and the
        // shipping half of the pricing version would then 409 every attempt,
        // with preview still showing the wrong zone. An unbreakable loop.
        assertThat(rates.getValue().destinationCountry()).isEqualTo("US");
    }

    // ------------------------------------- what the review found, as tests

    @Test
    @DisplayName("a bag mixing currencies is refused with its OWN type, not as unpurchasable")
    void aMixedCurrencyBagIsRefusedWithItsOwnType() {
        // Nothing exercised this path at the checkout layer: the only
        // mixed-currency test in the platform was cart's checkoutEnabled flag.
        // The type matters because the storefront words the three conflicts
        // differently -- this one tells the shopper to split the order, which
        // is useless advice for an unavailable item and vice versa.
        UUID second = UUID.randomUUID();
        when(cart.getMyCart()).thenReturn(new CartClient.CartView(
                UUID.randomUUID(), ONE_CENT,
                List.of(new CartClient.CartItem(UUID.randomUUID(), VARIANT, "Ring", 1,
                                ONE_CENT, ONE_CENT),
                        new CartClient.CartItem(UUID.randomUUID(), second, "Cuff", 1,
                                ONE_CENT, ONE_CENT))));
        when(catalog.price(any())).thenReturn(new CatalogPricingClient.PricingResponse(List.of(
                new CatalogPricingClient.Entry(VARIANT, "PRICED", UUID.randomUUID(),
                        "Ring", "Ring", REAL_PRICE, "ZMW"),
                new CatalogPricingClient.Entry(second, "PRICED", UUID.randomUUID(),
                        "Cuff", "Cuff", new BigDecimal("250.00"), "USD"))));

        assertThatThrownBy(() -> service.confirm(
                new ConfirmRequest(ADDRESS, "NG", "4242424242424242", null, "any"), "key-20"))
                .isInstanceOf(MixedCurrencyException.class);

        verify(order, never()).place(any());
    }

    @Test
    @DisplayName("shipping ANSWERING 400 is loud, not free shipping")
    void aShippingFourHundredIsLoudNotFreeShipping() {
        catalogSays("PRICED", REAL_PRICE, "ZMW");
        // shipping-service refuses to quote a currency its rate card is not
        // written in -- an IllegalArgumentException rendered as 400. Treating
        // that as an outage ships every such bag FREE, consistently, so
        // nothing looks wrong to anyone: the currency guard this service
        // already has cannot fire, because a refusing shipping-service never
        // returns an option in the wrong currency for it to inspect.
        doThrow(feign(400)).when(shipping).rates(any());

        assertThatThrownBy(() -> service.confirm(confirmRequest(), "key-21"))
                .isInstanceOf(DownstreamRejectedException.class);

        verify(order, never()).place(any());
        verify(payment, never()).createIntent(any());
    }

    @Test
    @DisplayName("shipping being UNREACHABLE is still free shipping, not an error")
    void aShippingOutageIsStillFree() {
        catalogSays("PRICED", REAL_PRICE, "ZMW");
        // The documented degrade must survive the fix above: a courier outage
        // (no response at all) must not stop a sale. 503 and a connection
        // failure both land here.
        doThrow(feign(503)).when(shipping).rates(any());

        service.confirm(confirmRequest(), "key-22");

        ArgumentCaptor<PaymentClient.CreateIntentRequest> intent =
                ArgumentCaptor.forClass(PaymentClient.CreateIntentRequest.class);
        verify(payment).createIntent(intent.capture());
        assertThat(intent.getValue().amount()).isEqualByComparingTo(REAL_PRICE);
    }

    @Test
    @DisplayName("a shipping outage at PREVIEW does not let confirm charge more than it showed")
    void aShippingOutageAtPreviewCannotOverchargeAtConfirm() {
        catalogSays("PRICED", REAL_PRICE, "ZMW");

        // Shipping is down while the shopper reaches the review step, so the
        // quote they read is items-only and the Place order button is enabled.
        doThrow(feign(503)).when(shipping).rates(any());
        var preview = service.preview(new PreviewRequest("NG", null, ADDRESS));
        assertThat(preview.shippingCost()).isEqualByComparingTo("0.00");
        assertThat(preview.total()).isEqualByComparingTo(REAL_PRICE);

        // The circuit half-opens while they type their card, and shipping
        // answers again.
        shippingCosts("125.00");

        // The catalog prices are byte-identical, so a digest over catalog
        // alone validates and the card is charged MORE than the screen said.
        // That is the one direction the staleness guard exists to make
        // impossible.
        assertThatThrownBy(() -> service.confirm(new ConfirmRequest(ADDRESS, "NG",
                "4242424242424242", null, preview.pricingVersion()), "key-23"))
                .isInstanceOf(PricingChangedException.class);

        verify(payment, never()).createIntent(any());
    }

    @Test
    @DisplayName("a shipping quote appearing between preview and confirm is fine if it was quoted at both")
    void aStableShippingQuoteDoesNotInvalidateTheVersion() {
        catalogSays("PRICED", REAL_PRICE, "ZMW");
        shippingCosts("125.00");

        var preview = service.preview(new PreviewRequest("NG", null, ADDRESS));
        service.confirm(new ConfirmRequest(ADDRESS, "NG", "4242424242424242", null,
                preview.pricingVersion()), "key-24");

        ArgumentCaptor<PaymentClient.CreateIntentRequest> intent =
                ArgumentCaptor.forClass(PaymentClient.CreateIntentRequest.class);
        verify(payment).createIntent(intent.capture());
        assertThat(intent.getValue().amount())
                .as("items plus the shipping the shopper was shown")
                .isEqualByComparingTo("4625.00");
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

        var preview = service.preview(new PreviewRequest("NG", null, ADDRESS));
        var response = service.confirm(new ConfirmRequest(ADDRESS, "NG", "4242424242424242",
                null, preview.pricingVersion()), "key-13");

        assertThat(response.orderId()).isEqualTo(ORDER_ID);
    }

    @Test
    @DisplayName("changing a quantity does not invalidate the version")
    void aQuantityEditDoesNotInvalidateTheVersion() {
        catalogSays("PRICED", REAL_PRICE, "ZMW");
        var preview = service.preview(new PreviewRequest("NG", null, ADDRESS));

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

        // The TYPE, not just IllegalStateException: BagNotPurchasableException,
        // PricingChangedException and MixedCurrencyException all extend it, so
        // asserting the supertype would stay green if this throw were swapped
        // for the "prices changed" one -- telling the shopper to retry a bag
        // that will be refused forever.
        assertThatThrownBy(() -> service.confirm(confirmRequest(), "key-5"))
                .isInstanceOf(BagNotPurchasableException.class);

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
                .isInstanceOf(BagNotPurchasableException.class);
        verify(order, never()).place(any());
    }

    @Test
    @DisplayName("an empty pricing answer is refused rather than read as free")
    void anEmptyPricingAnswerIsRefused() {
        when(catalog.price(any())).thenReturn(
                new CatalogPricingClient.PricingResponse(List.of()));

        assertThatThrownBy(() -> service.confirm(confirmRequest(), "key-7"))
                .isInstanceOf(BagNotPurchasableException.class);
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
