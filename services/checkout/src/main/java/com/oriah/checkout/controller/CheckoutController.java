package com.oriah.checkout.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.oriah.checkout.dto.ConfirmRequest;
import com.oriah.checkout.dto.ConfirmResponse;
import com.oriah.checkout.dto.PreviewRequest;
import com.oriah.checkout.dto.PreviewResponse;
import com.oriah.checkout.service.CheckoutService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/checkout")
@PreAuthorize("hasRole('USER')")
public class CheckoutController {

    private final CheckoutService service;

    public CheckoutController(CheckoutService service) {
        this.service = service;
    }

    @PostMapping("/preview")
    public ResponseEntity<PreviewResponse> preview(@Valid @RequestBody PreviewRequest body) {
        return ResponseEntity.ok(service.preview(body));
    }

    @PostMapping("/confirm")
    public ResponseEntity<ConfirmResponse> confirm(@Valid @RequestBody ConfirmRequest body) {
        return ResponseEntity.ok(service.confirm(body));
    }
}
