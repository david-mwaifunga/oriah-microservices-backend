package com.oriah.review.controller;

import java.net.URI;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.oriah.review.dto.AggregateRatingResponse;
import com.oriah.review.dto.CreateReviewRequest;
import com.oriah.review.dto.ReviewResponse;
import com.oriah.observability.ResourceNotFoundException;
import com.oriah.review.model.Review;
import com.oriah.review.service.ReviewService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1")
public class ReviewController {

    private final ReviewService service;

    public ReviewController(ReviewService service) {
        this.service = service;
    }

    /** Public: read all reviews for a product. */
    @GetMapping("/products/{productId}/reviews")
    public ResponseEntity<Page<ReviewResponse>> forProduct(
            @PathVariable("productId") UUID productId,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(service.findForProduct(productId, pageable).map(ReviewResponse::from));
    }

    /** Public: average rating + count for a product (PLP/PDP star summary). */
    @GetMapping("/products/{productId}/reviews/aggregate")
    public ResponseEntity<AggregateRatingResponse> aggregate(@PathVariable("productId") UUID productId) {
        return ResponseEntity.ok(service.aggregate(productId));
    }

    /** Auth required: write a review. */
    @PostMapping("/products/{productId}/reviews")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<ReviewResponse> create(
            @PathVariable("productId") UUID productId,
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateReviewRequest body) {
        Review r = service.createOrUpdate(productId, jwt.getSubject(), body);
        return ResponseEntity.created(URI.create(
                "/api/v1/products/" + productId + "/reviews/" + r.id())).body(ReviewResponse.from(r));
    }

    /** Auth required: list my own reviews. */
    @GetMapping("/reviews/me")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<Page<ReviewResponse>> mine(
            @AuthenticationPrincipal Jwt jwt,
            @PageableDefault(size = 50) Pageable pageable) {
        return ResponseEntity.ok(service.findMine(jwt.getSubject(), pageable).map(ReviewResponse::from));
    }

    /** Auth required: mark a review helpful (any USER). */
    @PostMapping("/reviews/{id}/helpful")
    @PreAuthorize("hasRole('USER')")
    public ReviewResponse markHelpful(@PathVariable("id") UUID id) {
        // Was orElseGet(() -> ResponseEntity.notFound().build()), which
        // answered 404 with an EMPTY BODY. A review can disappear under a
        // reader (deleted, or the product withdrawn), so this path is reachable
        // in normal use -- and with no problem body oriah-web could only show
        // "Upstream returned 404 Not Found", giving the user nothing to report.
        return service.markHelpful(id)
                .map(ReviewResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("Review", "id", id.toString()));
    }
}
