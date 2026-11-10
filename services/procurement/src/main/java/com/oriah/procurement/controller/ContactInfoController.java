package com.oriah.procurement.controller;

import org.springframework.http.ProblemDetail;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.oriah.procurement.dto.ProcurementContactInfoDto;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * {@code GET /api/v1/admin/procurement/contact-info} -- who owns the procurement
 * service, for staff.
 *
 * <h2>Why this path</h2>
 *
 * <p>The gateway routes only {@code /api/v1/admin/procurement/**} to this service
 * (a staff area), so the endpoint sits inside that prefix to be reachable at the
 * edge at all. Mounted at a service-named public path it would have needed a
 * route of its own, and left off the gateway it would have appeared in
 * {@code /v3/api-docs/procurement} while answering 404 at the edge. (Inventory's
 * twin, at {@code /api/v1/inventory/contact-info}, is not routed by the gateway:
 * a stale javadoc there says otherwise.)
 *
 * <h2>Why the role</h2>
 *
 * <p>The body carries on-call phone numbers, so it needs the procurement
 * capability, not merely a login. The gateway enforces the same rule for the
 * path, and the service enforces it too, because services call each other
 * directly and the ports are only closed in production.
 */
@RestController
@RequestMapping("/api/v1/admin/procurement")
@PreAuthorize("hasRole('PROCUREMENT_ADMIN')")
@Tag(name = "Service contact", description = "Who owns this service")
public class ContactInfoController {

    private final ProcurementContactInfoDto contactInfo;

    public ContactInfoController(ProcurementContactInfoDto contactInfo) {
        this.contactInfo = contactInfo;
    }

    /**
     * Returns the {@code @ConfigurationProperties} bean directly, so editing
     * {@code config-repo/procurement.yaml} and calling {@code POST /actuator/refresh}
     * changes the response without a redeploy. The {@code operationId} is pinned
     * so the generated client name cannot silently change.
     */
    @ApiResponse(responseCode = "200", description = "The owning team and how to reach them")
    @ApiResponse(responseCode = "401", description = "No or invalid bearer token",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "Authenticated, but lacking the procurement capability",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "500", description = "Unexpected server error",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping("/contact-info")
    @Operation(operationId = "fetchProcurementContactInfo",
            summary = "Who owns the procurement service, and how to reach them")
    public ProcurementContactInfoDto contactInfo() {
        return contactInfo;
    }
}
