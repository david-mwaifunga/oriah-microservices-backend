package com.oriah.customer.controller;

import java.net.URI;
import java.util.UUID;

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

import com.oriah.customer.dto.AddressRequest;
import com.oriah.customer.dto.AddressResponse;
import com.oriah.customer.dto.CustomerResponse;
import com.oriah.customer.dto.CustomerUpdateRequest;
import com.oriah.customer.model.Customer;
import com.oriah.customer.service.CustomerService;

import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;

/**
 * Customer self-service ("me") endpoints. Every method extracts the
 * Keycloak {@code sub} from the JWT and acts on that customer only.
 * Requires {@code ROLE_USER}.
 */
@RestController
@RequestMapping("/api/v1/customers/me")
@PreAuthorize("hasRole('USER')")
@Slf4j
public class CustomerController {

    private final CustomerService service;

    public CustomerController(CustomerService service) {
        this.service = service;
    }

    /**
     * Self-provisioning: returns the existing customer row for the
     * caller's Keycloak sub, or creates one bootstrapped from the JWT's
     * email/given_name/family_name claims. Idempotent — 200 if existed,
     * 201 if newly created.
     */
    @PostMapping
    public ResponseEntity<CustomerResponse> selfProvision(@AuthenticationPrincipal Jwt jwt) {
        log.debug("POST /me sub={}", jwt.getSubject());
        boolean existed = service.findByKeycloakId(jwt.getSubject()).isPresent();
        Customer c = service.getOrCreate(
                jwt.getSubject(),
                claim(jwt, "email", "unknown@oriah.local"),
                claim(jwt, "given_name", "Unknown"),
                claim(jwt, "family_name", "Unknown")
        );
        return existed
                ? ResponseEntity.ok(CustomerResponse.from(c))
                : ResponseEntity.created(URI.create("/api/v1/customers/me")).body(CustomerResponse.from(c));
    }

    @GetMapping
    public ResponseEntity<CustomerResponse> me(@AuthenticationPrincipal Jwt jwt) {
        return service.findByKeycloakId(jwt.getSubject())
                .map(CustomerResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PatchMapping
    public ResponseEntity<CustomerResponse> updateProfile(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CustomerUpdateRequest body) {
        return service.updateProfile(jwt.getSubject(), body)
                .map(CustomerResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/addresses")
    public ResponseEntity<java.util.List<AddressResponse>> listAddresses(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(
                service.listAddresses(jwt.getSubject()).stream().map(AddressResponse::from).toList()
        );
    }

    @PostMapping("/addresses")
    public ResponseEntity<AddressResponse> addAddress(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody AddressRequest body) {
        return service.addAddress(jwt.getSubject(), body)
                .map(AddressResponse::from)
                .map(a -> ResponseEntity.created(URI.create("/api/v1/customers/me/addresses/" + a.id())).body(a))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/addresses/{addressId}")
    public ResponseEntity<Void> deleteAddress(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("addressId") UUID addressId) {
        return service.removeAddress(jwt.getSubject(), addressId)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    private static String claim(Jwt jwt, String name, String fallback) {
        Object v = jwt.getClaim(name);
        return v == null ? fallback : v.toString();
    }
}
