package com.oriah.promotion.controller;

import java.math.BigDecimal;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.oriah.promotion.dto.ApplyRequest;
import com.oriah.promotion.dto.ApplyResponse;
import com.oriah.promotion.dto.PromotionResponse;
import com.oriah.promotion.service.PromotionService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/promotions")
@PreAuthorize("hasRole('USER')")
public class PromotionController {

    private final PromotionService service;

    public PromotionController(PromotionService service) {
        this.service = service;
    }

    /** Look up a code and return its config (validation hint to UI). */
    @GetMapping("/{code}")
    public ResponseEntity<PromotionResponse> get(@PathVariable("code") String code) {
        return service.findByCode(code)
                .map(PromotionResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Validate + compute discount against a subtotal. Returns 404 when
     * the code is unknown/expired/disabled/exhausted — single 404 path
     * avoids leaking which guard failed (UI just shows "code invalid").
     */
    @PostMapping("/{code}/apply")
    public ResponseEntity<ApplyResponse> apply(
            @PathVariable("code") String code,
            @Valid @RequestBody ApplyRequest body) {
        return service.applyToSubtotal(code, body.subtotal())
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Bump currentRedemptions by 1. Called by checkout after the order
     * is successfully placed. Idempotency is the caller's responsibility
     * — calling this twice double-counts. (Outbox/saga pattern handles
     * idempotency in a real production setup.)
     */
    @PostMapping("/{code}/redeem")
    public ResponseEntity<PromotionResponse> redeem(@PathVariable("code") String code) {
        return service.redeem(code)
                .map(PromotionResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
