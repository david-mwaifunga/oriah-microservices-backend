package com.oriah.finance.controller;

import org.springframework.http.ProblemDetail;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.oriah.finance.dto.FinanceContactInfoDto;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * {@code GET /api/v1/admin/finance/contact-info}: who owns the finance service, for staff. It sits inside the one prefix the gateway routes to this service, and
 * needs the finance capability, not merely a login: the body carries on-call phone numbers, and the gateway and the service both enforce the rule.
 */
@RestController
@RequestMapping("/api/v1/admin/finance")
@PreAuthorize("hasRole('FINANCE_ADMIN')")
@Tag(name = "Service contact", description = "Who owns this service")
public class ContactInfoController {

    private final FinanceContactInfoDto contactInfo;

    public ContactInfoController(FinanceContactInfoDto contactInfo) {
        this.contactInfo = contactInfo;
    }

    @ApiResponse(responseCode = "200", description = "The owning team and how to reach them")
    @ApiResponse(responseCode = "401", description = "No or invalid bearer token",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "Authenticated, but lacking the finance capability",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "500", description = "Unexpected server error",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping("/contact-info")
    @Operation(operationId = "fetchFinanceContactInfo", summary = "Who owns the finance service, and how to reach them")
    public FinanceContactInfoDto contactInfo() {
        return contactInfo;
    }
}
