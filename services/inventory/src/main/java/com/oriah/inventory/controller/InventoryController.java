package com.oriah.inventory.controller;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.oriah.inventory.dto.InventoryResponse;
import com.oriah.inventory.service.InventoryService;

import lombok.extern.slf4j.Slf4j;

/**
 * Public read endpoints for inventory. No auth required — the cart and
 * PDP screens hit this when rendering "in stock" / "out of stock" /
 * "few left" badges.
 */
@RestController
@RequestMapping("/api/v1/inventory")
@Slf4j
public class InventoryController {

    private final InventoryService service;

    public InventoryController(InventoryService service) {
        this.service = service;
    }

    @GetMapping("/{variantId}")
    public ResponseEntity<InventoryResponse> getByVariantId(
            @PathVariable("variantId") UUID variantId) {
        log.debug("GET /api/v1/inventory/{}", variantId);
        return service.findByVariantId(variantId)
                .map(InventoryResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
