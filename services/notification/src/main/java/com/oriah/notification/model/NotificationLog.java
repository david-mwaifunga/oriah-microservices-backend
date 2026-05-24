package com.oriah.notification.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.oriah.jpa.AuditableEntity;

@Entity
@Table(name = "notification_log")
public class NotificationLog extends AuditableEntity {

    @Id
    @Column(nullable = false)
    private UUID id;

    @Column(name = "order_id")
    private UUID orderId;

    @Column(name = "keycloak_id", nullable = false)
    private String keycloakId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(nullable = false, length = 20)
    private String channel;

    @Column(nullable = false)
    private String subject;

    @Column(nullable = false, columnDefinition = "text")
    private String body;

    @Column(name = "delivered_at", nullable = false)
    private Instant deliveredAt;

    protected NotificationLog() {}

    public NotificationLog(UUID orderId, String keycloakId, String eventType,
                           String channel, String subject, String body) {
        this.id = UUID.randomUUID();
        this.orderId = orderId;
        this.keycloakId = keycloakId;
        this.eventType = eventType;
        this.channel = channel;
        this.subject = subject;
        this.body = body;
        this.deliveredAt = Instant.now();
    }

    public UUID id() { return id; }
    public UUID orderId() { return orderId; }
    public String keycloakId() { return keycloakId; }
    public String eventType() { return eventType; }
    public String channel() { return channel; }
    public String subject() { return subject; }
    public String body() { return body; }
    public Instant deliveredAt() { return deliveredAt; }
}
