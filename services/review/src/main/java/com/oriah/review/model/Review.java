package com.oriah.review.model;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.oriah.jpa.BaseEntity;

@Entity
@Table(name = "reviews")
public class Review extends BaseEntity {

    @Id
    @Column(nullable = false)
    private UUID id;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "keycloak_id", nullable = false)
    private String keycloakId;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(nullable = false)
    private Short rating;

    @Column
    private String title;

    @Column(columnDefinition = "text")
    private String body;

    @Column(name = "helpful_count", nullable = false)
    private Integer helpfulCount;

    protected Review() {}

    public Review(UUID productId, String keycloakId, UUID orderId,
                  int rating, String title, String body) {
        if (rating < 1 || rating > 5) {
            throw new IllegalArgumentException("rating must be 1..5");
        }
        this.id = UUID.randomUUID();
        this.productId = productId;
        this.keycloakId = keycloakId;
        this.orderId = orderId;
        this.rating = (short) rating;
        this.title = title;
        this.body = body;
        this.helpfulCount = 0;
    }

    public UUID id() { return id; }
    public UUID productId() { return productId; }
    public String keycloakId() { return keycloakId; }
    public UUID orderId() { return orderId; }
    public Short rating() { return rating; }
    public String title() { return title; }
    public String body() { return body; }
    public Integer helpfulCount() { return helpfulCount; }

    public void markHelpful() { this.helpfulCount++; }

    /**
     * Replace the textual content + rating in place, keeping id +
     * created_at. Called when the same customer re-reviews the same
     * product (UNIQUE(product_id, keycloak_id) means we update, not
     * insert).
     */
    public void replace(int rating, String title, String body) {
        if (rating < 1 || rating > 5) {
            throw new IllegalArgumentException("rating must be 1..5");
        }
        this.rating = (short) rating;
        this.title = title;
        this.body = body;
    }
}
