package com.oriah.shipping.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.oriah.shipping.dto.ShippingContactInfoDto;

import org.springframework.http.ProblemDetail;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * {@code GET /api/v1/shipping/contact-info} — who owns the shipping service.
 *
 * <p>Moved here from {@code ShippingController}, where it was first written as
 * the template for the other eleven services. Those were generated with a
 * dedicated controller, so shipping's living on the domain controller was the
 * odd one out — same endpoint, different shape, for no reason other than the
 * order the work happened in.
 *
 * <p>The move also makes the {@code operationId} consistent:
 * {@code fetchShippingContactInfo} rather than springdoc's derived
 * {@code contactInfo}. Safe against the acceptance gate because the Tier 0
 * baseline has no shipping contact-info at all, so the whole operation is still
 * an addition.
 *
 * <h2>Why this path</h2>
 *
 * <p>{@code /api/v1/shipping/contact-info} sits inside the prefix the gateway
 * already routes here ({@code /api/v1/shipping/**}), so it needs no gateway
 * route and leaves {@code RouteSnapshotTest}'s 29-route snapshot untouched.
 *
 * <p>Authenticated, unlike the course's open version: {@code SecurityConfig}
 * requires a token for everything but actuator and the API docs, and the
 * details include on-call phone numbers.
 */
@RestController
@RequestMapping("/api/v1/shipping")
@Tag(name = "Service contact", description = "Who owns this service")
public class ContactInfoController {

    private final ShippingContactInfoDto contactInfo;

    public ContactInfoController(ShippingContactInfoDto contactInfo) {
        this.contactInfo = contactInfo;
    }

    /**
     * Returns the {@code @ConfigurationProperties} bean directly, so editing
     * {@code config-repo/shipping.yaml} and calling
     * {@code POST /actuator/refresh} changes the response without a redeploy —
     * which works only because the DTO is mutable and gets rebound in place.
     */
    @ApiResponse(responseCode = "200", description = "The owning team and how to reach them")
    @ApiResponse(responseCode = "401", description = "No or invalid bearer token",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "500", description = "Unexpected server error",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping("/contact-info")
    @Operation(operationId = "fetchShippingContactInfo",
            summary = "Who owns the shipping service, and how to reach them")
    public ShippingContactInfoDto contactInfo() {
        return contactInfo;
    }
}
