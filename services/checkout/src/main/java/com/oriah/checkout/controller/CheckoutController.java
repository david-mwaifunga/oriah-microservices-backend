package com.oriah.checkout.controller;

import org.springframework.http.ProblemDetail;
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

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/checkout")
@Tag(name = "Checkout", description = "Checkout preview and confirmation")
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
public class CheckoutController {

    private final CheckoutService service;

    public CheckoutController(CheckoutService service) {
        this.service = service;
    }

    @Operation(operationId = "preview", summary = "Price the caller's cart without committing anything")
    @ApiResponse(responseCode = "200", description = "The priced preview")
    @ApiResponse(responseCode = "400", description = "Validation failure or unparseable parameter; see fieldErrors",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping("/preview")
    public ResponseEntity<PreviewResponse> preview(@Valid @RequestBody PreviewRequest body) {
        return ResponseEntity.ok(service.preview(body));
    }

    @Operation(operationId = "confirm", summary = "Run the checkout saga: reserve, pay, place the order")
    @ApiResponse(responseCode = "200", description = "The saga result. NOTE: failures are currently also reported as 200 with a message field; see Tier 7")
    @ApiResponse(responseCode = "400", description = "Validation failure or unparseable parameter; see fieldErrors",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping("/confirm")
    public ResponseEntity<ConfirmResponse> confirm(@Valid @RequestBody ConfirmRequest body) {
        return ResponseEntity.ok(service.confirm(body));
    }
}
