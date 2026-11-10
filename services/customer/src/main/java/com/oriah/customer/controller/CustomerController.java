package com.oriah.customer.controller;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.ProblemDetail;
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
import com.oriah.observability.ResourceNotFoundException;
import com.oriah.customer.model.Customer;
import com.oriah.customer.service.CustomerService;

import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import lombok.extern.slf4j.Slf4j;

/**
 * Customer self-service ("me") endpoints. Every method extracts the
 * Keycloak {@code sub} from the JWT and acts on that customer only.
 * Requires {@code ROLE_USER}.
 */
@RestController
@RequestMapping("/api/v1/customers/me")
@Tag(name = "Customer", description = "The caller's own profile and addresses")
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
    @Operation(operationId = "selfProvision", summary = "Create the caller's profile from their JWT; idempotent")
    @ApiResponse(responseCode = "201", description = "Profile created from the token")
    @ApiResponse(responseCode = "400", description = "Validation failure or unparseable parameter; see fieldErrors",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping
    public ResponseEntity<CustomerResponse> selfProvision(@AuthenticationPrincipal Jwt jwt) {
        log.debug("POST /me sub={}", jwt.getSubject());
        // The service says whether THIS call created the profile. Asking "did it exist?" first
        // and creating after is not the same: two first calls at once would both be told "no"
        // and both answer 201 for one profile.
        CustomerService.Provisioned provisioned = service.provision(
                jwt.getSubject(),
                claim(jwt, "email", "unknown@oriah.local"),
                claim(jwt, "given_name", "Unknown"),
                claim(jwt, "family_name", "Unknown")
        );
        CustomerResponse body = CustomerResponse.from(provisioned.customer());
        return provisioned.created()
                ? ResponseEntity.created(URI.create("/api/v1/customers/me")).body(body)
                : ResponseEntity.ok(body);
    }

    /**
     * The caller's own profile.
     *
     * <p>Reachable 404: a brand-new Keycloak user holds a valid JWT but has
     * never called {@code POST /me}. With the old empty body the storefront
     * could only report "Upstream returned 404 Not Found" when the actionable
     * message is that the profile has not been created yet.
     *
     * <p>The detail says {@code customer : 'me'} rather than echoing the
     * subject claim -- the endpoint is {@code /customers/me}, so "me" is what
     * was actually asked for, and putting an identity claim in a response body
     * sends it into logs, bug reports and screenshots for no benefit.
     */
    @Operation(operationId = "me", summary = "The caller's profile")
    @ApiResponse(responseCode = "200", description = "The profile")
    @ApiResponse(responseCode = "404", description = "No such resource",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping
    public CustomerResponse me(@AuthenticationPrincipal Jwt jwt) {
        return service.findByKeycloakId(jwt.getSubject())
                .map(CustomerResponse::from)
                .orElseThrow(CustomerController::noProfile);
    }

    @Operation(operationId = "updateProfile", summary = "Partially update the caller's profile")
    @ApiResponse(responseCode = "200", description = "The updated profile")
    @ApiResponse(responseCode = "400", description = "Validation failure or unparseable parameter; see fieldErrors",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No such resource",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @PatchMapping
    public CustomerResponse updateProfile(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CustomerUpdateRequest body) {
        return service.updateProfile(jwt.getSubject(), body)
                .map(CustomerResponse::from)
                .orElseThrow(CustomerController::noProfile);
    }

    @Operation(operationId = "listAddresses", summary = "The caller's addresses")
    @ApiResponse(responseCode = "200", description = "The addresses, possibly empty")
    @GetMapping("/addresses")
    public ResponseEntity<java.util.List<AddressResponse>> listAddresses(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(
                service.listAddresses(jwt.getSubject()).stream().map(AddressResponse::from).toList()
        );
    }

    @Operation(operationId = "addAddress", summary = "Add an address to the caller's profile")
    @ApiResponse(responseCode = "201", description = "Created; Location points at the new address")
    @ApiResponse(responseCode = "400", description = "Validation failure or unparseable parameter; see fieldErrors",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No such resource",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping("/addresses")
    public ResponseEntity<AddressResponse> addAddress(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody AddressRequest body) {
        AddressResponse created = service.addAddress(jwt.getSubject(), body)
                .map(AddressResponse::from)
                .orElseThrow(CustomerController::noProfile);
        return ResponseEntity
                .created(URI.create("/api/v1/customers/me/addresses/" + created.id()))
                .body(created);
    }

    @Operation(operationId = "deleteAddress", summary = "Remove one of the caller's addresses")
    @ApiResponse(responseCode = "204", description = "Deleted; no body")
    @ApiResponse(responseCode = "404", description = "No such resource",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @DeleteMapping("/addresses/{addressId}")
    public ResponseEntity<Void> deleteAddress(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("addressId") UUID addressId) {
        if (!service.removeAddress(jwt.getSubject(), addressId)) {
            throw new ResourceNotFoundException("Address", "addressId", addressId.toString());
        }
        return ResponseEntity.noContent().build();
    }

    /**
     * The caller has a token but no customer row yet.
     *
     * <p>Shared by three handlers because it is one condition. Note the
     * inconsistency this does NOT resolve: {@code GET /me/addresses} answers
     * the same state with {@code 200} and an empty list. One condition, three
     * answers. Harmonising them is a status change with no bug behind it, so it
     * is deferred to be done together with the frontend rather than slipped
     * into a conventions tier.
     */
    private static ResourceNotFoundException noProfile() {
        return new ResourceNotFoundException("Customer", "customer", "me");
    }

    private static String claim(Jwt jwt, String name, String fallback) {
        Object v = jwt.getClaim(name);
        return v == null ? fallback : v.toString();
    }
}
