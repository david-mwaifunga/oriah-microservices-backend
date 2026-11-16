package com.oriah.notification.controller;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
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

import com.oriah.notification.contact.ContactBook;
import com.oriah.notification.dto.NotificationResponse;
import com.oriah.notification.model.RecipientKind;
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
    private final ContactBook contacts;

    public NotificationController(NotificationLogRepository repository, ContactBook contacts) {
        this.repository = repository;
        this.contacts = contacts;
    }

    @Operation(operationId = "mine", summary = "The caller's own messages of every status, newest first",
            description = "What the website's bell shows. A message that was not emailed is still listed. Any sort parameter is ignored.")
    @ApiResponse(responseCode = "200", description = "A page of notifications, possibly empty")
    @ApiResponse(responseCode = "400", description = "Validation failure or unparseable parameter; see fieldErrors",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping
    public ResponseEntity<Page<NotificationResponse>> mine(
            @AuthenticationPrincipal Jwt jwt,
            @PageableDefault(size = 50) Pageable pageable) {
        // Newest first, always: the page and its size are the caller's, the order is not. (A sort by a field name would be handed to the
        // database as it is, and the field the storefront knows, deliveredAt, is no longer a column.)
        Pageable newestFirst = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());
        Page<NotificationResponse> mine = repository
                .findByKeycloakIdAndRecipientKindOrderByRecordedAtDesc(jwt.getSubject(), RecipientKind.SHOPPER, newestFirst)
                .map(NotificationResponse::from);
        // The bell is also how notification learns where to email this customer: their own token says. Noted AFTER the read, in a transaction
        // of its own, and it never fails: the bell is answered whatever happens to the note. (A write while answering a GET is unusual; it is
        // one note of the caller's own data, made at most once per new token.)
        contacts.seen(jwt);
        return ResponseEntity.ok(mine);
    }
}
