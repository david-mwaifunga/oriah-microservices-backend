package com.oriah.promotion.controller;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.oriah.promotion.dto.CreatePromotionRequest;
import com.oriah.promotion.dto.PromotionResponse;
import com.oriah.promotion.model.Promotion;
import com.oriah.promotion.service.PromotionService;

import jakarta.validation.Valid;

/**
 * Admin-side promotion management. Reuses CATALOG_ADMIN role for the
 * marketing/catalog team (we don't have a dedicated PROMOTION_ADMIN
 * role — too granular for the demo scope).
 */
@RestController
@RequestMapping("/api/v1/admin/promotions")
@PreAuthorize("hasRole('CATALOG_ADMIN')")
public class AdminPromotionController {

    private final PromotionService service;

    public AdminPromotionController(PromotionService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<PromotionResponse> create(@Valid @RequestBody CreatePromotionRequest body) {
        Promotion p = service.create(body);
        return ResponseEntity.created(java.net.URI.create("/api/v1/admin/promotions/" + p.id()))
                .body(PromotionResponse.from(p));
    }

    @GetMapping
    public ResponseEntity<Page<PromotionResponse>> list(@PageableDefault(size = 50) Pageable pageable) {
        return ResponseEntity.ok(service.findAll(pageable).map(PromotionResponse::from));
    }

    @PostMapping("/{code}/disable")
    public ResponseEntity<PromotionResponse> disable(@PathVariable("code") String code) {
        return service.disable(code)
                .map(PromotionResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
