package com.oriah.review.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.oriah.review.model.Review;

public interface ReviewRepository extends JpaRepository<Review, UUID> {

    Page<Review> findByProductIdOrderByCreatedAtDesc(UUID productId, Pageable pageable);
    Page<Review> findByKeycloakIdOrderByCreatedAtDesc(String keycloakId, Pageable pageable);
    Optional<Review> findByProductIdAndKeycloakId(UUID productId, String keycloakId);

    /**
     * Average rating + count for a product. Returns null if no reviews yet.
     * The {@code Object[]} positional projection is the simplest portable
     * shape; callers map to a DTO.
     */
    @Query("SELECT AVG(r.rating), COUNT(r) FROM Review r WHERE r.productId = :productId")
    Object[] averageRating(@Param("productId") UUID productId);
}
