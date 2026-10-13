package com.oriah.cart.service;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.oriah.cart.client.CatalogPricingClient;
import com.oriah.cart.dto.AddItemRequest;
import com.oriah.cart.model.Cart;
import com.oriah.cart.model.CartItem;
import com.oriah.observability.ResourceNotFoundException;
import com.oriah.cart.repository.CartRepository;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class CartService {

    /**
     * One message for every reason a variant cannot be added.
     *
     * <p>"That variant is unpublished" and "no such variant" are different
     * facts, and telling them apart would let an anonymous caller learn which
     * ids exist among products that are not on the storefront. Catalog's own
     * pricing endpoint does distinguish them -- it has to, it is the authority
     * -- but cart reaches the same decision without re-exposing the
     * distinction. The real status goes to the log.
     */
    static final String NOT_PURCHASABLE =
            "That item is not available for purchase right now.";

    private final CartRepository repository;
    private final CatalogPricingClient catalog;

    public CartService(CartRepository repository, CatalogPricingClient catalog) {
        this.repository = repository;
        this.catalog = catalog;
    }

    /**
     * Asks catalog what a variant costs. No transaction, deliberately.
     *
     * <h2>Why this is a separate call from the write</h2>
     *
     * It makes an outbound HTTP request, and holding a pooled database
     * connection open across a call to another service is how a slow dependency
     * becomes an exhausted connection pool. Correctness was never the issue:
     * {@code addItem} was already {@code @Transactional}, so a throw rolled
     * back the cart row it had just inserted -- the cost was the pinned
     * connection, which shows up as a capacity collapse under load rather than
     * as a failing test.
     *
     * <p>Splitting it also gives the controller the pricing entry it needs for
     * the response, where per-line availability is a live catalog fact that
     * cannot be derived from the stored entity.
     *
     * @throws IllegalStateException    catalog answered, and the answer is no:
     *                                  the variant is unpublished, unknown, or
     *                                  carries no usable price. 409 via the
     *                                  shared advice, with no new handler in
     *                                  cart.
     * @throws ResponseStatusException  503. Catalog could not be reached, so
     *                                  the price was never established. A
     *                                  refusal would tell the shopper their
     *                                  item is unavailable during an outage;
     *                                  either way nothing is added.
     */
    public CatalogPricingClient.Entry resolvePricing(UUID variantId) {
        CatalogPricingClient.PricingResponse response;
        try {
            response = catalog.price(CatalogPricingClient.PricingRequest.of(variantId));
        } catch (RuntimeException e) {
            // Deliberately broad: a FeignException, a discovery failure, "no
            // instances available" and a decode error all mean the same thing
            // here -- the price is unknown, so nothing may be added at any
            // price.
            log.error("Could not price variantId={}; refusing to guess", variantId, e);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Prices are temporarily unavailable. Nothing has been added to your bag; "
                            + "please try again.", e);
        }

        CatalogPricingClient.Entry entry = response == null || response.results() == null
                ? null
                : response.results().stream()
                        .filter(e -> variantId.equals(e.variantId()))
                        .findFirst()
                        .orElse(null);

        // An absent entry is treated exactly like an unpurchasable one. Reading
        // "catalog returned nothing for this id" as anything other than a
        // refusal is how a missing answer becomes a free item.
        if (entry == null || !entry.isPriced()) {
            log.warn("Refusing variantId={}: catalog says status={} price={} {}",
                    variantId,
                    entry == null ? "(absent from response)" : entry.status(),
                    entry == null ? null : entry.unitPrice(),
                    entry == null ? null : entry.currency());
            throw new IllegalStateException(NOT_PURCHASABLE);
        }
        return entry;
    }

    @Transactional(readOnly = true)
    public Cart getOrEmpty(String keycloakId) {
        log.debug("getOrEmpty sub={}", keycloakId);
        return repository.findByKeycloakId(keycloakId)
                .orElseGet(() -> new Cart(keycloakId)); // unsaved placeholder; safe for read-only view
    }

    /**
     * Writes a line at the price {@link #resolvePricing} established.
     *
     * <p>Only {@code variantId} and {@code quantity} are read from the request.
     * The price, the label and the currency come from the pricing entry, which
     * is the whole change: this method used to pass
     * {@code req.unitPrice()} and {@code req.title()} straight into the
     * aggregate, so a request naming 0.01 produced a 0.01 line and checkout
     * charged it.
     */
    @Transactional
    public Cart addPricedItem(String keycloakId, AddItemRequest req,
                              CatalogPricingClient.Entry entry) {
        Cart cart = repository.findByKeycloakId(keycloakId)
                .orElseGet(() -> repository.save(new Cart(keycloakId)));
        CartItem item = cart.addOrIncrement(req.variantId(), req.quantity(),
                entry.unitPrice(), entry.variantTitle(), entry.currency());
        log.info("Cart add cartId={} variantId={} qty={} at {} {} title=\"{}\"",
                cart.id(), req.variantId(), item.quantity(),
                item.unitPrice(), item.currency(), item.title());
        return cart;
    }

    /**
     * Sets a line item's quantity.
     *
     * <h2>This method had a real bug, and it is worth knowing what it was</h2>
     *
     * <p>It used to read:
     *
     * <pre>{@code
     * return repository.findByKeycloakId(keycloakId).map(cart -> {
     *     Optional<CartItem> updated = cart.updateQuantity(variantId, qty);
     *     if (updated.isPresent()) { log.info(...); }
     *     return cart;                                  // regardless
     * });
     * }</pre>
     *
     * <p>The empty {@code Optional} from {@link Cart#updateQuantity} was used
     * <em>only</em> to gate a log line, and the cart was then returned anyway.
     * So a PATCH naming a variant that is not in the cart answered
     * <strong>200 with the unchanged cart</strong> — the caller was told the
     * update succeeded when nothing had changed.
     *
     * <p>It stayed hidden because the storefront re-renders from the returned
     * cart: editing a quantity for a line another tab had just removed made the
     * number silently snap back, with no error anywhere. No test covered the
     * path.
     *
     * <p>Now throws, which also distinguishes the two conditions a single
     * {@code Optional} could not: <em>you have no cart</em> and <em>that item
     * is not in your cart</em> are different problems and a client needs to
     * know which.
     */
    @Transactional
    public Cart updateItemQuantity(String keycloakId, UUID variantId, int qty) {
        Cart cart = repository.findByKeycloakId(keycloakId)
                .orElseThrow(CartService::noCart);

        cart.updateQuantity(variantId, qty)
                .orElseThrow(() -> noSuchItem(variantId));

        log.info("Cart update cartId={} variantId={} newQty={}", cart.id(), variantId, qty);
        return cart;
    }

    @Transactional
    public void removeItem(String keycloakId, UUID variantId) {
        Cart cart = repository.findByKeycloakId(keycloakId)
                .orElseThrow(CartService::noCart);

        if (!cart.removeVariant(variantId)) {
            throw noSuchItem(variantId);
        }
        log.info("Cart remove cartId={} variantId={}", cart.id(), variantId);
    }

    @Transactional
    public void clear(String keycloakId) {
        Cart cart = repository.findByKeycloakId(keycloakId)
                .orElseThrow(CartService::noCart);

        cart.clear();
        log.info("Cart cleared cartId={}", cart.id());
    }

    /**
     * The caller has no cart.
     *
     * <p>Reports the field value as the literal {@code "me"} rather than the
     * Keycloak subject. The endpoint is {@code /api/v1/carts/me}, so "me" is
     * what the caller actually asked for — and echoing a subject claim into a
     * response body puts an identifier in logs, bug reports and screenshots for
     * no benefit, since the caller cannot address anyone else's cart anyway.
     */
    private static ResourceNotFoundException noCart() {
        return new ResourceNotFoundException("Cart", "customer", "me");
    }

    private static ResourceNotFoundException noSuchItem(UUID variantId) {
        return new ResourceNotFoundException("CartItem", "variantId", variantId.toString());
    }
}
