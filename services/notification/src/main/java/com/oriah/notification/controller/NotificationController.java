package com.oriah.notification.controller;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.oriah.notification.dto.NotificationResponse;
import com.oriah.notification.repository.NotificationLogRepository;

@RestController
@RequestMapping("/api/v1/notifications/me")
@PreAuthorize("hasRole('USER')")
public class NotificationController {

    private final NotificationLogRepository repository;

    public NotificationController(NotificationLogRepository repository) {
        this.repository = repository;
    }

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
