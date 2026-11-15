package com.oriah.checkout.service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import org.springframework.http.HttpStatus;

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
import com.oriah.checkout.exception.DownstreamRejectedException;
import com.oriah.checkout.exception.DownstreamUnavailableException;
import com.oriah.checkout.exception.MixedCurrencyException;
import com.oriah.checkout.exception.PricingChangedException;
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
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * What checkout quotes and places, and where that number comes from.
 *
 * <h2>The defect at the centre of the pricing phase</h2>
 *
 * {@code confirm} never computed a subtotal. It read {@code myCart.subtotal()}
 * and that single field was the sole input to the charged total, so whatever
 * cart reported was what the shopper was held to. Checkout prices the bag
 * itself, from catalog, and places that -- and a line catalog will not price is
 * refused outright, so nothing is ordered.
 *
 * <h2>Quote, then commit (MVP_PLAN Phase 0, items 2 and 4.4; Phase 7)</h2>
 *
 * Q24: "before we commit, we all know the price". Preview prices the bag AND
 * the delivery the shopper chose (an option, a town, who pays the courier --
 * asked of shipping, on the goods AFTER the promotions' discount), and hands
 * back a digest of that whole quote. Confirm prices everything again and
 * refuses with 409 pricing-changed -- before anything is ordered -- when its
 * digest differs. The digest binds quantity, price, the per-piece discount and
 * the promotion that gave it, the delivery price, the town and the payer, so a
 * basket edited in another tab, or a delivery price published in between, is
 * never placed unseen.
 *
 * <p>Checkout touches no money any more: there is no card, no payment intent
 * and no promotion code. "Nothing happened" on a refusal is expressed as
 * "order.place was never called" and the cart was not cleared.
 *
 * <p>Confirm places the option the shopper NAMED. Order checks the same digest
 * again before it saves anything, and checkout passes order's refusals on:
 * pricing-changed as its own, the shopper-actionable ones under their own name.
 * The order's total must be the agreed quote's total, or checkout refuses to
 * go on.
 */
class ConfirmPricingTest {

    private static final UUID ADDRESS = UUID.randomUUID();
    private static final UUID TOWN = UUID.randomUUID();
    private static final UUID OTHER_TOWN = UUID.randomUUID();
    private static final UUID VARIANT = UUID.randomUUID();
    private static final UUID SECOND = UUID.randomUUID();
    private static final UUID PROMOTION = UUID.randomUUID();
    private static final UUID OTHER_PROMOTION = UUID.randomUUID();
    private static final UUID ORDER_ID = UUID.randomUUID();
    private static final Instant HOLD_EXPIRES = Instant.parse("2026-11-13T10:10:00Z");
    private static final BigDecimal ZERO = new BigDecimal("0.00");
    private static final String ORIAH_COLLECTS = Quote.ORIAH_COLLECTS;
    private static final String RECIPIENT_PAYS = Quote.RECIPIENT_PAYS;
    private static final String RING = "Aurelia 18k - Ring Size M";

    /** What a tampered-with bag claims. */
    private static final BigDecimal ONE_CENT = new BigDecimal("0.01");
    /** What catalog says the thing actually costs. */
    private static final BigDecimal REAL_PRICE = new BigDecimal("4500.00");

    private CartClient cart;
    private CatalogPricingClient catalog;
    private ShippingClient shipping;
    private OrderClient order;
    private CustomerClient customer;
    private CheckoutService service;

    @BeforeEach
    void setUp() {
        cart = mock(CartClient.class);
        catalog = mock(CatalogPricingClient.class);
        shipping = mock(ShippingClient.class);
        order = mock(OrderClient.class);
        customer = mock(CustomerClient.class);

        ResilientFeignCaller resilient = new ResilientFeignCaller(
                CircuitBreakerRegistry.ofDefaults(),
                RetryRegistry.of(RetryConfig.custom().maxAttempts(1).build()),
                RateLimiterRegistry.of(RateLimiterConfig.ofDefaults()));
        service = new CheckoutService(cart, catalog, shipping, order, customer, resilient);

        // A bag whose stored line and subtotal both claim one cent. This is
        // what a pre-fix cart would have handed over, and what a compromised or
        // buggy cart could still hand over.
        bagHolds(1);
        catalogSays("PRICED", REAL_PRICE, "ZMW");
        shippingQuotes(selfCollect());
        orderRecordsTotal("4500.00");
        doReturn(List.of(new CustomerClient.Address(ADDRESS, "ZM"))).when(customer).myAddresses();
    }

    // ------------------------------------------------------------- fixtures

    private void bagHolds(int quantity) {
        cartHolds(item(VARIANT, "Free ring please", quantity));
    }

    private void cartHolds(CartClient.CartItem... items) {
        when(cart.getMyCart()).thenReturn(new CartClient.CartView(UUID.randomUUID(), ONE_CENT, List.of(items)));
    }

    private static CartClient.CartItem item(UUID variant, String title, int quantity) {
        return new CartClient.CartItem(UUID.randomUUID(), variant, title, quantity, ONE_CENT, ONE_CENT);
    }

    private void catalogSays(String status, BigDecimal price, String currency) {
        doReturn(new CatalogPricingClient.PricingResponse(List.of(new CatalogPricingClient.Entry(
                VARIANT, status, UUID.randomUUID(), "Aurelia 18k", RING, price, currency))))
                .when(catalog).price(any());
    }

    /** A PRICED line in ZMW: the LIST price, what a promotion takes off each piece, and the promotion that does. */
    private static CatalogPricingClient.Entry priced(UUID variant, String title, String price, String discountPerPiece, UUID promotion) {
        BigDecimal list = new BigDecimal(price);
        BigDecimal off = new BigDecimal(discountPerPiece);
        return new CatalogPricingClient.Entry(variant, "PRICED", UUID.randomUUID(), title, title, list, "ZMW", off, promotion, list.subtract(off));
    }

    private void catalogPrices(CatalogPricingClient.Entry... entries) {
        doReturn(new CatalogPricingClient.PricingResponse(List.of(entries))).when(catalog).price(any());
    }

    /** Two rings, 500.00 off each by one promotion: 9,000.00 at list, 8,000.00 after the discount. */
    private void aPromotedBag() {
        bagHolds(2);
        catalogPrices(priced(VARIANT, RING, "4500.00", "500.00", PROMOTION));
    }

    private static QuoteLine promotedLine() {
        return new QuoteLine(VARIANT, 2, new BigDecimal("4500.00"), new BigDecimal("500.00"), PROMOTION);
    }

    /** What shipping answers for one quote: the amount that goes on the order, in the given currency. */
    private static ShippingClient.QuoteReply quoted(UUID town, String option, String payer, String amount, String currency) {
        return new ShippingClient.QuoteReply(town, town == null ? null : "Lusaka", option, option, payer, new BigDecimal(amount), currency,
                new BigDecimal(amount), false, 0, 2);
    }

    private static ShippingClient.QuoteReply selfCollect() {
        return quoted(null, "SELF_COLLECT", null, "0.00", "ZMW");
    }

    /** A delivered option to TOWN, the courier paid by Oriah (so its price is in the total). */
    private static ShippingClient.QuoteReply delivered(String option, String amount) {
        return quoted(TOWN, option, ORIAH_COLLECTS, amount, "ZMW");
    }

    /** Shipping answering the same quote whatever is asked. */
    private void shippingQuotes(ShippingClient.QuoteReply reply) {
        // doReturn, not when(...): quote() may already be stubbed to throw, and
        // when(mock.quote(any())) would CALL it while setting up the new stub.
        doReturn(reply).when(shipping).quote(any());
    }

    /** Shipping answering for the options it OFFERS, and 409 town-not-offered for any other, as the real one does. */
    private void shippingOffers(ShippingClient.QuoteReply... offered) {
        doAnswer(call -> {
            ShippingClient.QuoteAsk ask = call.getArgument(0);
            return Arrays.stream(offered)
                    .filter(reply -> reply.optionCode().equals(ask.optionCode()))
                    .findFirst()
                    .orElseThrow(() -> shippingRefuses(409, "town-not-offered", "We do not deliver there yet."));
        }).when(shipping).quote(any());
    }

    /** order answering as the real one does: CREATED, its pieces held, at the total IT priced. */
    private void orderRecordsTotal(String total) {
        doReturn(new OrderClient.OrderResponse(ORDER_ID, "kc-alice", "CREATED", REAL_PRICE, ZERO, ZERO,
                new BigDecimal(total), "ZMW", null, null, HOLD_EXPIRES)).when(order).place(any(), any());
    }

    /** A FeignException carrying a real status, as the client would throw. */
    private static FeignException feign(int status) {
        Request request = Request.create(Request.HttpMethod.POST,
                "http://downstream/api/v1/x", Map.of(),
                new byte[0], StandardCharsets.UTF_8, new RequestTemplate());
        return FeignException.errorStatus("Client#call()",
                Response.builder().status(status).reason("generated")
                        .request(request).headers(Map.of()).build());
    }

    /** A FeignException carrying a problem+json body of the given type. */
    private static FeignException feignProblem(String method, int status, String type) {
        return feignProblem(method, status, type, "refused");
    }

    private static FeignException feignProblem(String method, int status, String type, String detail) {
        Request request = Request.create(Request.HttpMethod.POST, "http://downstream/x", Map.of(),
                new byte[0], StandardCharsets.UTF_8, new RequestTemplate());
        byte[] body = ("{\"type\":\"" + type + "\",\"status\":" + status + ",\"detail\":\"" + detail + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        return FeignException.errorStatus(method,
                Response.builder().status(status).reason("refused").request(request)
                        .headers(Map.of("Content-Type", List.of("application/problem+json")))
                        .body(body).build());
    }

    /** What shipping's refusal of a choice looks like on the wire. */
    private static FeignException shippingRefuses(int status, String code, String detail) {
        return feignProblem("ShippingClient#quote(QuoteAsk)", status, "https://oriah.dev/problems/" + code, detail);
    }

    /**
     * The digest of the quote the shopper SAW -- computed with the shared rule
     * rather than pasted, so a fixture cannot drift from it.
     */
    private static String saw(String option, String delivery, QuoteLine... lines) {
        return QuoteDigest.of(new Quote(List.of(lines), "ZMW", option, new BigDecimal(delivery)));
    }

    /** The digest of a delivered quote to TOWN, the courier paid by {@code payer}. */
    private static String sawDelivered(String option, String payer, String delivery, QuoteLine... lines) {
        return QuoteDigest.of(new Quote(List.of(lines), "ZMW", option, new BigDecimal(delivery), TOWN, payer));
    }

    private static QuoteLine line(UUID variant, int quantity, String price) {
        return new QuoteLine(variant, quantity, new BigDecimal(price), ZERO);
    }

    /** What the shopper saw for this fixture's default bag: one ring, self-collected. */
    private static String sawOneRing() {
        return saw("SELF_COLLECT", "0.00", line(VARIANT, 1, "4500.00"));
    }

    /** A confirm for an option that needs no town (self-collect). */
    private static ConfirmRequest confirm(String option, String quoteDigest) {
        return new ConfirmRequest(ADDRESS, option, null, null, quoteDigest);
    }

    /** Who the parcel is for, as the shopper typed it (a Zambian phone number written the way Zambians write it). */
    private static final String RECIPIENT = "  Mulenga   Chanda ";
    private static final String RECIPIENT_PHONE_TYPED = "097 123 4567";

    /** A confirm for a delivered option to TOWN, to a named recipient. */
    private static ConfirmRequest confirmTo(String option, String payer, String quoteDigest) {
        return new ConfirmRequest(ADDRESS, option, TOWN, payer, quoteDigest, RECIPIENT, RECIPIENT_PHONE_TYPED);
    }

    private PreviewResponse previewFor(String option) {
        return service.preview(new PreviewRequest(ADDRESS, option, null, null));
    }

    private PreviewResponse previewTo(String option, String payer) {
        return service.preview(new PreviewRequest(ADDRESS, option, TOWN, payer));
    }

    /** What checkout asked shipping -- the one time it asked. */
    private ShippingClient.QuoteAsk asked() {
        ArgumentCaptor<ShippingClient.QuoteAsk> ask = ArgumentCaptor.forClass(ShippingClient.QuoteAsk.class);
        verify(shipping).quote(ask.capture());
        return ask.getValue();
    }

    private OrderClient.PlaceOrderRequest placement() {
        ArgumentCaptor<OrderClient.PlaceOrderRequest> placed =
                ArgumentCaptor.forClass(OrderClient.PlaceOrderRequest.class);
        verify(order).place(any(), placed.capture());
        return placed.getValue();
    }

    private void nothingWasOrdered() {
        verify(order, never()).place(any(), any());
        verify(cart, never()).clearMyCart();
    }

    // ------------------------------------------------------------- the price

    @Test
    @DisplayName("the quote confirm holds the order to is catalog's price, not the cart's subtotal")
    void theQuoteIsCatalogsPriceNotTheCartsSubtotal() {
        // The bag said 0.01; catalog said 4500.00. The digest of 4500.00 is the
        // quote the shopper saw, and it can only match because checkout priced
        // the bag itself.
        ConfirmResponse response = service.confirm(confirm("SELF_COLLECT", sawOneRing()), "key-1");

        assertThat(placement().items().getFirst().unitPrice()).as("the bag said 0.01; catalog said 4500.00").isEqualByComparingTo(REAL_PRICE);
        assertThat(response.total()).isEqualByComparingTo(REAL_PRICE);
    }

    @Test
    @DisplayName("a digest worked out from the cart's own price is refused: the cart is not the authority")
    void aDigestFromTheCartsOwnPriceIsRefused() {
        String sawAtTheCartsPrice = saw("SELF_COLLECT", "0.00", line(VARIANT, 1, "0.01"));

        assertThatThrownBy(() -> service.confirm(confirm("SELF_COLLECT", sawAtTheCartsPrice), "key-1b"))
                .isInstanceOf(PricingChangedException.class);
        nothingWasOrdered();
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
    @DisplayName("the currency comes from catalog, not from a constant: a bag priced in another currency end to end is asked of shipping, digested and placed in that currency")
    void theCurrencyIsCatalogsNotAConstant() {
        // Every other fixture is ZMW, so a hardcoded "ZMW" would pass them all. Here catalog prices in USD, shipping answers in USD, and the shopper's
        // digest is the USD quote's: only a currency read from catalog can make this confirm.
        catalogSays("PRICED", REAL_PRICE, "USD");
        shippingQuotes(quoted(null, "SELF_COLLECT", null, "0.00", "USD"));
        String usdDigest = QuoteDigest.of(new Quote(List.of(line(VARIANT, 1, "4500.00")), "USD", "SELF_COLLECT", ZERO));
        doReturn(new OrderClient.OrderResponse(ORDER_ID, "kc-alice", "CREATED", REAL_PRICE, ZERO, ZERO, REAL_PRICE, "USD", null, null, HOLD_EXPIRES))
                .when(order).place(any(), any());

        ConfirmResponse response = service.confirm(confirm("SELF_COLLECT", usdDigest), "key-4");

        assertThat(asked().currency()).as("what shipping was asked in").isEqualTo("USD");
        assertThat(placement().currency()).as("what order was told").isEqualTo("USD");
        assertThat(response.currency()).isEqualTo("USD");
    }

    // --------------------------------------------- preview: the quote it shows

    @Test
    @DisplayName("preview without a chosen delivery prices the bag but quotes no total and no digest, and asks shipping nothing")
    void previewWithoutAChoiceQuotesNoTotal() {
        PreviewResponse preview = previewFor(null);

        // There is no total to agree to until the shopper has chosen how the
        // piece reaches them -- showing the cheapest as "the" total is how a
        // K0 option used to make every delivery free.
        assertThat(preview.subtotal()).isEqualByComparingTo(REAL_PRICE);
        assertThat(preview.discount()).isEqualByComparingTo("0.00");
        assertThat(preview.currency()).isEqualTo("ZMW");
        assertThat(preview.shippingCost()).isNull();
        assertThat(preview.total()).isNull();
        assertThat(preview.deliveryOption()).isNull();
        assertThat(preview.deliveryTownId()).isNull();
        assertThat(preview.payer()).isNull();
        assertThat(preview.quoteDigest()).isNull();
        assertThat(preview.lines()).hasSize(1);
        PreviewResponse.LineDto ring = preview.lines().getFirst();
        assertThat(ring.variantId()).isEqualTo(VARIANT);
        assertThat(ring.title()).isEqualTo(RING);
        assertThat(ring.quantity()).isEqualTo(1);
        assertThat(ring.unitPrice()).isEqualByComparingTo(REAL_PRICE);
        assertThat(ring.discountPerPiece()).isEqualByComparingTo("0.00");
        assertThat(ring.promotionId()).isNull();
        assertThat(ring.netUnitPrice()).isEqualByComparingTo(REAL_PRICE);
        verifyNoInteractions(shipping);
    }

    @Test
    @DisplayName("preview for a chosen delivery shows its cost, its total, its town and payer, and the digest of exactly that")
    void previewForAChoiceShowsItsTotalAndDigest() {
        shippingQuotes(delivered("OWN_DELIVERY", "125.00"));

        PreviewResponse preview = previewTo("OWN_DELIVERY", ORIAH_COLLECTS);

        assertThat(preview.subtotal()).isEqualByComparingTo(REAL_PRICE);
        assertThat(preview.shippingCost()).isEqualByComparingTo("125.00");
        assertThat(preview.discount()).isEqualByComparingTo("0.00");
        assertThat(preview.total()).isEqualByComparingTo("4625.00");
        assertThat(preview.currency()).isEqualTo("ZMW");
        assertThat(preview.deliveryOption()).isEqualTo("OWN_DELIVERY");
        assertThat(preview.deliveryTownId()).isEqualTo(TOWN);
        assertThat(preview.payer()).isEqualTo(ORIAH_COLLECTS);
        assertThat(preview.quoteDigest())
                .isEqualTo(sawDelivered("OWN_DELIVERY", ORIAH_COLLECTS, "125.00", line(VARIANT, 1, "4500.00")));
    }

    @Test
    @DisplayName("a payer the shopper did not name is the one shipping resolved, and the digest binds it")
    void aPayerShippingResolvedIsShownAndBound() {
        shippingQuotes(delivered("OWN_DELIVERY", "125.00"));

        PreviewResponse preview = previewTo("OWN_DELIVERY", null);

        assertThat(asked().payer()).as("the shopper named none").isNull();
        assertThat(preview.payer()).isEqualTo(ORIAH_COLLECTS);
        assertThat(preview.quoteDigest())
                .isEqualTo(sawDelivered("OWN_DELIVERY", ORIAH_COLLECTS, "125.00", line(VARIANT, 1, "4500.00")));
    }

    @Test
    @DisplayName("preview with no address looks nothing up: self-collect needs none")
    void previewWithoutAnAddressLooksNothingUp() {
        service.preview(new PreviewRequest(null, "SELF_COLLECT", null, null));

        verifyNoInteractions(customer);
    }

    @Test
    @DisplayName("a delivery cost finer than a ngwee is OUR fault: 500, never a quote")
    void aDeliveryCostFinerThanANgweeIsRefused() {
        shippingQuotes(quoted(null, "SELF_COLLECT", null, "0.005", "ZMW"));

        assertThatThrownBy(() -> previewFor("SELF_COLLECT")).isInstanceOf(DownstreamRejectedException.class);
    }

    @Test
    @DisplayName("a negative delivery cost is OUR fault: 500, never a quote")
    void aNegativeDeliveryCostIsRefused() {
        shippingQuotes(quoted(null, "SELF_COLLECT", null, "-1.00", "ZMW"));

        assertThatThrownBy(() -> previewFor("SELF_COLLECT")).isInstanceOf(DownstreamRejectedException.class);
    }

    @Test
    @DisplayName("shipping answering with no cost at all is refused, not read as free")
    void aShippingAnswerWithNoCostIsRefused() {
        shippingQuotes(new ShippingClient.QuoteReply(null, null, "SELF_COLLECT", "SELF_COLLECT", null, null, "ZMW", null, false, null, null));

        assertThatThrownBy(() -> service.confirm(confirm("SELF_COLLECT", sawOneRing()), "key-37"))
                .isInstanceOf(DownstreamRejectedException.class);
        nothingWasOrdered();
    }

    @Test
    @DisplayName("shipping answering with no body at all is refused, not read as free")
    void aShippingAnswerWithNoBodyIsRefused() {
        doReturn(null).when(shipping).quote(any());

        assertThatThrownBy(() -> service.confirm(confirm("SELF_COLLECT", sawOneRing()), "key-38"))
                .isInstanceOf(DownstreamRejectedException.class);
        nothingWasOrdered();
    }

    // --------------------------------------------- confirm: the option placed

    @Test
    @DisplayName("confirm quotes and places the option the shopper NAMED, not the cheapest one offered")
    void theNamedOptionIsQuotedAndPlaced() {
        shippingOffers(selfCollect(), delivered("OWN_DELIVERY", "125.00"));
        orderRecordsTotal("4625.00");

        ConfirmResponse response = service.confirm(confirmTo("OWN_DELIVERY", ORIAH_COLLECTS,
                sawDelivered("OWN_DELIVERY", ORIAH_COLLECTS, "125.00", line(VARIANT, 1, "4500.00"))), "k");

        assertThat(asked().optionCode()).isEqualTo("OWN_DELIVERY");
        assertThat(response.total()).as("4500 + 125, not 4500 + the cheapest 0").isEqualByComparingTo("4625.00");
        assertThat(placement().deliveryOption()).isEqualTo("OWN_DELIVERY");
    }

    @Test
    @DisplayName("a delivered bag is placed with who it is for, tidied and in one spelling: the name trimmed, the phone as +260 and nine digits")
    void aDeliveredBagIsPlacedWithItsRecipient() {
        shippingOffers(selfCollect(), delivered("OWN_DELIVERY", "125.00"));
        orderRecordsTotal("4625.00");

        service.confirm(confirmTo("OWN_DELIVERY", ORIAH_COLLECTS, sawDelivered("OWN_DELIVERY", ORIAH_COLLECTS, "125.00", line(VARIANT, 1, "4500.00"))), "k");

        assertThat(placement().recipientName()).isEqualTo("Mulenga Chanda");
        assertThat(placement().recipientPhone()).isEqualTo("+260971234567");
    }

    @Test
    @DisplayName("a recipient that cannot be read is refused before anything is asked of order: order's 400 would reach the shopper as a server fault")
    void anUnreadableRecipientNeverReachesOrder() {
        shippingOffers(selfCollect(), delivered("OWN_DELIVERY", "125.00"));
        String saw = sawDelivered("OWN_DELIVERY", ORIAH_COLLECTS, "125.00", line(VARIANT, 1, "4500.00"));

        assertThatThrownBy(() -> service.confirm(new ConfirmRequest(ADDRESS, "OWN_DELIVERY", TOWN, ORIAH_COLLECTS, saw, "Mulenga Chanda", "12345"), "k"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("phone");
        assertThatThrownBy(() -> service.confirm(new ConfirmRequest(ADDRESS, "OWN_DELIVERY", TOWN, ORIAH_COLLECTS, saw, null, "0971234567"), "k"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("name");
        nothingWasOrdered();
    }

    @Test
    @DisplayName("an option shipping does not offer at confirm is passed on as its own 409 refusal, and nothing is placed")
    void anOptionNotOfferedIsRefused() {
        // Withdrawn since preview, or never offered: checkout cannot tell which,
        // and either way there is no price to place at. Shipping's own answer
        // is passed on, so the storefront shows what IS offered rather than a
        // field error the shopper cannot fix.
        shippingOffers(selfCollect());

        assertThatThrownBy(() -> service.confirm(confirmTo("OWN_DELIVERY", ORIAH_COLLECTS,
                sawDelivered("OWN_DELIVERY", ORIAH_COLLECTS, "125.00", line(VARIANT, 1, "4500.00"))), "k"))
                .isInstanceOfSatisfying(RefusalPassedOnException.class, refusal -> {
                    assertThat(refusal.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(refusal.code()).isEqualTo("town-not-offered");
                });

        nothingWasOrdered();
    }

    @Test
    @DisplayName("self-collect needs no address: nothing is looked up, no town or payer is asked about, and none is placed")
    void selfCollectNeedsNoAddress() {
        service.confirm(new ConfirmRequest(null, "SELF_COLLECT", null, null, sawOneRing()), "k");

        ShippingClient.QuoteAsk ask = asked();
        assertThat(ask.optionCode()).isEqualTo("SELF_COLLECT");
        assertThat(ask.townId()).isNull();
        assertThat(ask.payer()).isNull();
        verifyNoInteractions(customer);
        assertThat(placement().shippingAddressId()).isNull();
        assertThat(placement().deliveryTownId()).isNull();
        assertThat(placement().payer()).isNull();
        assertThat(placement().recipientName()).as("a pickup has no recipient").isNull();
        assertThat(placement().recipientPhone()).isNull();
    }

    @Test
    @DisplayName("a self-collect quote never carries a town or payer, even if shipping names one: order's digest has none, so checkout's must not")
    void aSelfCollectQuoteNeverCarriesATownOrPayer() {
        // Shipping has no business naming a town or a payer for self-collect. But a quote that did must still hash as the plain self-collect one: order builds
        // its digest without them, and a digest with them would make every self-collect a "pricing changed".
        shippingQuotes(quoted(TOWN, "SELF_COLLECT", ORIAH_COLLECTS, "0.00", "ZMW"));

        PreviewResponse preview = previewFor("SELF_COLLECT");

        assertThat(preview.deliveryTownId()).isNull();
        assertThat(preview.payer()).isNull();
        assertThat(preview.quoteDigest()).isEqualTo(sawOneRing());

        service.confirm(confirm("SELF_COLLECT", sawOneRing()), "k");

        assertThat(placement().quoteDigest()).isEqualTo(sawOneRing());
    }

    // --------------------------------------------- quote, then commit (4.4)

    @Test
    @DisplayName("preview places what it showed: the digest it hands out confirms, at its total")
    void previewPlacesWhatItShowed() {
        PreviewResponse preview = previewFor("SELF_COLLECT");

        ConfirmResponse response = service.confirm(confirm("SELF_COLLECT", preview.quoteDigest()), "key-3");

        assertThat(preview.subtotal()).isEqualByComparingTo(REAL_PRICE);
        assertThat(response.total()).isEqualByComparingTo(preview.total());
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
        // only one that may be placed (Q24).
        String sawOne = previewFor("SELF_COLLECT").quoteDigest();
        bagHolds(3);

        assertThatThrownBy(() -> service.confirm(confirm("SELF_COLLECT", sawOne), "k"))
                .isInstanceOf(PricingChangedException.class);
        nothingWasOrdered();
    }

    @Test
    @DisplayName("two quantities swapped between lines is refused even though the total is unchanged")
    void anEqualTotalSwapIsRefused() {
        doReturn(new CatalogPricingClient.PricingResponse(List.of(
                new CatalogPricingClient.Entry(VARIANT, "PRICED", UUID.randomUUID(), "A", "A", REAL_PRICE, "ZMW"),
                new CatalogPricingClient.Entry(SECOND, "PRICED", UUID.randomUUID(), "B", "B", REAL_PRICE, "ZMW"))))
                .when(catalog).price(any());
        cartHolds(item(VARIANT, "A", 2), item(SECOND, "B", 1));
        String sawOneAndTwo = saw("SELF_COLLECT", "0.00",
                line(VARIANT, 1, "4500.00"), line(SECOND, 2, "4500.00"));

        assertThatThrownBy(() -> service.confirm(confirm("SELF_COLLECT", sawOneAndTwo), "k"))
                .isInstanceOf(PricingChangedException.class);
        nothingWasOrdered();
    }

    @Test
    @DisplayName("a price changed after preview is refused before anything is ordered")
    void aPriceChangedAfterPreviewIsRefused() {
        String sawAt4500 = previewFor("SELF_COLLECT").quoteDigest();
        catalogSays("PRICED", new BigDecimal("4600.00"), "ZMW");

        assertThatThrownBy(() -> service.confirm(confirm("SELF_COLLECT", sawAt4500), "k"))
                .isInstanceOf(PricingChangedException.class);
        nothingWasOrdered();
    }

    @Test
    @DisplayName("a delivery price changed after preview is refused -- a price published in between")
    void aDeliveryPriceChangedAfterPreviewIsRefused() {
        shippingQuotes(delivered("OWN_DELIVERY", "100.00"));
        String sawAt100 = previewTo("OWN_DELIVERY", ORIAH_COLLECTS).quoteDigest();
        shippingQuotes(delivered("OWN_DELIVERY", "125.00"));

        assertThatThrownBy(() -> service.confirm(confirmTo("OWN_DELIVERY", ORIAH_COLLECTS, sawAt100), "k"))
                .isInstanceOf(PricingChangedException.class);
        nothingWasOrdered();
    }

    @Test
    @DisplayName("a stable delivery price is fine: items plus the delivery the shopper was shown")
    void aStableDeliveryPriceIsPlaced() {
        shippingQuotes(delivered("OWN_DELIVERY", "125.00"));
        orderRecordsTotal("4625.00");
        PreviewResponse preview = previewTo("OWN_DELIVERY", ORIAH_COLLECTS);

        ConfirmResponse response = service.confirm(confirmTo("OWN_DELIVERY", ORIAH_COLLECTS, preview.quoteDigest()), "key-24");

        assertThat(response.total()).isEqualByComparingTo("4625.00");
        assertThat(placement().quoteDigest()).isEqualTo(preview.quoteDigest());
    }

    @Test
    @DisplayName("a digest made for another town is refused: the town is part of what the shopper agreed to")
    void aDigestForAnotherTownIsRefused() {
        shippingQuotes(delivered("OWN_DELIVERY", "125.00"));
        String sawForAnotherTown = QuoteDigest.of(new Quote(List.of(line(VARIANT, 1, "4500.00")), "ZMW", "OWN_DELIVERY",
                new BigDecimal("125.00"), OTHER_TOWN, ORIAH_COLLECTS));

        assertThatThrownBy(() -> service.confirm(confirmTo("OWN_DELIVERY", ORIAH_COLLECTS, sawForAnotherTown), "k"))
                .isInstanceOf(PricingChangedException.class);
        nothingWasOrdered();
    }

    @Test
    @DisplayName("a digest that names no payer is refused: who pays the courier is part of what the shopper agreed to")
    void aDigestWithNoPayerIsRefused() {
        shippingQuotes(delivered("OWN_DELIVERY", "125.00"));
        String sawWithNoPayer = QuoteDigest.of(new Quote(List.of(line(VARIANT, 1, "4500.00")), "ZMW", "OWN_DELIVERY",
                new BigDecimal("125.00"), TOWN, null));

        assertThatThrownBy(() -> service.confirm(confirmTo("OWN_DELIVERY", ORIAH_COLLECTS, sawWithNoPayer), "k"))
                .isInstanceOf(PricingChangedException.class);
        nothingWasOrdered();
    }

    @Test
    @DisplayName("a delivered quote carries its town, payer and digest to order, with the LIST price on each line")
    void aDeliveredQuoteIsPlacedWithTownPayerAndListPrice() {
        aPromotedBag();
        shippingQuotes(delivered("OWN_DELIVERY", "125.00"));
        orderRecordsTotal("8125.00");
        String saw = sawDelivered("OWN_DELIVERY", ORIAH_COLLECTS, "125.00", promotedLine());

        service.confirm(confirmTo("OWN_DELIVERY", ORIAH_COLLECTS, saw), "k");

        OrderClient.PlaceOrderRequest placed = placement();
        assertThat(placed.deliveryOption()).isEqualTo("OWN_DELIVERY");
        assertThat(placed.deliveryTownId()).isEqualTo(TOWN);
        assertThat(placed.payer()).isEqualTo(ORIAH_COLLECTS);
        assertThat(placed.shippingAddressId()).isEqualTo(ADDRESS);
        assertThat(placed.currency()).isEqualTo("ZMW");
        assertThat(placed.quoteDigest()).isEqualTo(saw);
        assertThat(placed.items()).hasSize(1);
        OrderClient.LineItem ring = placed.items().getFirst();
        assertThat(ring.variantId()).isEqualTo(VARIANT);
        assertThat(ring.quantity()).isEqualTo(2);
        assertThat(ring.unitPrice())
                .as("the LIST price: order takes the discount from catalog itself and refuses a quote that does not match")
                .isEqualByComparingTo("4500.00");
    }

    // --------------------------------------------- what shipping is asked, and answers

    @Test
    @DisplayName("preview asks shipping for the exact town, option and payer, on the goods AFTER the discount")
    void previewAsksShippingForTheGoodsAfterDiscount() {
        aPromotedBag();
        shippingQuotes(delivered("OWN_DELIVERY", "125.00"));

        previewTo("OWN_DELIVERY", ORIAH_COLLECTS);

        ShippingClient.QuoteAsk ask = asked();
        assertThat(ask.townId()).isEqualTo(TOWN);
        assertThat(ask.optionCode()).isEqualTo("OWN_DELIVERY");
        assertThat(ask.payer()).isEqualTo(ORIAH_COLLECTS);
        assertThat(ask.goodsTotal())
                .as("two rings at 4500.00 less 500.00 each: a free-delivery threshold is judged on 8,000.00, not on 9,000.00")
                .isEqualByComparingTo("8000.00");
        assertThat(ask.currency()).isEqualTo("ZMW");
    }

    @Test
    @DisplayName("confirm asks shipping for the exact town, option and payer, on the goods AFTER the discount")
    void confirmAsksShippingForTheGoodsAfterDiscount() {
        aPromotedBag();
        shippingQuotes(delivered("OWN_DELIVERY", "125.00"));
        orderRecordsTotal("8125.00");

        service.confirm(confirmTo("OWN_DELIVERY", ORIAH_COLLECTS,
                sawDelivered("OWN_DELIVERY", ORIAH_COLLECTS, "125.00", promotedLine())), "k");

        ShippingClient.QuoteAsk ask = asked();
        assertThat(ask.townId()).isEqualTo(TOWN);
        assertThat(ask.optionCode()).isEqualTo("OWN_DELIVERY");
        assertThat(ask.payer()).isEqualTo(ORIAH_COLLECTS);
        assertThat(ask.goodsTotal()).isEqualByComparingTo("8000.00");
        assertThat(ask.currency()).isEqualTo("ZMW");
    }

    @Test
    @DisplayName("a courier the recipient pays costs nothing on this order: delivery 0.00, total is the goods, and the digest binds the payer")
    void aCourierTheRecipientPaysCostsNothingOnThisOrder() {
        shippingQuotes(quoted(TOWN, "YANGO", RECIPIENT_PAYS, "0.00", "ZMW"));

        PreviewResponse preview = previewTo("YANGO", RECIPIENT_PAYS);

        assertThat(asked().payer()).isEqualTo(RECIPIENT_PAYS);
        assertThat(preview.shippingCost()).isEqualByComparingTo("0.00");
        assertThat(preview.total()).isEqualByComparingTo(REAL_PRICE);
        assertThat(preview.payer()).isEqualTo(RECIPIENT_PAYS);
        assertThat(preview.quoteDigest())
                .isEqualTo(sawDelivered("YANGO", RECIPIENT_PAYS, "0.00", line(VARIANT, 1, "4500.00")));
        assertThat(preview.quoteDigest())
                .as("the same bag with Oriah collecting is another quote")
                .isNotEqualTo(sawDelivered("YANGO", ORIAH_COLLECTS, "0.00", line(VARIANT, 1, "4500.00")));
    }

    @Test
    @DisplayName("a courier the recipient pays is placed at the goods total, with that payer")
    void aCourierTheRecipientPaysIsPlaced() {
        shippingQuotes(quoted(TOWN, "YANGO", RECIPIENT_PAYS, "0.00", "ZMW"));
        orderRecordsTotal("4500.00");

        ConfirmResponse response = service.confirm(confirmTo("YANGO", RECIPIENT_PAYS,
                sawDelivered("YANGO", RECIPIENT_PAYS, "0.00", line(VARIANT, 1, "4500.00"))), "k");

        assertThat(response.total()).isEqualByComparingTo(REAL_PRICE);
        assertThat(placement().payer()).isEqualTo(RECIPIENT_PAYS);
        assertThat(placement().deliveryTownId()).isEqualTo(TOWN);
    }

    @Test
    @DisplayName("shipping answering RECIPIENT_PAYS with a cost is OUR 500, never a quote, at confirm and at preview")
    void aRecipientPaysCourierThatStillCostsSomethingIsRefused() {
        shippingQuotes(quoted(TOWN, "YANGO", RECIPIENT_PAYS, "75.00", "ZMW"));

        // No digest could be worked out for such a quote (the shared rule refuses to build it), so any one will do:
        // the refusal must come from the shipping answer, not from the digest.
        assertThatThrownBy(() -> service.confirm(confirmTo("YANGO", RECIPIENT_PAYS, "0".repeat(64)), "k"))
                .isInstanceOf(DownstreamRejectedException.class);
        assertThatThrownBy(() -> previewTo("YANGO", RECIPIENT_PAYS)).isInstanceOf(DownstreamRejectedException.class);
        nothingWasOrdered();
    }

    @Test
    @DisplayName("shipping answering a payer checkout does not know is OUR 500, never a quote")
    void anUnknownPayerIsRefused() {
        shippingQuotes(quoted(TOWN, "YANGO", "SOMEONE_ELSE", "75.00", "ZMW"));

        assertThatThrownBy(() -> previewTo("YANGO", null)).isInstanceOf(DownstreamRejectedException.class);
    }

    @ParameterizedTest(name = "shipping answering {0} {1} at confirm is passed on as a 409 {1}, and nothing is ordered")
    @CsvSource({"409,town-not-offered", "409,payer-not-offered", "404,town-not-found"})
    void shippingsRefusalOfTheChoiceIsPassedOnAtConfirm(int answered, String code) {
        doThrow(shippingRefuses(answered, code, "We do not deliver there yet.")).when(shipping).quote(any());

        // The digest is beside the point: the refusal comes first.
        assertThatThrownBy(() -> service.confirm(confirmTo("OWN_DELIVERY", ORIAH_COLLECTS, "0".repeat(64)), "k"))
                .isInstanceOfSatisfying(RefusalPassedOnException.class, refusal -> {
                    assertThat(refusal.status()).as("a 404 is passed on as a 409: the shopper can choose another").isEqualTo(HttpStatus.CONFLICT);
                    assertThat(refusal.code()).as("the type is https://oriah.dev/problems/" + code).isEqualTo(code);
                    assertThat(refusal.getMessage()).isEqualTo("We do not deliver there yet.");
                });
        nothingWasOrdered();
    }

    @ParameterizedTest(name = "shipping answering {0} {1} at preview is passed on as a 409 {1}: no quote, no digest")
    @CsvSource({"409,town-not-offered", "409,payer-not-offered", "404,town-not-found"})
    void shippingsRefusalOfTheChoiceIsPassedOnAtPreview(int answered, String code) {
        // Not a silent "no quote": a preview that said nothing would leave the
        // shopper unable to tell a town not yet priced from a broken page.
        doThrow(shippingRefuses(answered, code, "We do not deliver there yet.")).when(shipping).quote(any());

        assertThatThrownBy(() -> previewTo("OWN_DELIVERY", ORIAH_COLLECTS))
                .isInstanceOfSatisfying(RefusalPassedOnException.class, refusal -> {
                    assertThat(refusal.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(refusal.code()).isEqualTo(code);
                });
    }

    // --------------------------------------------- promotions

    @Test
    @DisplayName("a promotion line: preview shows what comes off each piece, the discount sums per piece, and the digest is that of the promoted quote")
    void aPromotionLineIsShownPerPieceAndBoundIntoTheDigest() {
        // Two rings at 4,500 with 500 off each, and a cuff at 1,000 that no
        // promotion touches: 10,000.00 at list, 1,000.00 off, 125.00 delivery.
        cartHolds(item(VARIANT, "Ring", 2), item(SECOND, "Cuff", 1));
        catalogPrices(priced(VARIANT, RING, "4500.00", "500.00", PROMOTION),
                priced(SECOND, "Cuff", "1000.00", "0.00", null));
        shippingQuotes(delivered("OWN_DELIVERY", "125.00"));

        PreviewResponse preview = previewTo("OWN_DELIVERY", ORIAH_COLLECTS);

        assertThat(preview.subtotal()).isEqualByComparingTo("10000.00");
        assertThat(preview.discount()).as("500.00 x 2 pieces, and nothing for the cuff").isEqualByComparingTo("1000.00");
        assertThat(preview.shippingCost()).isEqualByComparingTo("125.00");
        assertThat(preview.total()).isEqualByComparingTo("9125.00");
        assertThat(preview.total()).as("total = subtotal - discount + shipping")
                .isEqualByComparingTo(preview.subtotal().subtract(preview.discount()).add(preview.shippingCost()));

        assertThat(preview.lines()).hasSize(2);
        PreviewResponse.LineDto rings = preview.lines().get(0);
        assertThat(rings.variantId()).isEqualTo(VARIANT);
        assertThat(rings.quantity()).isEqualTo(2);
        assertThat(rings.unitPrice()).as("the LIST price").isEqualByComparingTo("4500.00");
        assertThat(rings.discountPerPiece()).isEqualByComparingTo("500.00");
        assertThat(rings.promotionId()).isEqualTo(PROMOTION);
        assertThat(rings.netUnitPrice()).isEqualByComparingTo("4000.00");
        PreviewResponse.LineDto cuff = preview.lines().get(1);
        assertThat(cuff.variantId()).isEqualTo(SECOND);
        assertThat(cuff.discountPerPiece()).isEqualByComparingTo("0.00");
        assertThat(cuff.promotionId()).isNull();
        assertThat(cuff.netUnitPrice()).isEqualByComparingTo("1000.00");

        assertThat(preview.quoteDigest()).isEqualTo(QuoteDigest.of(new Quote(
                List.of(promotedLine(), new QuoteLine(SECOND, 1, new BigDecimal("1000.00"), ZERO)),
                "ZMW", "OWN_DELIVERY", new BigDecimal("125.00"), TOWN, ORIAH_COLLECTS)));
    }

    @Test
    @DisplayName("a promoted bag confirms at its discounted total")
    void aPromotedBagConfirmsAtItsDiscountedTotal() {
        aPromotedBag();
        orderRecordsTotal("8000.00");
        PreviewResponse preview = previewFor("SELF_COLLECT");

        ConfirmResponse response = service.confirm(confirm("SELF_COLLECT", preview.quoteDigest()), "k");

        assertThat(preview.total()).as("9,000.00 at list less 1,000.00").isEqualByComparingTo("8000.00");
        assertThat(response.total()).isEqualByComparingTo(preview.total());
    }

    @Test
    @DisplayName("an order total WITHOUT the promotion's discount disagrees with the agreed quote and is refused")
    void anOrderTotalMissingTheDiscountIsRefused() {
        aPromotedBag();
        orderRecordsTotal("9000.00");

        assertThatThrownBy(() -> service.confirm(confirm("SELF_COLLECT", saw("SELF_COLLECT", "0.00", promotedLine())), "k"))
                .isInstanceOf(DownstreamRejectedException.class);
        verify(cart, never()).clearMyCart();
    }

    @Test
    @DisplayName("the same discount from ANOTHER promotion is another quote: the old digest is pricing-changed")
    void anotherPromotionIsAnotherQuote() {
        catalogPrices(priced(VARIANT, RING, "4500.00", "500.00", PROMOTION));
        String sawPromotion = previewFor("SELF_COLLECT").quoteDigest();
        catalogPrices(priced(VARIANT, RING, "4500.00", "500.00", OTHER_PROMOTION));

        assertThat(previewFor("SELF_COLLECT").quoteDigest()).isNotEqualTo(sawPromotion);
        assertThatThrownBy(() -> service.confirm(confirm("SELF_COLLECT", sawPromotion), "k"))
                .isInstanceOf(PricingChangedException.class);
        nothingWasOrdered();
    }

    @Test
    @DisplayName("the same discount with NO promotion named is another quote: the old digest is pricing-changed")
    void aDiscountWithNoPromotionNamedIsAnotherQuote() {
        catalogPrices(priced(VARIANT, RING, "4500.00", "500.00", PROMOTION));
        String sawPromotion = previewFor("SELF_COLLECT").quoteDigest();
        catalogPrices(priced(VARIANT, RING, "4500.00", "500.00", null));

        assertThat(previewFor("SELF_COLLECT").quoteDigest()).isNotEqualTo(sawPromotion);
        assertThatThrownBy(() -> service.confirm(confirm("SELF_COLLECT", sawPromotion), "k"))
                .isInstanceOf(PricingChangedException.class);
        nothingWasOrdered();
    }

    @Test
    @DisplayName("a promotion that has ended since preview is pricing-changed")
    void aPromotionThatEndedIsPricingChanged() {
        catalogPrices(priced(VARIANT, RING, "4500.00", "500.00", PROMOTION));
        String sawPromotion = previewFor("SELF_COLLECT").quoteDigest();
        catalogPrices(priced(VARIANT, RING, "4500.00", "0.00", null));

        assertThatThrownBy(() -> service.confirm(confirm("SELF_COLLECT", sawPromotion), "k"))
                .isInstanceOf(PricingChangedException.class);
        nothingWasOrdered();
    }

    @Test
    @DisplayName("a promotion id beside no discount is not named on the line")
    void aPromotionIdBesideNoDiscountIsNotNamed() {
        catalogPrices(priced(VARIANT, RING, "4500.00", "0.00", PROMOTION));

        PreviewResponse preview = previewFor("SELF_COLLECT");

        assertThat(preview.lines().getFirst().promotionId()).isNull();
        assertThat(preview.quoteDigest()).isEqualTo(sawOneRing());
    }

    @Test
    @DisplayName("a discount of more than the piece costs is catalog's bug: the bag is refused, not priced below zero")
    void aDiscountOfMoreThanThePieceCostsIsRefused() {
        catalogPrices(priced(VARIANT, RING, "4500.00", "5000.00", PROMOTION));

        assertThatThrownBy(() -> service.confirm(confirm("SELF_COLLECT", sawOneRing()), "k"))
                .isInstanceOf(BagNotPurchasableException.class);
        nothingWasOrdered();
    }

    @Test
    @DisplayName("a discount finer than a ngwee is catalog's bug: the bag is refused")
    void aDiscountFinerThanANgweeIsRefused() {
        catalogPrices(priced(VARIANT, RING, "4500.00", "0.005", PROMOTION));

        assertThatThrownBy(() -> previewFor("SELF_COLLECT")).isInstanceOf(BagNotPurchasableException.class);
    }

    // --------------------------------------------- order's answer (B12)

    @Test
    @DisplayName("order finding the quote changed is passed on as checkout's own pricing-changed, and the cart is not cleared")
    void ordersPricingChangedIsPassedThrough() {
        // order prices everything again before it saves; a price moving in the
        // instant between checkout's check and order's is order's to catch.
        // It used to become a generic 500 -- "the shop is broken" -- for an
        // answer the shopper can act on by reviewing their bag.
        doThrow(feignProblem("OrderClient#place(String,PlaceOrderRequest)", 409,
                "https://oriah.dev/problems/pricing-changed")).when(order).place(any(), any());

        assertThatThrownBy(() -> service.confirm(confirm("SELF_COLLECT", sawOneRing()), "k"))
                .isInstanceOf(PricingChangedException.class);
        verify(cart, never()).clearMyCart();
    }

    @Test
    @DisplayName("a 409 from order of a type checkout does not know is not mistaken for pricing-changed")
    void anUnknownOrderConflictIsNotPricingChanged() {
        doThrow(feignProblem("OrderClient#place(String,PlaceOrderRequest)", 409,
                "https://oriah.dev/problems/something-new")).when(order).place(any(), any());

        assertThatThrownBy(() -> service.confirm(confirm("SELF_COLLECT", sawOneRing()), "k"))
                .isInstanceOf(DownstreamRejectedException.class);
        verify(cart, never()).clearMyCart();
    }

    @Test
    @DisplayName("order finding a piece no longer sellable is checkout's own bag-not-purchasable, never a 500")
    void ordersBagNotPurchasableIsPassedThrough() {
        // An admin unpublishes a piece in the seconds between checkout's
        // pricing and order's. The shopper can act on that -- remove the piece
        // -- so it is passed on by type, as pricing-changed is.
        doThrow(feignProblem("OrderClient#place(String,PlaceOrderRequest)", 409,
                "https://oriah.dev/problems/bag-not-purchasable")).when(order).place(any(), any());

        assertThatThrownBy(() -> service.confirm(confirm("SELF_COLLECT", sawOneRing()), "k"))
                .isInstanceOf(BagNotPurchasableException.class);
        verify(cart, never()).clearMyCart();
    }

    @Test
    @DisplayName("order finding the bag mixes currencies is checkout's own mixed-currency, never a 500")
    void ordersMixedCurrencyIsPassedThrough() {
        doThrow(feignProblem("OrderClient#place(String,PlaceOrderRequest)", 409,
                "https://oriah.dev/problems/mixed-currency")).when(order).place(any(), any());

        assertThatThrownBy(() -> service.confirm(confirm("SELF_COLLECT", sawOneRing()), "k"))
                .isInstanceOf(MixedCurrencyException.class);
        verify(cart, never()).clearMyCart();
    }

    @ParameterizedTest(name = "order refusing with {0} is passed on under its own name as a 409")
    @CsvSource({"insufficient-stock", "hold-limit-reached", "live-order-has-another-address"})
    void ordersRefusalsTheShopperCanActOnArePassedOnUnderTheirOwnName(String code) {
        doThrow(feignProblem("OrderClient#place(String,PlaceOrderRequest)", 409,
                "https://oriah.dev/problems/" + code)).when(order).place(any(), any());

        assertThatThrownBy(() -> service.confirm(confirm("SELF_COLLECT", sawOneRing()), "k"))
                .isInstanceOfSatisfying(RefusalPassedOnException.class, refusal -> {
                    assertThat(refusal.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(refusal.code()).isEqualTo(code);
                });
        verify(cart, never()).clearMyCart();
    }

    @Test
    @DisplayName("an order total that disagrees with the agreed quote is refused, and the cart is not cleared")
    void anOrderTotalThatDisagreesIsRefused() {
        // The digests matched, so this should be impossible -- which is exactly
        // when going on anyway would be a mistake nobody notices.
        orderRecordsTotal("4600.00");

        assertThatThrownBy(() -> service.confirm(confirm("SELF_COLLECT", sawOneRing()), "k"))
                .isInstanceOf(DownstreamRejectedException.class);
        verify(cart, never()).clearMyCart();
    }

    @Test
    @DisplayName("confirm answers with the order placed and held -- id, status, total, currency, hold expiry -- and clears the cart after it")
    void confirmAnswersWithTheOrderAndClearsTheCart() {
        ConfirmResponse response = service.confirm(confirm("SELF_COLLECT", sawOneRing()), "k");

        assertThat(response.orderId()).isEqualTo(ORDER_ID);
        assertThat(response.orderStatus()).isEqualTo("CREATED");
        assertThat(response.total()).isEqualByComparingTo("4500.00");
        assertThat(response.currency()).isEqualTo("ZMW");
        assertThat(response.holdExpiresAt()).isEqualTo(HOLD_EXPIRES);
        assertThat(response.message()).isNotBlank();
        InOrder inOrder = inOrder(order, cart);
        inOrder.verify(order).place(any(), any());
        inOrder.verify(cart).clearMyCart();
    }

    @Test
    @DisplayName("a cart that cannot be cleared does not undo the order: the order is placed, the shopper owns it")
    void aCartThatCannotBeClearedDoesNotFailTheConfirm() {
        doThrow(feign(503)).when(cart).clearMyCart();

        ConfirmResponse response = service.confirm(confirm("SELF_COLLECT", sawOneRing()), "k");

        assertThat(response.orderId()).isEqualTo(ORDER_ID);
        verify(cart).clearMyCart();
    }

    private OrderClient.OrderResponse anOrderAs(String status) {
        return new OrderClient.OrderResponse(ORDER_ID, "kc-alice", status, REAL_PRICE, ZERO, ZERO, REAL_PRICE, "ZMW", null, null, HOLD_EXPIRES);
    }

    @ParameterizedTest(name = "a replayed key that gets back an order that is {0} is not placed and held: the cart is kept, and the shopper is told to confirm again")
    @CsvSource({"EXPIRED", "CANCELLED"})
    void aReplayedKeyThatReturnsAnEndedOrderIsNotPlacedAndHeld(String ended) {
        // Order answers a repeated key with the order it made, whatever has become of it since. An order whose hold lapsed (or that the shopper cancelled
        // in the meantime) is not "placed and held", and the cart must not be cleared for it.
        doReturn(anOrderAs(ended)).when(order).place(any(), any());

        assertThatThrownBy(() -> service.confirm(confirm("SELF_COLLECT", sawOneRing()), "k"))
                .isInstanceOfSatisfying(RefusalPassedOnException.class, refusal -> {
                    assertThat(refusal.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(refusal.code()).isEqualTo("order-no-longer-held");
                    assertThat(refusal.extras()).containsEntry("orderId", ORDER_ID).containsEntry("orderStatus", ended);
                });
        verify(cart, never()).clearMyCart();
    }

    @ParameterizedTest(name = "a replayed key that gets back an order that is {0} IS that order: answered as placed, and the cart is cleared")
    @CsvSource({"CREATED", "PAYMENT_SUBMITTED"})
    void aReplayedKeyThatReturnsALiveOrderIsTheSameOrder(String live) {
        doReturn(anOrderAs(live)).when(order).place(any(), any());

        ConfirmResponse response = service.confirm(confirm("SELF_COLLECT", sawOneRing()), "k");

        assertThat(response.orderId()).isEqualTo(ORDER_ID);
        assertThat(response.orderStatus()).isEqualTo(live);
        verify(cart).clearMyCart();
    }

    // --------------------------------------------- staff waived the delivery after the shopper's quote

    @Test
    @DisplayName("a replayed confirm after staff made the delivery free is placed: the order's total is the quote's less exactly the delivery it waived")
    void aWaivedDeliveryIsTheQuoteLessWhatWasWaived() {
        shippingQuotes(delivered("OWN_DELIVERY", "125.00"));
        String digest = sawDelivered("OWN_DELIVERY", ORIAH_COLLECTS, "125.00", line(VARIANT, 1, "4500.00"));
        // the shopper's quote was K4,625.00; staff waived the K125.00, so order's total is K4,500.00 and it says what it waived
        doReturn(new OrderClient.OrderResponse(ORDER_ID, "kc-alice", "CREATED", REAL_PRICE, ZERO, ZERO, REAL_PRICE, "ZMW", TOWN, ORIAH_COLLECTS, HOLD_EXPIRES,
                new BigDecimal("125.00"))).when(order).place(any(), any());

        ConfirmResponse response = service.confirm(confirmTo("OWN_DELIVERY", ORIAH_COLLECTS, digest), "k");

        assertThat(response.total()).isEqualByComparingTo("4500.00");
        verify(cart).clearMyCart();
    }

    @Test
    @DisplayName("a lower total that is NOT explained by the waiver is still refused: the check that the order is for what the shopper agreed to stays")
    void aLowerTotalTheWaiverDoesNotExplainIsRefused() {
        shippingQuotes(delivered("OWN_DELIVERY", "125.00"));
        String digest = sawDelivered("OWN_DELIVERY", ORIAH_COLLECTS, "125.00", line(VARIANT, 1, "4500.00"));
        // order says it waived K100.00, but its total is K125.00 lower than the quote: K25.00 is unexplained
        doReturn(new OrderClient.OrderResponse(ORDER_ID, "kc-alice", "CREATED", REAL_PRICE, ZERO, ZERO, REAL_PRICE, "ZMW", TOWN, ORIAH_COLLECTS, HOLD_EXPIRES,
                new BigDecimal("100.00"))).when(order).place(any(), any());

        assertThatThrownBy(() -> service.confirm(confirmTo("OWN_DELIVERY", ORIAH_COLLECTS, digest), "k"))
                .isInstanceOf(com.oriah.checkout.exception.DownstreamRejectedException.class);
        verify(cart, never()).clearMyCart();
    }

    // --------------------------------------------- the Idempotency-Key (h)

    @Test
    @DisplayName("the browser's Idempotency-Key is forwarded to order verbatim, so a retry places the same order")
    void theIdempotencyKeyIsForwardedVerbatim() {
        String key = "7d9c1c1e-3b52-4f0f-9a0e-0d5b6a1d2c11";

        service.confirm(confirm("SELF_COLLECT", sawOneRing()), key);

        verify(order).place(eq(key), any());
    }

    @Test
    @DisplayName("an absent Idempotency-Key stays absent: a key minted per attempt would guard nothing")
    void anAbsentIdempotencyKeyStaysAbsent() {
        service.confirm(confirm("SELF_COLLECT", sawOneRing()), null);

        verify(order).place(isNull(), any());
    }

    // ---------------------------------- the address is the shopper's to name

    @Test
    @DisplayName("shipping answering 422 is a loud 500, not passed on as something the shopper can fix")
    void aShipping422IsNotPassedOn() {
        doThrow(shippingRefuses(422, "validation", "refused")).when(shipping).quote(any());

        assertThatThrownBy(() -> service.confirm(confirm("SELF_COLLECT", sawOneRing()), "key-35"))
                .isInstanceOf(DownstreamRejectedException.class);
        nothingWasOrdered();
    }

    @Test
    @DisplayName("a 409 from shipping of a type checkout does not know is our 500, not passed on")
    void anUnknownShippingConflictIsNotPassedOn() {
        doThrow(shippingRefuses(409, "something-new", "refused")).when(shipping).quote(any());

        assertThatThrownBy(() -> service.confirm(confirm("SELF_COLLECT", sawOneRing()), "key-39"))
                .isInstanceOf(DownstreamRejectedException.class);
        nothingWasOrdered();
    }

    @Test
    @DisplayName("a 404 from shipping that is not town-not-found is our 500, not passed on")
    void anUnknownShippingNotFoundIsNotPassedOn() {
        doThrow(feign(404)).when(shipping).quote(any());

        assertThatThrownBy(() -> service.confirm(confirm("SELF_COLLECT", sawOneRing()), "key-40"))
                .isInstanceOf(DownstreamRejectedException.class);
        nothingWasOrdered();
    }

    @Test
    @DisplayName("an address that is not the shopper's is refused, and shipping is not even asked")
    void anAddressThatIsNotTheShoppersIsRefused() {
        doReturn(List.of()).when(customer).myAddresses();

        assertThatThrownBy(() -> service.confirm(confirm("SELF_COLLECT", sawOneRing()), "key-31"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("That shipping address is not one of yours.");
        verifyNoInteractions(shipping);
        nothingWasOrdered();
    }

    @Test
    @DisplayName("preview refuses an address that is not the shopper's exactly as confirm does")
    void previewRefusesAnAddressThatIsNotTheShoppersLikeConfirm() {
        doReturn(List.of()).when(customer).myAddresses();

        assertThatThrownBy(() -> previewFor("SELF_COLLECT"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("That shipping address is not one of yours.");
        verifyNoInteractions(shipping);
    }

    @Test
    @DisplayName("customer-service being unreachable fails closed before anything is ordered")
    void customerUnreachableFailsClosed() {
        doThrow(feign(503)).when(customer).myAddresses();

        assertThatThrownBy(() -> service.confirm(confirm("SELF_COLLECT", sawOneRing()), "key-32"))
                .isInstanceOf(DownstreamUnavailableException.class);
        nothingWasOrdered();
    }

    // ------------------------------------- what the review found, as tests

    @Test
    @DisplayName("a bag mixing currencies is refused with its OWN type, not as unpurchasable")
    void aMixedCurrencyBagIsRefusedWithItsOwnType() {
        cartHolds(item(VARIANT, "Ring", 1), item(SECOND, "Cuff", 1));
        doReturn(new CatalogPricingClient.PricingResponse(List.of(
                new CatalogPricingClient.Entry(VARIANT, "PRICED", UUID.randomUUID(), "Ring", "Ring", REAL_PRICE, "ZMW"),
                new CatalogPricingClient.Entry(SECOND, "PRICED", UUID.randomUUID(), "Cuff", "Cuff",
                        new BigDecimal("250.00"), "USD"))))
                .when(catalog).price(any());

        assertThatThrownBy(() -> service.confirm(confirm("SELF_COLLECT", sawOneRing()), "key-20"))
                .isInstanceOf(MixedCurrencyException.class);
        nothingWasOrdered();
    }

    @Test
    @DisplayName("shipping ANSWERING 400 is loud, not free shipping")
    void aShippingFourHundredIsLoudNotFreeShipping() {
        doThrow(feign(400)).when(shipping).quote(any());

        assertThatThrownBy(() -> service.confirm(confirm("SELF_COLLECT", sawOneRing()), "key-21"))
                .isInstanceOf(DownstreamRejectedException.class);
        nothingWasOrdered();
    }

    @Test
    @DisplayName("shipping being UNREACHABLE refuses to confirm: never a sale with delivery priced at zero")
    void aShippingOutageRefusesToConfirm() {
        doThrow(feign(503)).when(shipping).quote(any());

        assertThatThrownBy(() -> service.confirm(confirm("SELF_COLLECT", sawOneRing()), "key-22"))
                .isInstanceOf(DownstreamUnavailableException.class);
        nothingWasOrdered();
    }

    @Test
    @DisplayName("a shipping outage at PREVIEW gives no quote at all, so nothing unseen can be confirmed")
    void aPreviewDuringAShippingOutageIssuesNoQuote() {
        doThrow(feign(503)).when(shipping).quote(any());

        assertThatThrownBy(() -> previewFor("SELF_COLLECT")).isInstanceOf(DownstreamUnavailableException.class);
    }

    @Test
    @DisplayName("a stale digest is refused before anything is ordered")
    void aStaleDigestIsRefusedBeforeAnythingHappens() {
        assertThatThrownBy(() -> service.confirm(confirm("SELF_COLLECT", "0".repeat(64)), "key-12"))
                .isInstanceOf(PricingChangedException.class);
        nothingWasOrdered();
    }

    // ------------------------------------------------------- refuse, don't guess

    @Test
    @DisplayName("the bag's currency is sent to shipping, so it can refuse to quote in it")
    void theBagCurrencyIsSentToShipping() {
        service.confirm(confirm("SELF_COLLECT", sawOneRing()), "key-9");

        assertThat(asked().currency()).isEqualTo("ZMW");
    }

    /**
     * A delivery charge quoted in USD for a ZMW bag. Priced at 0.00 on purpose:
     * then the digest of the quote matches what the shopper "saw" whether or
     * not the currency is checked, so only the currency check can refuse it --
     * a 45.00 charge would be refused by the digest instead and hide a missing
     * check.
     */
    private void shippingQuotesInDollars() {
        shippingQuotes(quoted(null, "SELF_COLLECT", null, "0.00", "USD"));
    }

    @Test
    @DisplayName("a shipping quote in another currency is refused at confirm, not added to the total")
    void aShippingQuoteInAnotherCurrencyIsRefused() {
        shippingQuotesInDollars();

        assertThatThrownBy(() -> service.confirm(confirm("SELF_COLLECT", sawOneRing()), "key-10"))
                .isInstanceOf(DownstreamRejectedException.class);
        nothingWasOrdered();
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
        doThrow(feign(500)).when(shipping).quote(any());

        assertThatThrownBy(() -> service.confirm(confirm("SELF_COLLECT", sawOneRing()), "key-36"))
                .isInstanceOf(DownstreamRejectedException.class);
        nothingWasOrdered();
    }

    @Test
    @DisplayName("shipping failing with no answer at all is a 503 too, before anything is ordered")
    void anUnreachableShippingServiceIsServiceUnavailable() {
        doThrow(new IllegalStateException("shipping is down")).when(shipping).quote(any());

        assertThatThrownBy(() -> service.confirm(confirm("SELF_COLLECT", sawOneRing()), "key-11"))
                .isInstanceOf(DownstreamUnavailableException.class);
        nothingWasOrdered();
    }

    @Test
    @DisplayName("a line catalog will not price is never ordered")
    void anUnpublishedLineIsRefusedEntirely() {
        catalogSays("UNPUBLISHED", null, null);

        assertThatThrownBy(() -> service.confirm(confirm("SELF_COLLECT", sawOneRing()), "key-5"))
                .isInstanceOf(BagNotPurchasableException.class);
        nothingWasOrdered();
    }

    @Test
    @DisplayName("a bag catalog knows nothing about is refused")
    void anUnknownLineIsRefused() {
        catalogSays("UNKNOWN", null, null);

        assertThatThrownBy(() -> service.confirm(confirm("SELF_COLLECT", sawOneRing()), "key-6"))
                .isInstanceOf(BagNotPurchasableException.class);
        nothingWasOrdered();
    }

    @Test
    @DisplayName("an empty pricing answer is refused rather than read as free")
    void anEmptyPricingAnswerIsRefused() {
        doReturn(new CatalogPricingClient.PricingResponse(List.of())).when(catalog).price(any());

        assertThatThrownBy(() -> service.confirm(confirm("SELF_COLLECT", sawOneRing()), "key-7"))
                .isInstanceOf(BagNotPurchasableException.class);
        nothingWasOrdered();
    }

    @Test
    @DisplayName("an unreachable catalog fails before anything is ordered")
    void anUnreachableCatalogFailsClosed() {
        doThrow(new IllegalStateException("catalog is down")).when(catalog).price(any());

        assertThatThrownBy(() -> service.confirm(confirm("SELF_COLLECT", sawOneRing()), "key-8"))
                .as("an outage the shopper can retry (503), not an unnamed failure")
                .isInstanceOf(com.oriah.checkout.exception.DownstreamUnavailableException.class);
        nothingWasOrdered();
    }
}
