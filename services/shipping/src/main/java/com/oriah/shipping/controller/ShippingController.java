package com.oriah.shipping.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.oriah.shipping.dto.RateRequest;
import com.oriah.shipping.dto.RateResponse;
import com.oriah.shipping.dto.ShippingContactInfoDto;
import com.oriah.shipping.service.ShippingService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/shipping")
@PreAuthorize("hasRole('USER')")
public class ShippingController {

    private final ShippingService service;
    private final ShippingContactInfoDto contactInfo;

    public ShippingController(ShippingService service, ShippingContactInfoDto contactInfo) {
        this.service = service;
        this.contactInfo = contactInfo;
    }

    @PostMapping("/rates")
    public ResponseEntity<RateResponse> rates(@Valid @RequestBody RateRequest body) {
        return ResponseEntity.ok(service.quote(body.destinationCountry(), body.subtotal()));
    }

    /**
     * Who owns this service and how to reach them.
     *
     * <p>The course's {@code /contact-info} convention. Returns the
     * {@code @ConfigurationProperties} bean directly, so editing
     * {@code config-repo/shipping.yaml} and calling
     * {@code POST /actuator/refresh} changes the response without a redeploy --
     * which only works because the DTO is mutable and gets rebound in place.
     *
     * <p>Authenticated, unlike the course's open version: this class is
     * annotated {@code @PreAuthorize("hasRole('USER')")}, so a new handler
     * inherits it. Left that way deliberately -- the alternative is a
     * method-level override here plus a matching exception in the gateway's
     * Tier 3-verified rules, to expose a name, an email and two phone numbers.
     */
    @GetMapping("/contact-info")
    public ShippingContactInfoDto contactInfo() {
        return contactInfo;
    }
}
