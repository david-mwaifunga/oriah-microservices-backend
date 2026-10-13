package com.oriah.cart.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.oriah.cart.client.CatalogPricingClient;
import com.oriah.cart.dto.AddItemRequest;
import com.oriah.cart.model.Cart;
import com.oriah.cart.model.CartItem;
import com.oriah.cart.repository.CartRepository;

import feign.FeignException;
import feign.Request;
import feign.RequestTemplate;
import feign.Response;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Where the one-cent purchase actually dies.
 *
 * <h2>The defect, stated exactly</h2>
 *
 * {@code CartService.addItem} read
 * {@code cart.addOrIncrement(req.variantId(), req.quantity(), req.unitPrice(), req.title())}
 * -- the shopper's own price, stored verbatim. {@code POST /api/v1/carts/me/items}
 * with {@code "unitPrice": 0.01} produced a bag with a 0.01 subtotal, and
 * checkout charged it, which was demonstrated live against the running stack
 * before this phase began. The aggregate is the only write door for a price, so
 * this service method is the entire pricing policy of cart.
 *
 * <h2>Why these assertions and not the HTTP-level one</h2>
 *
 * The obvious test -- POST a body still carrying {@code unitPrice: 0.01} and
 * assert the subtotal is catalog's price -- is a FALSE GREEN, and it is worth
 * being precise about why. Cart sets no {@code spring.jackson} block, so Boot's
 * {@code FAIL_ON_UNKNOWN_PROPERTIES=false} applies: the moment that field
 * leaves {@code AddItemRequest}, the stale value is silently ignored and the
 * test passes whether or not any catalog lookup happens. That test is still
 * worth having as an end-to-end guard, but these are the ones that prove the
 * lookup works, and they must come first.
 *
 * <h2>Fail closed, and fail before writing anything</h2>
 *
 * A variant catalog will not price is not a free variant: UNPUBLISHED means the
 * product is DRAFT or ARCHIVED or the variant is inactive, UNKNOWN means it
 * does not exist. Both refuse the add. An unreachable catalog is a 503 rather
 * than a refusal, because the claim was never checked -- the same distinction
 * order-service draws between "checked and false" and "could not check".
 *
 * <p>{@link #pricingHappensBeforeTheRepositoryIsTouched} pins the ORDER, which
 * is the observable half of "the HTTP call is outside the transaction". The
 * correctness half was already free: {@code addItem} was {@code @Transactional},
 * so a throw rolled back the cart row it had just inserted. What was NOT free
 * is the cost -- a pooled database connection held open for the whole
 * downstream timeout, which fails as a capacity collapse under load rather than
 * as a test failure.
 */
class AddItemPricingTest {

    private static final String ALICE = "kc-alice";
    private static final UUID VARIANT = UUID.randomUUID();

    /**
     * What catalog says. There is deliberately no counterpart constant for
     * "what the shopper claims": AddItemRequest no longer has a price field to
     * put one in, which is the structural half of this fix. These tests prove
     * the other half -- that the stored line takes its price from the entry
     * rather than from anywhere else.
     */
    private static final BigDecimal CATALOG_PRICE = new BigDecimal("4500.00");

    private CatalogPricingClient catalog;
    private CartRepository repository;
    private CartService service;

    @BeforeEach
    void setUp() {
        catalog = mock(CatalogPricingClient.class);
        repository = mock(CartRepository.class);
        service = new CartService(repository, catalog);
    }

    private static AddItemRequest request(int quantity) {
        return new AddItemRequest(VARIANT, quantity);
    }

    private void catalogAnswers(CatalogPricingClient.Entry entry) {
        when(catalog.price(any())).thenReturn(
                new CatalogPricingClient.PricingResponse(List.of(entry)));
    }

    private static CatalogPricingClient.Entry priced() {
        return new CatalogPricingClient.Entry(VARIANT, "PRICED", UUID.randomUUID(),
                "Aurelia 18k", "Aurelia 18k - Ring Size M", CATALOG_PRICE, "ZMW");
    }

    private static CatalogPricingClient.Entry withStatus(String status) {
        return new CatalogPricingClient.Entry(VARIANT, status, null, null, null, null, null);
    }

    private static FeignException unreachable() {
        Request request = Request.create(Request.HttpMethod.POST,
                "http://catalog/api/v1/variants/pricing", java.util.Map.of(),
                new byte[0], java.nio.charset.StandardCharsets.UTF_8, new RequestTemplate());
        return FeignException.errorStatus("CatalogPricingClient#price(PricingRequest)",
                Response.builder().status(503).reason("generated")
                        .request(request).headers(java.util.Map.of()).build());
    }

    // ------------------------------------------------------------- the fix

    @Test
    @DisplayName("the stored line is priced by catalog, not by the request")
    void theStoredPriceIsCatalogsNotTheRequests() {
        catalogAnswers(priced());
        when(repository.findByKeycloakId(ALICE)).thenReturn(Optional.of(new Cart(ALICE)));

        CatalogPricingClient.Entry entry = service.resolvePricing(VARIANT);
        Cart cart = service.addPricedItem(ALICE, request(2), entry);

        CartItem line = cart.items().getFirst();
        assertThat(line.unitPrice())
                .as("catalog said 4500.00, and nothing in the request can say otherwise")
                .isEqualByComparingTo(CATALOG_PRICE);
        assertThat(line.currency()).isEqualTo("ZMW");
        assertThat(line.title())
                .as("catalog's composed variantTitle, not the client's string")
                .isEqualTo("Aurelia 18k - Ring Size M");
        assertThat(cart.subtotal()).isEqualByComparingTo("9000.00");
    }

    @Test
    @DisplayName("the pricing call happens before the repository is touched at all")
    void pricingHappensBeforeTheRepositoryIsTouched() {
        catalogAnswers(priced());
        when(repository.findByKeycloakId(ALICE)).thenReturn(Optional.of(new Cart(ALICE)));

        CatalogPricingClient.Entry entry = service.resolvePricing(VARIANT);
        service.addPricedItem(ALICE, request(1), entry);

        InOrder order = inOrder(catalog, repository);
        order.verify(catalog).price(any());
        order.verify(repository).findByKeycloakId(ALICE);
    }

    // ------------------------------------------------------ refusals (409)

    @Test
    @DisplayName("an UNPUBLISHED variant is refused and nothing is written")
    void anUnpublishedVariantIsRefused() {
        catalogAnswers(withStatus("UNPUBLISHED"));

        assertThatThrownBy(() -> service.resolvePricing(VARIANT))
                .isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("an UNKNOWN variant is refused and nothing is written")
    void anUnknownVariantIsRefused() {
        catalogAnswers(withStatus("UNKNOWN"));

        assertThatThrownBy(() -> service.resolvePricing(VARIANT))
                .isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("a status this service has never heard of is refused, not trusted")
    void anUnrecognisedStatusIsRefused() {
        // Bound as a String precisely so a new catalog status cannot throw on
        // deserialisation. It must then fail CLOSED rather than being treated
        // as purchasable.
        catalogAnswers(withStatus("SOME_FUTURE_STATUS"));

        assertThatThrownBy(() -> service.resolvePricing(VARIANT))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("a PRICED entry carrying no usable price is refused")
    void aPricedEntryWithoutAPriceIsRefused() {
        // Zero is catalog's "nobody has priced this yet" marker, and a null
        // price on a PRICED entry would be a catalog bug. Storing either as a
        // line sells the item for nothing.
        catalogAnswers(new CatalogPricingClient.Entry(VARIANT, "PRICED", UUID.randomUUID(),
                "Draft ring", "Draft ring", BigDecimal.ZERO, "ZMW"));

        assertThatThrownBy(() -> service.resolvePricing(VARIANT))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("an empty results list is refused rather than read as 'free'")
    void anEmptyResultSetIsRefused() {
        when(catalog.price(any())).thenReturn(
                new CatalogPricingClient.PricingResponse(List.of()));

        assertThatThrownBy(() -> service.resolvePricing(VARIANT))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("every refusal reads the same, so it is not an oracle over unpublished products")
    void refusalsAreIndistinguishable() {
        catalogAnswers(withStatus("UNPUBLISHED"));
        String unpublished = messageOfRefusal();

        catalogAnswers(withStatus("UNKNOWN"));
        String unknown = messageOfRefusal();

        // "that variant is unpublished" versus "no such variant" would tell an
        // anonymous caller which ids exist among products that are not on the
        // storefront. Catalog's own pricing endpoint distinguishes them, but
        // cart need not re-expose that distinction to reach the same decision.
        assertThat(unpublished).isEqualTo(unknown);
    }

    private String messageOfRefusal() {
        try {
            service.resolvePricing(VARIANT);
            throw new AssertionError("expected a refusal");
        } catch (IllegalStateException e) {
            return e.getMessage();
        }
    }

    // ------------------------------------------------- unreachable (503)

    @Test
    @DisplayName("an unreachable catalog is a 503 and nothing is written")
    void anUnreachableCatalogIsServiceUnavailable() {
        when(catalog.price(any())).thenThrow(unreachable());

        assertThatThrownBy(() -> service.resolvePricing(VARIANT))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .as("the price was never established, so this is not a refusal")
                        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));

        verify(repository, never()).save(any());
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("any other client failure is also a 503, never a silent success")
    void anyOtherClientFailureIsAlsoServiceUnavailable() {
        // Discovery failure, no instance available, a decode error: all of them
        // mean the price is unknown, and none of them may add a line.
        when(catalog.price(any())).thenThrow(new IllegalArgumentException("no instances"));

        assertThatThrownBy(() -> service.resolvePricing(VARIANT))
                .isInstanceOf(ResponseStatusException.class);

        verifyNoInteractions(repository);
    }
}
