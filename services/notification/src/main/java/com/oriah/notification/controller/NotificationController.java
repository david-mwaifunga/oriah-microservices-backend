package com.oriah.notification.controller;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.oriah.notification.dto.NotificationResponse;
import com.oriah.notification.repository.NotificationLogRepository;

@RestController
@RequestMapping("/api/v1/notifications/me")
@Tag(name = "Notifications", description = "The caller's own notification log")
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
public class NotificationController {

    private final NotificationLogRepository repository;

    public NotificationController(NotificationLogRepository repository) {
        this.repository = repository;
    }

    @Operation(operationId = "mine", summary = "The caller's notifications, newest first")
    @ApiResponse(responseCode = "200", description = "A page of notifications, possibly empty")
    @ApiResponse(responseCode = "400", description = "Validation failure or unparseable parameter; see fieldErrors",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping
    public ResponseEntity<Page<NotificationResponse>> mine(
            @AuthenticationPrincipal Jwt jwt,
            @PageableDefault(size = 50) Pageable pageable) {
        return ResponseEntity.ok(
                repository.findByKeycloakIdOrderByDeliveredAtDesc(jwt.getSubject(), pageable)
                        .map(NotificationResponse::from)
        );
    }
}
