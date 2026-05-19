package com.oriah.order.controller;

import java.net.URI;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.oriah.order.dto.AttachPaymentRequest;
import com.oriah.order.dto.OrderResponse;
import com.oriah.order.dto.PlaceOrderRequest;
import com.oriah.order.model.Order;
import com.oriah.order.model.OrderStatus;
import com.oriah.order.service.OrderService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/orders")
@PreAuthorize("hasRole('USER')")
public class OrderController {

    private final OrderService service;

    public OrderController(OrderService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<OrderResponse> place(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody PlaceOrderRequest body) {
        Order o = service.placeOrder(jwt.getSubject(), body);
        return ResponseEntity.created(URI.create("/api/v1/orders/" + o.id()))
                .body(OrderResponse.from(o));
    }

    @GetMapping("/me")
    public ResponseEntity<Page<OrderResponse>> mine(
            @AuthenticationPrincipal Jwt jwt,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(service.findMine(jwt.getSubject(), pageable).map(OrderResponse::from));
    }

    @GetMapping("/{id}")
    public ResponseEntity<OrderResponse> get(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("id") UUID id) {
        return service.findById(id)
                .filter(o -> o.keycloakId().equals(jwt.getSubject()))
                .map(OrderResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Customer can attach a successful PaymentIntent to their own order
     * and transition to PAID in one call. Caller responsibility: ensure
     * the PaymentIntent is actually SUCCEEDED before calling this.
     * (Future checkout orchestrator validates that.)
     */
    @PostMapping("/{id}/pay")
    public ResponseEntity<OrderResponse> pay(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("id") UUID id,
            @Valid @RequestBody AttachPaymentRequest body) {
        return service.attachPayment(jwt.getSubject(), id, body.paymentIntentId())
                .flatMap(o -> service.transition(o.id(), OrderStatus.PAID))
                .map(OrderResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<OrderResponse> cancel(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("id") UUID id) {
        return service.findById(id)
                .filter(o -> o.keycloakId().equals(jwt.getSubject()))
                .flatMap(o -> service.transition(o.id(), OrderStatus.CANCELLED))
                .map(OrderResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
