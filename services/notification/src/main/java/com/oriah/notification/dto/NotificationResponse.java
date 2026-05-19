package com.oriah.notification.dto;

import java.time.Instant;
import java.util.UUID;

import com.oriah.notification.model.NotificationLog;

public record NotificationResponse(
        UUID id,
        UUID orderId,
        String eventType,
        String channel,
        String subject,
        String body,
        Instant deliveredAt
) {
    public static NotificationResponse from(NotificationLog n) {
        return new NotificationResponse(
                n.id(), n.orderId(), n.eventType(), n.channel(),
                n.subject(), n.body(), n.deliveredAt());
    }
}
