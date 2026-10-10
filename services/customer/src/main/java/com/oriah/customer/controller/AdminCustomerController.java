package com.oriah.customer.controller;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.oriah.customer.dto.CustomerResponse;
import com.oriah.observability.ResourceNotFoundException;
import com.oriah.customer.service.CustomerService;

import lombok.extern.slf4j.Slf4j;

/**
 * Admin-side read access: list/lookup any customer by their internal
 * id. Reserved for support agents with the CUSTOMER_ADMIN role.
 */
@RestController
@RequestMapping("/api/v1/admin/customers")
@PreAuthorize("hasRole('CUSTOMER_ADMIN')")
@Slf4j
public class AdminCustomerController {

    private final CustomerService service;

    public AdminCustomerController(CustomerService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<Page<CustomerResponse>> list(
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(service.findAll(pageable).map(CustomerResponse::from));
    }

    @GetMapping("/{id}")
    public CustomerResponse get(@PathVariable("id") UUID id) {
        return service.findById(id)
                .map(CustomerResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("Customer", "id", id.toString()));
    }
}
