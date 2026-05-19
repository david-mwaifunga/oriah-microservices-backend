package com.oriah.review.service;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.oriah.review.dto.AggregateRatingResponse;
import com.oriah.review.dto.CreateReviewRequest;
import com.oriah.review.model.Review;
import com.oriah.review.repository.ReviewRepository;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class ReviewService {

    private final ReviewRepository repository;

    public ReviewService(ReviewRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public Review createOrUpdate(UUID productId, String keycloakId, CreateReviewRequest req) {
        return repository.findByProductIdAndKeycloakId(productId, keycloakId)
                .map(existing -> {
                    // Mutate the managed entity in place — JPA dirty checking
                    // persists. Saving a NEW Review here would hit the
                    // UNIQUE(product_id, keycloak_id) constraint.
                    existing.replace(req.rating(), req.title(), req.body());
                    log.info("Updated review id={} product={} customer={} newRating={}",
                            existing.id(), productId, keycloakId, req.rating());
                    return existing;
                })
                .orElseGet(() -> {
                    Review fresh = new Review(productId, keycloakId, req.orderId(),
                            req.rating(), req.title(), req.body());
                    Review saved = repository.save(fresh);
                    log.info("Created review id={} product={} customer={} rating={}",
                            saved.id(), productId, keycloakId, req.rating());
                    return saved;
                });
    }

    @Transactional(readOnly = true)
    public Page<Review> findForProduct(UUID productId, Pageable pageable) {
        return repository.findByProductIdOrderByCreatedAtDesc(productId, pageable);
    }

    @Transactional(readOnly = true)
    public Page<Review> findMine(String keycloakId, Pageable pageable) {
        return repository.findByKeycloakIdOrderByCreatedAtDesc(keycloakId, pageable);
    }

    @Transactional(readOnly = true)
    public AggregateRatingResponse aggregate(UUID productId) {
        Object[] row = repository.averageRating(productId);
        // averageRating returns Object[][]{Object[]{avg, count}} or {[null, 0]} for no rows
        if (row == null || row.length == 0) return new AggregateRatingResponse(null, 0L);
        Object inner = row[0];
        if (inner instanceof Object[] arr && arr.length >= 2) {
            Double avg = arr[0] == null ? null : ((Number) arr[0]).doubleValue();
            Long count = arr[1] == null ? 0L : ((Number) arr[1]).longValue();
            return new AggregateRatingResponse(avg, count);
        }
        // Fallback: positional row (avg, count) directly
        Double avg = row[0] == null ? null : ((Number) row[0]).doubleValue();
        Long count = row.length > 1 && row[1] != null ? ((Number) row[1]).longValue() : 0L;
        return new AggregateRatingResponse(avg, count);
    }

    @Transactional
    public Optional<Review> markHelpful(UUID id) {
        return repository.findById(id).map(r -> { r.markHelpful(); return r; });
    }
}
