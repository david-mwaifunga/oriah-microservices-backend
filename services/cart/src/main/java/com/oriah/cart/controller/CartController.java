package com.oriah.cart.controller;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.oriah.cart.dto.AddItemRequest;
import com.oriah.cart.dto.CartResponse;
import com.oriah.cart.dto.UpdateItemRequest;
import com.oriah.cart.service.CartService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/carts/me")
@PreAuthorize("hasRole('USER')")
public class CartController {

    private final CartService service;

    public CartController(CartService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<CartResponse> get(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(CartResponse.from(service.getOrEmpty(jwt.getSubject())));
    }

    @PostMapping("/items")
    public ResponseEntity<CartResponse> addItem(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody AddItemRequest body) {
        return ResponseEntity.ok(CartResponse.from(service.addItem(jwt.getSubject(), body)));
    }

    @PatchMapping("/items/{variantId}")
    public CartResponse updateQuantity(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("variantId") UUID variantId,
            @Valid @RequestBody UpdateItemRequest body) {
        return CartResponse.from(
                service.updateItemQuantity(jwt.getSubject(), variantId, body.quantity()));
    }

    /**
     * 204 on success, 404 with a problem body when the item is not there.
     *
     * <p>{@code ResponseEntity} is kept on this handler and on {@link #clear}
     * because 204 is not what Spring returns for a {@code void} handler -- it
     * would answer 200 with an empty body, which is a different contract.
     */
    @DeleteMapping("/items/{variantId}")
    public ResponseEntity<Void> removeItem(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("variantId") UUID variantId) {
        service.removeItem(jwt.getSubject(), variantId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping
    public ResponseEntity<Void> clear(@AuthenticationPrincipal Jwt jwt) {
        service.clear(jwt.getSubject());
        return ResponseEntity.noContent().build();
    }
}
