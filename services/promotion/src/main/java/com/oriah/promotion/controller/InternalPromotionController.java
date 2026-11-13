package com.oriah.promotion.controller;

import java.time.Clock;
import java.time.Instant;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.oriah.promotion.dto.InForceResponse;
import com.oriah.promotion.service.PromotionService;

import io.swagger.v3.oas.annotations.Hidden;

/**
 * What catalog reads to price a piece: the promotions in force at an instant (now when none is given), as definitions. Catalog prices
 * anonymously for the storefront, so this read needs no token; it changes nothing, it is not routed by the gateway (so it is not on the
 * public edge) and it is not in the public API documents. Which products a category promotion covers is catalog's to work out: it owns the
 * category tree.
 */
@RestController
@RequestMapping("/internal/promotions")
@Hidden
public class InternalPromotionController {

    private final PromotionService promotions;
    private final Clock clock;

    public InternalPromotionController(PromotionService promotions, Clock clock) {
        this.promotions = promotions;
        this.clock = clock;
    }

    @GetMapping("/in-force")
    public InForceResponse inForce(@RequestParam(name = "at", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant at) {
        Instant when = at == null ? clock.instant() : at;
        return new InForceResponse(when, promotions.inForceAt(when));
    }
}
