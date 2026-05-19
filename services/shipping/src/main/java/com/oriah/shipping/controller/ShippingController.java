package com.oriah.shipping.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.oriah.shipping.dto.RateRequest;
import com.oriah.shipping.dto.RateResponse;
import com.oriah.shipping.service.ShippingService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/shipping")
@PreAuthorize("hasRole('USER')")
public class ShippingController {

    private final ShippingService service;

    public ShippingController(ShippingService service) {
        this.service = service;
    }

    @PostMapping("/rates")
    public ResponseEntity<RateResponse> rates(@Valid @RequestBody RateRequest body) {
        return ResponseEntity.ok(service.quote(body.destinationCountry(), body.subtotal()));
    }
}
