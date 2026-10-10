package com.oriah.cart.service;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.oriah.cart.dto.AddItemRequest;
import com.oriah.cart.model.Cart;
import com.oriah.cart.model.CartItem;
import com.oriah.observability.ResourceNotFoundException;
import com.oriah.cart.repository.CartRepository;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class CartService {

    private final CartRepository repository;

    public CartService(CartRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public Cart getOrEmpty(String keycloakId) {
        log.debug("getOrEmpty sub={}", keycloakId);
        return repository.findByKeycloakId(keycloakId)
                .orElseGet(() -> new Cart(keycloakId)); // unsaved placeholder; safe for read-only view
    }

    @Transactional
    public Cart addItem(String keycloakId, AddItemRequest req) {
        Cart cart = repository.findByKeycloakId(keycloakId)
                .orElseGet(() -> repository.save(new Cart(keycloakId)));
        CartItem item = cart.addOrIncrement(req.variantId(), req.quantity(), req.unitPrice(), req.title());
        log.info("Cart add cartId={} variantId={} qty={} title=\"{}\"",
                cart.id(), req.variantId(), item.quantity(), item.title());
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
