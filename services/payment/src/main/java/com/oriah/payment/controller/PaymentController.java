package com.oriah.payment.controller;

import java.net.URI;
import java.util.UUID;

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

import com.oriah.payment.dto.ConfirmIntentRequest;
import com.oriah.payment.dto.CreateIntentRequest;
import com.oriah.payment.dto.PaymentIntentResponse;
import com.oriah.payment.model.PaymentIntent;
import com.oriah.payment.service.PaymentService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/payments")
@PreAuthorize("hasRole('USER')")
public class PaymentController {

    private final PaymentService service;

    public PaymentController(PaymentService service) {
        this.service = service;
    }

    @PostMapping("/intents")
    public ResponseEntity<PaymentIntentResponse> createIntent(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateIntentRequest body) {
        PaymentIntent created = service.createIntent(jwt.getSubject(), body);
        return ResponseEntity.created(URI.create("/api/v1/payments/intents/" + created.id()))
                .body(PaymentIntentResponse.from(created));
    }

    @GetMapping("/intents/{id}")
    public ResponseEntity<PaymentIntentResponse> get(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("id") UUID id) {
        return service.findById(id)
                .filter(i -> i.keycloakId().equals(jwt.getSubject()))
                .map(PaymentIntentResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/intents/{id}/confirm")
    public ResponseEntity<PaymentIntentResponse> confirm(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("id") UUID id,
            @Valid @RequestBody ConfirmIntentRequest body) {
        return service.confirm(id, body.cardNumber())
                .filter(i -> i.keycloakId().equals(jwt.getSubject()))
                .map(PaymentIntentResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
