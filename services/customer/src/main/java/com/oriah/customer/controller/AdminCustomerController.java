package com.oriah.customer.controller;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.oriah.customer.dto.CustomerResponse;
import com.oriah.observability.ResourceNotFoundException;
import com.oriah.customer.service.CustomerService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import lombok.extern.slf4j.Slf4j;

/**
 * Admin-side read access: list/lookup any customer by their internal
 * id. Reserved for support agents with the CUSTOMER_ADMIN role.
 */
@RestController
@RequestMapping("/api/v1/admin/customers")
@Tag(name = "Customer (admin)", description = "Customer reads, CUSTOMER_ADMIN only")
@ApiResponses({
        @ApiResponse(responseCode = "401", description = "No or invalid bearer token",
                content = @Content(mediaType = "application/problem+json",
                        schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "403", description = "Authenticated, but lacking the required role",
                content = @Content(mediaType = "application/problem+json",
                        schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "500", description = "Unexpected server error",
                content = @Content(mediaType = "application/problem+json",
                        schema = @Schema(implementation = ProblemDetail.class)))
})
@PreAuthorize("hasRole('CUSTOMER_ADMIN')")
@Slf4j
public class AdminCustomerController {

    private final CustomerService service;

    public AdminCustomerController(CustomerService service) {
        this.service = service;
    }

    @Operation(operationId = "list", summary = "All customers, paged")
    @ApiResponse(responseCode = "200", description = "A page of customers")
    @ApiResponse(responseCode = "400", description = "Validation failure or unparseable parameter; see fieldErrors",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping
    public ResponseEntity<Page<CustomerResponse>> list(
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(service.findAll(pageable).map(CustomerResponse::from));
    }

    @Operation(operationId = "get", summary = "One customer by id")
    @ApiResponse(responseCode = "200", description = "The customer")
    @ApiResponse(responseCode = "400", description = "Validation failure or unparseable parameter; see fieldErrors",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No such resource",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping("/{id}")
    public CustomerResponse get(@PathVariable("id") UUID id) {
        return service.findById(id)
                .map(CustomerResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("Customer", "id", id.toString()));
    }
}
