package com.oriah.cart.service;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.oriah.cart.dto.AddItemRequest;
import com.oriah.cart.model.Cart;
import com.oriah.cart.model.CartItem;
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

    @Transactional
    public Optional<Cart> updateItemQuantity(String keycloakId, UUID variantId, int qty) {
        return repository.findByKeycloakId(keycloakId).map(cart -> {
            Optional<CartItem> updated = cart.updateQuantity(variantId, qty);
            if (updated.isPresent()) {
                log.info("Cart update cartId={} variantId={} newQty={}", cart.id(), variantId, qty);
            }
            return cart;
        });
    }

    @Transactional
    public boolean removeItem(String keycloakId, UUID variantId) {
        Optional<Cart> cart = repository.findByKeycloakId(keycloakId);
        if (cart.isEmpty()) return false;
        boolean removed = cart.get().removeVariant(variantId);
        if (removed) log.info("Cart remove cartId={} variantId={}", cart.get().id(), variantId);
        return removed;
    }

    @Transactional
    public boolean clear(String keycloakId) {
        Optional<Cart> cart = repository.findByKeycloakId(keycloakId);
        if (cart.isEmpty()) return false;
        cart.get().clear();
        log.info("Cart cleared cartId={}", cart.get().id());
        return true;
    }
}
