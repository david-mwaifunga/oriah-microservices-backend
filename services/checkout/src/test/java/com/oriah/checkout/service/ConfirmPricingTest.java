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
import com.oriah.checkout.client.PaymentCreateClient;
import com.oriah.checkout.client.ShippingClient;
import com.oriah.checkout.dto.ConfirmRequest;
import com.oriah.checkout.dto.ConfirmResponse;
import com.oriah.checkout.dto.PreviewRequest;
import com.oriah.checkout.dto.PreviewResponse;
import com.oriah.checkout.exception.BagNotPurchasableException;
import com.oriah.checkout.exception.DownstreamRejectedException;
import com.oriah.checkout.exception.DownstreamUnavailableException;
import com.oriah.checkout.exception.MixedCurrencyException;
import com.oriah.checkout.exception.OutsideDeliveryAreaException;
import com.oriah.checkout.exception.PricingChangedException;
import com.oriah.checkout.exception.PromotionCodesNotAcceptedException;
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
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * What checkout charges, and where that number comes from.
 *
 * <h2>The defect at the centre of the pricing phase</h2>
 *
 * {@code confirm} never computed a subtotal. It read {@code myCart.subtotal()}
 * and that single field was the sole input to the charged total, so whatever
 * cart reported was what the card was debited. Checkout prices the bag itself,
 * from catalog, and charges that -- and a line catalog will not price is
 * refused outright, so nothing is ordered and nothing is charged.
 *
 * <h2>Quote, then commit (MVP_PLAN Phase 0, items 2 and 4.4)</h2>
 *
 * Q24: "before we commit, we all know the price". Preview prices the bag AND
 * the delivery option the shopper chose, and hands back a digest of that whole
 * quote. Confirm prices everything again and refuses with 409 pricing-changed
 * -- before anything is ordered or charged -- when its digest differs. The
 * old {@code pricingVersion} left quantity and delivery out, so a basket
 * edited in another tab, or a delivery price published in between, was
 * charged unseen; the digest binds both.
 *
 * <p>Confirm charges the option the shopper NAMED. It used to take the
 * cheapest offered, so a K0 option would have made every delivery free.
 *
 * <p>order checks the same digest again before it saves anything, and
 * checkout passes order's refusal on as its own pricing-changed. The intent is
 * created for the total ORDER recorded -- never for a figure only checkout
 * computed -- and only when the two agree.
 */
class ConfirmPricingTest {

    private static final UUID ADDRESS = UUID.randomUUID();
    private static final UUID VARIANT = UUID.randomUUID();
    private static final UUID SECOND = UUID.randomUUID();
    private static final UUID ORDER_ID = UUID.randomUUID();
    private static final UUID INTENT_ID = UUID.randomUUID();
    private static final String CARD = "4242424242424242";
    private static final BigDecimal ZERO = new BigDecimal("0.00");

    /** What a tampered-with bag claims. */
    private static final BigDecimal ONE_CENT = new BigDecimal("0.01");
    /** What catalog says the thing actually costs. */
    private static final BigDecimal REAL_PRICE = new BigDecimal("4500.00");

    private CartClient cart;
    private CatalogPricingClient catalog;
    private ShippingClient shipping;
    private PaymentCreateClient paymentCreate;
    private PaymentClient payment;
    private OrderClient order;
    private CustomerClient customer;
    private CheckoutService service;

    @BeforeEach
    void setUp() {
        cart = mock(CartClient.class);
        catalog = mock(CatalogPricingClient.class);
        shipping = mock(ShippingClient.class);
        paymentCreate = mock(PaymentCreateClient.class);
        payment = mock(PaymentClient.class);
        order = mock(OrderClient.class);
        customer = mock(CustomerClient.class);

        ResilientFeignCaller resilient = new ResilientFeignCaller(
                CircuitBreakerRegistry.ofDefaults(),
                RetryRegistry.of(RetryConfig.custom().maxAttempts(1).build()),
                RateLimiterRegistry.of(RateLimiterConfig.ofDefaults()));
        service = new CheckoutService(cart, catalog, shipping, paymentCreate, payment, order, customer, resilient);

        // A bag whose stored line and subtotal both claim one cent. This is
        // what a pre-fix cart would have handed over, and what a compromised or
        // buggy cart could still hand over.
        bagHolds(1);
        catalogSays("PRICED", REAL_PRICE, "ZMW");
        shippingOffers(option("SELF_COLLECT", "0.00"));
        orderRecordsTotal("4500.00");
        intentSucceeds();
        addressIsIn("ZM");
    }

    // ------------------------------------------------------------- fixtures

    private void bagHolds(int quantity) {
        when(cart.getMyCart()).thenReturn(new CartClient.CartView(
                UUID.randomUUID(), ONE_CENT,
                List.of(new CartClient.CartItem(UUID.randomUUID(), VARIANT,
                        "Free ring please", quantity, ONE_CENT, ONE_CENT))));
    }

    private void catalogSays(String status, BigDecimal price, String currency) {
        doReturn(new CatalogPricingClient.PricingResponse(List.of(new CatalogPricingClient.Entry(
                VARIANT, status, UUID.randomUUID(), "Aurelia 18k", "Aurelia 18k - Ring Size M",
                price, currency))))
                .when(catalog).price(any());
    }

    private static ShippingClient.ShippingOption option(String code, String cost) {
        return new ShippingClient.ShippingOption(code, "Oriah Jewelry", code, new BigDecimal(cost), "ZMW", 0, 2);
    }

    private void shippingOffers(ShippingClient.ShippingOption... options) {
        // doReturn, not when(...): rates() may already be stubbed to throw, and
        // when(mock.rates(any())) would CALL it while setting up the new stub.
        doReturn(new ShippingClient.RateResponse("ZM", List.of(options))).when(shipping).rates(any());
    }

    /** order answering as the real one does: CREATED, then PAID, at the total IT priced. */
    private void orderRecordsTotal(String total) {
        BigDecimal recorded = new BigDecimal(total);
        doReturn(new OrderClient.OrderResponse(ORDER_ID, "kc-alice", "CREATED",
                REAL_PRICE, recorded, "ZMW", null)).when(order).place(any());
        doReturn(new OrderClient.OrderResponse(ORDER_ID, "kc-alice", "PAID",
                REAL_PRICE, recorded, "ZMW", INTENT_ID)).when(order).pay(any(), any());
    }

    private void intentSucceeds() {
        when(paymentCreate.createIntent(any())).thenReturn(new PaymentClient.IntentResponse(
                INTENT_ID, ORDER_ID, REAL_PRICE, "ZMW", "PENDING", null));
        when(payment.confirm(any(), any(), any())).thenReturn(new PaymentClient.IntentResponse(
                INTENT_ID, ORDER_ID, REAL_PRICE, "ZMW", "SUCCEEDED", null));
    }

    /** The caller's one address, in the given country. */
    private void addressIsIn(String country) {
        doReturn(List.of(new CustomerClient.Address(ADDRESS, country))).when(customer).myAddresses();
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

    /** A FeignException carrying a problem+json body of the given type. */
    private static FeignException feignProblem(String method, int status, String type) {
        Request request = Request.create(Request.HttpMethod.POST, "http://downstream/x", Map.of(),
                new byte[0], StandardCharsets.UTF_8, new RequestTemplate());
        byte[] body = ("{\"type\":\"" + type + "\",\"status\":" + status + ",\"detail\":\"refused\"}")
                .getBytes(StandardCharsets.UTF_8);
        return FeignException.errorStatus(method,
                Response.builder().status(status).reason("refused").request(request)
                        .headers(Map.of("Content-Type", List.of("application/problem+json")))
                        .body(body).build());
    }

    /**
     * The digest of the quote the shopper SAW -- computed with the shared rule
     * rather than pasted, so a fixture cannot drift from it.
     */
    private static String saw(String option, String delivery, QuoteLine... lines) {
        return QuoteDigest.of(new Quote(List.of(lines), "ZMW", option, new BigDecimal(delivery)));
    }

    private static QuoteLine line(UUID variant, int quantity, String price) {
        return new QuoteLine(variant, quantity, new BigDecimal(price), ZERO);
    }

    /** What the shopper saw for this fixture's default bag: one ring, self-collected. */
    private static String sawOneRing() {
        return saw("SELF_COLLECT", "0.00", line(VARIANT, 1, "4500.00"));
    }

    private static ConfirmRequest confirm(String option, String quoteDigest) {
        return new ConfirmRequest(ADDRESS, option, CARD, null, quoteDigest);
    }

    private PreviewResponse previewFor(String option) {
        return service.preview(new PreviewRequest(null, null, ADDRESS, option));
    }

    private BigDecimal chargedAmount() {
        ArgumentCaptor<PaymentClient.CreateIntentRequest> intent =
                ArgumentCaptor.forClass(PaymentClient.CreateIntentRequest.class);
        verify(paymentCreate).createIntent(intent.capture());
        return intent.getValue().amount();
    }

    private OrderClient.PlaceOrderRequest placement() {
        ArgumentCaptor<OrderClient.PlaceOrderRequest> placed =
                ArgumentCaptor.forClass(OrderClient.PlaceOrderRequest.class);
        verify(order).place(placed.capture());
        return placed.getValue();
    }

    private void nothingWasOrderedOrCharged() {
        verify(order, never()).place(any());
        verify(paymentCreate, never()).createIntent(any());
    }

    // ------------------------------------------------------------- the price

    @Test
    @DisplayName("the amount sent to payment is catalog's price, not the cart's subtotal")
    void theChargedAmountIsCatalogsNotTheCartsSubtotal() {
        service.confirm(confirm("SELF_COLLECT", sawOneRing()), "key-1");

        assertThat(chargedAmount()).as("the bag said 0.01; catalog said 4500.00").isEqualByComparingTo(REAL_PRICE);
    }

    @Test
    @DisplayName("the order snapshot carries catalog's price and label, not the cart's")
    void theOrderSnapshotCarriesCatalogsPrice() {
        service.confirm(confirm("SELF_COLLECT", sawOneRing()), "key-2");

        OrderClient.PlaceOrderRequest placed = placement();
        assertThat(placed.items().getFirst().unitPrice()).isEqualByComparingTo(REAL_PRICE);
        assertThat(placed.items().getFirst().title())
                .as("catalog's composed label, not the client's string")
                .isEqualTo("Aurelia 18k - Ring Size M");
    }

    @Test
    @DisplayName("the currency comes from catalog, not from a hardcoded USD")
    void theCurrencyIsCatalogsNotHardcoded() {
        service.confirm(confirm("SELF_COLLECT", sawOneRing()), "key-4");

        assertThat(placement().currency()).isEqualTo("ZMW");
        ArgumentCaptor<PaymentClient.CreateIntentRequest> intent =
                ArgumentCaptor.forClass(PaymentClient.CreateIntentRequest.class);
        verify(paymentCreate).createIntent(intent.capture());
        assertThat(intent.getValue().currency()).as("a ZMW bag must not become a USD intent").isEqualTo("ZMW");
    }

    // --------------------------------------------- preview: the quote it shows

    @Test
    @DisplayName("preview without a chosen option lists the options but quotes no total and no digest")
    void previewWithoutAnOptionQuotesNoTotal() {
        shippingOffers(option("SELF_COLLECT", "0.00"), option("OWN_DELIVERY", "125.00"));

        PreviewResponse preview = previewFor(null);

        // There is no total to agree to until the shopper has chosen how the
        // piece reaches them -- showing the cheapest as "the" total is how a
        // K0 option used to make every delivery free.
        assertThat(preview.shippingOptions())
                .extracting(PreviewResponse.ShippingOptionDto::code)
                .containsExactly("SELF_COLLECT", "OWN_DELIVERY");
        assertThat(preview.subtotal()).isEqualByComparingTo(REAL_PRICE);
        assertThat(preview.shippingCost()).isNull();
        assertThat(preview.total()).isNull();
        assertThat(preview.deliveryOption()).isNull();
        assertThat(preview.quoteDigest()).isNull();
    }

    @Test
    @DisplayName("preview for a chosen option shows its delivery, its total, and the digest of exactly that")
    void previewForAnOptionShowsItsTotalAndDigest() {
        shippingOffers(option("SELF_COLLECT", "0.00"), option("OWN_DELIVERY", "125.00"));

        PreviewResponse preview = previewFor("OWN_DELIVERY");

        assertThat(preview.shippingCost()).isEqualByComparingTo("125.00");
        assertThat(preview.discount()).isEqualByComparingTo("0.00");
        assertThat(preview.total()).isEqualByComparingTo("4625.00");
        assertThat(preview.deliveryOption()).isEqualTo("OWN_DELIVERY");
        assertThat(preview.quoteDigest())
                .isEqualTo(saw("OWN_DELIVERY", "125.00", line(VARIANT, 1, "4500.00")));
    }

    @Test
    @DisplayName("preview for an option no longer offered quotes nothing, so nothing unseen can be confirmed")
    void previewForAnOptionNotOfferedQuotesNoTotal() {
        PreviewResponse preview = previewFor("OWN_DELIVERY");

        assertThat(preview.total()).isNull();
        assertThat(preview.deliveryOption()).isNull();
        assertThat(preview.quoteDigest()).isNull();
    }

    @Test
    @DisplayName("preview with no address quotes delivery within Zambia")
    void previewWithoutAnAddressQuotesZambia() {
        service.preview(new PreviewRequest(null, null, null, "SELF_COLLECT"));

        ArgumentCaptor<ShippingClient.RateRequest> rates = ArgumentCaptor.forClass(ShippingClient.RateRequest.class);
        verify(shipping).rates(rates.capture());
        assertThat(rates.getValue().destinationCountry()).isEqualTo("ZM");
        verifyNoInteractions(customer);
    }

    @Test
    @DisplayName("preview echoes each option's code, so the shopper's choice can be sent back")
    void previewEchoesEachOptionsCode() {
        assertThat(previewFor(null).shippingOptions())
                .extracting(PreviewResponse.ShippingOptionDto::code)
                .containsExactly("SELF_COLLECT");
    }

    @Test
    @DisplayName("a delivery cost finer than a ngwee is OUR fault: 500, never a quote")
    void aDeliveryCostFinerThanANgweeIsRefused() {
        shippingOffers(option("SELF_COLLECT", "0.005"));

        assertThatThrownBy(() -> previewFor("SELF_COLLECT")).isInstanceOf(DownstreamRejectedException.class);
    }

    // --------------------------------------------- confirm: the option charged

    @Test
    @DisplayName("confirm charges the option the shopper NAMED, not the cheapest one offered")
    void theNamedOptionIsCharged() {
        shippingOffers(option("SELF_COLLECT", "0.00"), option("OWN_DELIVERY", "125.00"));
        orderRecordsTotal("4625.00");

        service.confirm(confirm("OWN_DELIVERY", saw("OWN_DELIVERY", "125.00", line(VARIANT, 1, "4500.00"))), "k");

        assertThat(chargedAmount()).as("4500 + 125, not 4500 + the cheapest 0").isEqualByComparingTo("4625.00");
        assertThat(placement().deliveryOption()).isEqualTo("OWN_DELIVERY");
    }

    @Test
    @DisplayName("an option not offered at confirm is pricing-changed: the shopper re-reviews, nothing is placed")
    void anOptionNotOfferedIsRefused() {
        // Withdrawn since preview, or never offered: checkout cannot tell which,
        // and either way there is no price to charge for it. 409, the same
        // answer order gives, so the storefront re-previews and shows what IS
        // offered rather than a field error the shopper cannot fix.
        assertThatThrownBy(() -> service.confirm(
                confirm("OWN_DELIVERY", saw("OWN_DELIVERY", "125.00", line(VARIANT, 1, "4500.00"))), "k"))
                .isInstanceOf(PricingChangedException.class);

        nothingWasOrderedOrCharged();
    }

    @Test
    @DisplayName("self-collect needs no address: nothing is looked up, and delivery is quoted for Zambia")
    void selfCollectNeedsNoAddress() {
        service.confirm(new ConfirmRequest(null, "SELF_COLLECT", CARD, null, sawOneRing()), "k");

        ArgumentCaptor<ShippingClient.RateRequest> rates = ArgumentCaptor.forClass(ShippingClient.RateRequest.class);
        verify(shipping).rates(rates.capture());
        assertThat(rates.getValue().destinationCountry()).isEqualTo("ZM");
        verifyNoInteractions(customer);
        assertThat(placement().shippingAddressId()).isNull();
    }

    // --------------------------------------------- quote, then commit (4.4)

    @Test
    @DisplayName("preview charges what it showed: the digest it hands out confirms, at its total")
    void previewChargesWhatItShowed() {
        PreviewResponse preview = previewFor("SELF_COLLECT");

        service.confirm(confirm("SELF_COLLECT", preview.quoteDigest()), "key-3");

        assertThat(preview.subtotal()).isEqualByComparingTo(REAL_PRICE);
        assertThat(preview.total()).isEqualByComparingTo(chargedAmount());
    }

    @Test
    @DisplayName("order is told the option and the digest the shopper agreed to")
    void theOrderIsToldTheOptionAndTheDigest() {
        service.confirm(confirm("SELF_COLLECT", sawOneRing()), "k");

        assertThat(placement().deliveryOption()).isEqualTo("SELF_COLLECT");
        assertThat(placement().quoteDigest()).isEqualTo(sawOneRing());
    }

    @Test
    @DisplayName("a quantity changed after preview -- a bag edited in another tab -- is refused before anything happens")
    void aQuantityChangedAfterPreviewIsRefused() {
        // This used to be ACCEPTED, deliberately: pricingVersion left quantity
        // out so a stepper nudge would not conflict. But the shopper agreed to
        // one ring at 4,500, not three at 13,500 -- the total they saw is the
        // only one that may be charged (Q24).
        String sawOne = previewFor("SELF_COLLECT").quoteDigest();
        bagHolds(3);

        assertThatThrownBy(() -> service.confirm(confirm("SELF_COLLECT", sawOne), "k"))
                .isInstanceOf(PricingChangedException.class);
        nothingWasOrderedOrCharged();
    }

    @Test
    @DisplayName("two quantities swapped between lines is refused even though the total is unchanged")
    void anEqualTotalSwapIsRefused() {
        doReturn(new CatalogPricingClient.PricingResponse(List.of(
                new CatalogPricingClient.Entry(VARIANT, "PRICED", UUID.randomUUID(), "A", "A", REAL_PRICE, "ZMW"),
                new CatalogPricingClient.Entry(SECOND, "PRICED", UUID.randomUUID(), "B", "B", REAL_PRICE, "ZMW"))))
                .when(catalog).price(any());
        when(cart.getMyCart()).thenReturn(new CartClient.CartView(UUID.randomUUID(), ONE_CENT, List.of(
                new CartClient.CartItem(UUID.randomUUID(), VARIANT, "A", 2, ONE_CENT, ONE_CENT),
                new CartClient.CartItem(UUID.randomUUID(), SECOND, "B", 1, ONE_CENT, ONE_CENT))));
        String sawOneAndTwo = saw("SELF_COLLECT", "0.00",
                line(VARIANT, 1, "4500.00"), line(SECOND, 2, "4500.00"));

        assertThatThrownBy(() -> service.confirm(confirm("SELF_COLLECT", sawOneAndTwo), "k"))
                .isInstanceOf(PricingChangedException.class);
        nothingWasOrderedOrCharged();
    }

    @Test
    @DisplayName("a price changed after preview is refused before anything is ordered or charged")
    void aPriceChangedAfterPreviewIsRefused() {
        String sawAt4500 = previewFor("SELF_COLLECT").quoteDigest();
        catalogSays("PRICED", new BigDecimal("4600.00"), "ZMW");

        assertThatThrownBy(() -> service.confirm(confirm("SELF_COLLECT", sawAt4500), "k"))
                .isInstanceOf(PricingChangedException.class);
        nothingWasOrderedOrCharged();
    }

    @Test
    @DisplayName("a delivery price changed after preview is refused -- a price published in between")
    void aDeliveryPriceChangedAfterPreviewIsRefused() {
        shippingOffers(option("OWN_DELIVERY", "100.00"));
        String sawAt100 = previewFor("OWN_DELIVERY").quoteDigest();
        shippingOffers(option("OWN_DELIVERY", "125.00"));

        assertThatThrownBy(() -> service.confirm(confirm("OWN_DELIVERY", sawAt100), "k"))
                .isInstanceOf(PricingChangedException.class);
        nothingWasOrderedOrCharged();
    }

    @Test
    @DisplayName("a stable delivery price is fine: items plus the delivery the shopper was shown")
    void aStableDeliveryPriceIsCharged() {
        shippingOffers(option("OWN_DELIVERY", "125.00"));
        orderRecordsTotal("4625.00");
        PreviewResponse preview = previewFor("OWN_DELIVERY");

        service.confirm(confirm("OWN_DELIVERY", preview.quoteDigest()), "key-24");

        assertThat(chargedAmount()).isEqualByComparingTo("4625.00");
    }

    // --------------------------------------------- order's answer (B12)

    @Test
    @DisplayName("order finding the quote changed is passed on as checkout's own pricing-changed, and nothing is charged")
    void ordersPricingChangedIsPassedThrough() {
        // order prices everything again before it saves; a price moving in the
        // instant between checkout's check and order's is order's to catch.
        // It used to become a generic 500 -- "the shop is broken" -- for an
        // answer the shopper can act on by reviewing their bag.
        doThrow(feignProblem("OrderClient#place(PlaceOrderRequest)", 409,
                "https://oriah.dev/problems/pricing-changed")).when(order).place(any());

        assertThatThrownBy(() -> service.confirm(confirm("SELF_COLLECT", sawOneRing()), "k"))
                .isInstanceOf(PricingChangedException.class);
        verify(paymentCreate, never()).createIntent(any());
    }

    @Test
    @DisplayName("a 409 from order of a type checkout does not know is not mistaken for pricing-changed")
    void anUnknownOrderConflictIsNotPricingChanged() {
        doThrow(feignProblem("OrderClient#place(PlaceOrderRequest)", 409,
                "https://oriah.dev/problems/something-new")).when(order).place(any());

        assertThatThrownBy(() -> service.confirm(confirm("SELF_COLLECT", sawOneRing()), "k"))
                .isInstanceOf(DownstreamRejectedException.class);
        verify(paymentCreate, never()).createIntent(any());
    }

    @Test
    @DisplayName("order finding a piece no longer sellable is checkout's own bag-not-purchasable, never a 500")
    void ordersBagNotPurchasableIsPassedThrough() {
        // An admin unpublishes a piece in the seconds between checkout's
        // pricing and order's. The shopper can act on that -- remove the piece
        // -- so it is passed on by type, as pricing-changed is.
        doThrow(feignProblem("OrderClient#place(PlaceOrderRequest)", 409,
                "https://oriah.dev/problems/bag-not-purchasable")).when(order).place(any());

        assertThatThrownBy(() -> service.confirm(confirm("SELF_COLLECT", sawOneRing()), "k"))
                .isInstanceOf(BagNotPurchasableException.class);
        verify(paymentCreate, never()).createIntent(any());
    }

    @Test
    @DisplayName("order finding the bag mixes currencies is checkout's own mixed-currency, never a 500")
    void ordersMixedCurrencyIsPassedThrough() {
        doThrow(feignProblem("OrderClient#place(PlaceOrderRequest)", 409,
                "https://oriah.dev/problems/mixed-currency")).when(order).place(any());

        assertThatThrownBy(() -> service.confirm(confirm("SELF_COLLECT", sawOneRing()), "k"))
                .isInstanceOf(MixedCurrencyException.class);
        verify(paymentCreate, never()).createIntent(any());
    }

    @Test
    @DisplayName("an order total that disagrees with the agreed quote is never charged")
    void anOrderTotalThatDisagreesIsNeverCharged() {
        // The digests matched, so this should be impossible -- which is exactly
        // when charging anyway would be a mistake nobody notices.
        orderRecordsTotal("4600.00");

        assertThatThrownBy(() -> service.confirm(confirm("SELF_COLLECT", sawOneRing()), "k"))
                .isInstanceOf(DownstreamRejectedException.class);
        verify(paymentCreate, never()).createIntent(any());
    }

    @Test
    @DisplayName("the response reports the total the PAID order records")
    void theResponseCarriesThePaidTotal() {
        // A distinct figure from the placed and quoted 4,500, so this can only
        // pass when the response is read from the PAID order.
        doReturn(new OrderClient.OrderResponse(ORDER_ID, "kc-alice", "PAID",
                REAL_PRICE, new BigDecimal("4501.00"), "ZMW", INTENT_ID)).when(order).pay(any(), any());

        ConfirmResponse response = service.confirm(confirm("SELF_COLLECT", sawOneRing()), "k");

        assertThat(response.orderId()).isEqualTo(ORDER_ID);
        assertThat(response.total()).isEqualByComparingTo("4501.00");
    }

    // --------------------------------------------- promotion codes (B9)

    @Test
    @DisplayName("a promotion code at preview is refused: codes are retired until Phase 7's scoped promotions")
    void aPromotionCodeIsRefusedAtPreview() {
        assertThatThrownBy(() -> service.preview(new PreviewRequest(null, "SPRING10", ADDRESS, "SELF_COLLECT")))
                .isInstanceOf(PromotionCodesNotAcceptedException.class);
        verifyNoInteractions(cart, catalog, shipping);
    }

    @Test
    @DisplayName("a promotion code at confirm is refused before anything is ordered or charged")
    void aPromotionCodeIsRefusedAtConfirm() {
        assertThatThrownBy(() -> service.confirm(
                new ConfirmRequest(ADDRESS, "SELF_COLLECT", CARD, "SPRING10", sawOneRing()), "k"))
                .isInstanceOf(PromotionCodesNotAcceptedException.class);
        nothingWasOrderedOrCharged();
    }

    @Test
    @DisplayName("a blank promotion code is no code at all")
    void aBlankPromotionCodeIsIgnored() {
        service.confirm(new ConfirmRequest(ADDRESS, "SELF_COLLECT", CARD, "  ", sawOneRing()), "k");

        assertThat(chargedAmount()).isEqualByComparingTo(REAL_PRICE);
    }

    // ---------------------------------- the zone is not the client's to choose

    @Test
    @DisplayName("an address outside Zambia is refused with a reason the shopper can act on, and nothing is placed")
    void anAddressOutsideZambiaIsRefusedWithAReasonTheShopperCanActOn() {
        addressIsIn("US");
        doThrow(feignProblem("ShippingClient#rates(RateRequest)", 422,
                "https://oriah.dev/problems/outside-delivery-area")).when(shipping).rates(any());

        assertThatThrownBy(() -> service.confirm(confirm("SELF_COLLECT", sawOneRing()), "key-30"))
                .isInstanceOf(OutsideDeliveryAreaException.class)
                .hasMessage("We deliver within Zambia only.");

        ArgumentCaptor<ShippingClient.RateRequest> rates = ArgumentCaptor.forClass(ShippingClient.RateRequest.class);
        verify(shipping).rates(rates.capture());
        assertThat(rates.getValue().destinationCountry()).as("the country of the address").isEqualTo("US");
        nothingWasOrderedOrCharged();
    }

    @Test
    @DisplayName("shipping answering 422 of ANOTHER type is not mistaken for 'outside Zambia'")
    void aShipping422OfAnotherTypeIsNotMistakenForOutsideZambia() {
        doThrow(feignProblem("ShippingClient#rates(RateRequest)", 422,
                "https://oriah.dev/problems/validation")).when(shipping).rates(any());

        assertThatThrownBy(() -> service.confirm(confirm("SELF_COLLECT", sawOneRing()), "key-35"))
                .isInstanceOf(DownstreamRejectedException.class);
        verify(order, never()).place(any());
    }

    @Test
    @DisplayName("an address that is not the shopper's is refused, and nothing is ordered")
    void anAddressThatIsNotTheShoppersIsRefused() {
        doReturn(List.of()).when(customer).myAddresses();

        assertThatThrownBy(() -> service.confirm(confirm("SELF_COLLECT", sawOneRing()), "key-31"))
                .isInstanceOf(IllegalArgumentException.class);
        nothingWasOrderedOrCharged();
    }

    @Test
    @DisplayName("customer-service being unreachable fails closed before anything is charged")
    void customerUnreachableFailsClosed() {
        doThrow(feign(503)).when(customer).myAddresses();

        assertThatThrownBy(() -> service.confirm(confirm("SELF_COLLECT", sawOneRing()), "key-32"))
                .isInstanceOf(RuntimeException.class);
        nothingWasOrderedOrCharged();
    }

    @Test
    @DisplayName("preview refuses an address outside Zambia exactly as confirm does")
    void previewOutsideZambiaIsRefusedLikeConfirm() {
        addressIsIn("US");
        doThrow(feignProblem("ShippingClient#rates(RateRequest)", 422,
                "https://oriah.dev/problems/outside-delivery-area")).when(shipping).rates(any());

        assertThatThrownBy(() -> previewFor("SELF_COLLECT")).isInstanceOf(OutsideDeliveryAreaException.class);

        ArgumentCaptor<ShippingClient.RateRequest> rates = ArgumentCaptor.forClass(ShippingClient.RateRequest.class);
        verify(shipping).rates(rates.capture());
        assertThat(rates.getValue().destinationCountry()).isEqualTo("US");
    }

    // ------------------------------------- what the review found, as tests

    @Test
    @DisplayName("a bag mixing currencies is refused with its OWN type, not as unpurchasable")
    void aMixedCurrencyBagIsRefusedWithItsOwnType() {
        when(cart.getMyCart()).thenReturn(new CartClient.CartView(UUID.randomUUID(), ONE_CENT, List.of(
                new CartClient.CartItem(UUID.randomUUID(), VARIANT, "Ring", 1, ONE_CENT, ONE_CENT),
                new CartClient.CartItem(UUID.randomUUID(), SECOND, "Cuff", 1, ONE_CENT, ONE_CENT))));
        doReturn(new CatalogPricingClient.PricingResponse(List.of(
                new CatalogPricingClient.Entry(VARIANT, "PRICED", UUID.randomUUID(), "Ring", "Ring", REAL_PRICE, "ZMW"),
                new CatalogPricingClient.Entry(SECOND, "PRICED", UUID.randomUUID(), "Cuff", "Cuff",
                        new BigDecimal("250.00"), "USD"))))
                .when(catalog).price(any());

        assertThatThrownBy(() -> service.confirm(confirm("SELF_COLLECT", sawOneRing()), "key-20"))
                .isInstanceOf(MixedCurrencyException.class);
        verify(order, never()).place(any());
    }

    @Test
    @DisplayName("shipping ANSWERING 400 is loud, not free shipping")
    void aShippingFourHundredIsLoudNotFreeShipping() {
        doThrow(feign(400)).when(shipping).rates(any());

        assertThatThrownBy(() -> service.confirm(confirm("SELF_COLLECT", sawOneRing()), "key-21"))
                .isInstanceOf(DownstreamRejectedException.class);
        nothingWasOrderedOrCharged();
    }

    @Test
    @DisplayName("shipping being UNREACHABLE refuses to confirm: never a sale with delivery charged at zero")
    void aShippingOutageRefusesToConfirm() {
        doThrow(feign(503)).when(shipping).rates(any());

        assertThatThrownBy(() -> service.confirm(confirm("SELF_COLLECT", sawOneRing()), "key-22"))
                .isInstanceOf(DownstreamUnavailableException.class);
        nothingWasOrderedOrCharged();
    }

    @Test
    @DisplayName("a shipping outage at PREVIEW gives no quote at all, so nothing unseen can be confirmed")
    void aPreviewDuringAShippingOutageIssuesNoQuote() {
        doThrow(feign(503)).when(shipping).rates(any());

        assertThatThrownBy(() -> previewFor("SELF_COLLECT")).isInstanceOf(DownstreamUnavailableException.class);
    }

    @Test
    @DisplayName("a stale digest is refused before anything is ordered or charged")
    void aStaleDigestIsRefusedBeforeAnythingHappens() {
        assertThatThrownBy(() -> service.confirm(confirm("SELF_COLLECT", "0".repeat(64)), "key-12"))
                .isInstanceOf(PricingChangedException.class);
        nothingWasOrderedOrCharged();
    }

    // ------------------------------------------------------- refuse, don't guess

    @Test
    @DisplayName("the bag's currency is sent to shipping, so it can refuse to quote in it")
    void theBagCurrencyIsSentToShipping() {
        service.confirm(confirm("SELF_COLLECT", sawOneRing()), "key-9");

        ArgumentCaptor<ShippingClient.RateRequest> rates = ArgumentCaptor.forClass(ShippingClient.RateRequest.class);
        verify(shipping).rates(rates.capture());
        assertThat(rates.getValue().currency()).isEqualTo("ZMW");
    }

    /**
     * A delivery charge quoted in USD for a ZMW bag. Priced at 0.00 on purpose:
     * then the digest of the quote matches what the shopper "saw" whether or
     * not the currency is checked, so only the currency check can refuse it --
     * a 45.00 charge would be refused by the digest instead and hide a missing
     * check.
     */
    private void shippingQuotesInDollars() {
        doReturn(new ShippingClient.RateResponse("ZM", List.of(new ShippingClient.ShippingOption(
                "SELF_COLLECT", "DHL", "International", new BigDecimal("0.00"), "USD", 7, 14))))
                .when(shipping).rates(any());
    }

    @Test
    @DisplayName("a shipping quote in another currency is refused at confirm, not added to the total")
    void aShippingQuoteInAnotherCurrencyIsRefused() {
        shippingQuotesInDollars();

        assertThatThrownBy(() -> service.confirm(confirm("SELF_COLLECT", sawOneRing()), "key-10"))
                .isInstanceOf(DownstreamRejectedException.class);
        nothingWasOrderedOrCharged();
    }

    @Test
    @DisplayName("a shipping quote in another currency is refused at preview, before the shopper is shown it")
    void aShippingQuoteInAnotherCurrencyIsRefusedAtPreview() {
        // 45 dollars shown as 45 Kwacha is a wrong total on the screen, and the
        // digest of it would then be handed out as the quote to agree to.
        shippingQuotesInDollars();

        assertThatThrownBy(() -> previewFor("SELF_COLLECT")).isInstanceOf(DownstreamRejectedException.class);
    }

    @Test
    @DisplayName("shipping answering 500 is its own failure: our 500, not a 503 'try again shortly'")
    void aShippingInternalServerErrorIsNotRetryable() {
        doThrow(feign(500)).when(shipping).rates(any());

        assertThatThrownBy(() -> service.confirm(confirm("SELF_COLLECT", sawOneRing()), "key-36"))
                .isInstanceOf(DownstreamRejectedException.class);
        nothingWasOrderedOrCharged();
    }

    @Test
    @DisplayName("shipping failing with no answer at all is a 503 too, before anything is ordered")
    void anUnreachableShippingServiceIsServiceUnavailable() {
        doThrow(new IllegalStateException("shipping is down")).when(shipping).rates(any());

        assertThatThrownBy(() -> service.confirm(confirm("SELF_COLLECT", sawOneRing()), "key-11"))
                .isInstanceOf(DownstreamUnavailableException.class);
        nothingWasOrderedOrCharged();
    }

    @Test
    @DisplayName("a line catalog will not price is never ordered and never charged")
    void anUnpublishedLineIsRefusedEntirely() {
        catalogSays("UNPUBLISHED", null, null);

        assertThatThrownBy(() -> service.confirm(confirm("SELF_COLLECT", sawOneRing()), "key-5"))
                .isInstanceOf(BagNotPurchasableException.class);
        nothingWasOrderedOrCharged();
    }

    @Test
    @DisplayName("a bag catalog knows nothing about is refused")
    void anUnknownLineIsRefused() {
        catalogSays("UNKNOWN", null, null);

        assertThatThrownBy(() -> service.confirm(confirm("SELF_COLLECT", sawOneRing()), "key-6"))
                .isInstanceOf(BagNotPurchasableException.class);
        verify(order, never()).place(any());
    }

    @Test
    @DisplayName("an empty pricing answer is refused rather than read as free")
    void anEmptyPricingAnswerIsRefused() {
        doReturn(new CatalogPricingClient.PricingResponse(List.of())).when(catalog).price(any());

        assertThatThrownBy(() -> service.confirm(confirm("SELF_COLLECT", sawOneRing()), "key-7"))
                .isInstanceOf(BagNotPurchasableException.class);
        verify(paymentCreate, never()).createIntent(any());
    }

    @Test
    @DisplayName("an unreachable catalog fails before anything is ordered or charged")
    void anUnreachableCatalogFailsClosed() {
        doThrow(new IllegalStateException("catalog is down")).when(catalog).price(any());

        assertThatThrownBy(() -> service.confirm(confirm("SELF_COLLECT", sawOneRing()), "key-8"))
                .isInstanceOf(RuntimeException.class);
        nothingWasOrderedOrCharged();
    }
}
