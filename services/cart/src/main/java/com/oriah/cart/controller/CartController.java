package com.oriah.cart.controller;

import java.util.UUID;

import org.springframework.http.ProblemDetail;
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

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/carts/me")
@Tag(name = "Cart", description = "The caller's own cart")
@ApiResponses({
        @ApiResponse(responseCode = "401", description = "No or invalid bearer token",
                content = @Content(mediaType = "application/problem+json",
                        schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "403", description = "Authenticated, but lacking the required role",
                content = @Content(mediaType = "application/problem+json",
                        schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "500", description = "Unexpected server error",
                content = @Content(mediaType = "application/problem+json",
                        schema = @Schema(implementation = ProblemDetail.class)))
})
@PreAuthorize("hasRole('USER')")
public class CartController {

    private final CartService service;

    public CartController(CartService service) {
        this.service = service;
    }

    @Operation(operationId = "get", summary = "The caller's cart, empty if they have none")
    @ApiResponse(responseCode = "200", description = "The cart; an empty placeholder if none exists yet")
    @GetMapping
    public ResponseEntity<CartResponse> get(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(CartResponse.from(service.getOrEmpty(jwt.getSubject())));
    }

    @Operation(operationId = "addItem", summary = "Add a variant, or increment it if already present")
    @ApiResponse(responseCode = "200", description = "The updated cart")
    @ApiResponse(responseCode = "400", description = "Validation failure or unparseable parameter; see fieldErrors",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping("/items")
    public ResponseEntity<CartResponse> addItem(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody AddItemRequest body) {
        return ResponseEntity.ok(CartResponse.from(service.addItem(jwt.getSubject(), body)));
    }

    @Operation(operationId = "updateQuantity", summary = "Set a line item's quantity")
    @ApiResponse(responseCode = "200", description = "The updated cart")
    @ApiResponse(responseCode = "400", description = "Validation failure or unparseable parameter; see fieldErrors",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No such resource",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
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
    @Operation(operationId = "removeItem", summary = "Remove one line item")
    @ApiResponse(responseCode = "204", description = "Removed; no body")
    @ApiResponse(responseCode = "404", description = "No such resource",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @DeleteMapping("/items/{variantId}")
    public ResponseEntity<Void> removeItem(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("variantId") UUID variantId) {
        service.removeItem(jwt.getSubject(), variantId);
        return ResponseEntity.noContent().build();
    }

    @Operation(operationId = "clear", summary = "Empty the cart")
    @ApiResponse(responseCode = "204", description = "Cleared; no body")
    @ApiResponse(responseCode = "404", description = "No such resource",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @DeleteMapping
    public ResponseEntity<Void> clear(@AuthenticationPrincipal Jwt jwt) {
        service.clear(jwt.getSubject());
        return ResponseEntity.noContent().build();
    }
}
