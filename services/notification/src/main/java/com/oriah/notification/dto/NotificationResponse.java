package com.oriah.notification.dto;

import java.time.Instant;
import java.util.UUID;

import com.oriah.notification.model.NotificationLog;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One message as its owner reads it in the website's bell.
 *
 * <p>Fields are only ever added here (the storefront reads this), and none of the added ones is required of a reader.
 */
public record NotificationResponse(
        UUID id,
        UUID orderId,
        String eventType,
        String channel,
        String subject,
        String body,
        @Schema(deprecated = true, description = "Always equal to recordedAt: when the message was written down. It never meant 'delivered'. "
                + "Read recordedAt; this field is removed once the storefront has switched.")
        Instant deliveredAt,
        @Schema(description = "When the message was written down.")
        Instant recordedAt,
        @Schema(description = "What became of the message: RECORDED (waiting to be emailed), SENT (a mail server took it), FAILED (tried and given "
                + "up, or handed over with no answer), NOT_SENT (deliberately not emailed; see statusReason). It is shown here whatever its status.",
                allowableValues = {"RECORDED", "SENT", "FAILED", "NOT_SENT"})
        String status,
        @Schema(description = "Why the message was not emailed or why emailing it failed; null while it waits and once it is sent. BELL_ONLY means "
                + "this kind of message is shown on the website only. SUPERSEDED means something later made it untrue: show it muted.",
                nullable = true)
        String statusReason,
        @Schema(description = "When a mail server took the message; null unless status is SENT. Whether it reached the mailbox is never known.",
                nullable = true)
        Instant sentAt
) {
    public static NotificationResponse from(NotificationLog n) {
        return new NotificationResponse(
                n.id(), n.orderId(), n.eventType(), n.channel(),
                n.subject(), n.body(), n.recordedAt(), n.recordedAt(),
                n.status().name(), n.statusReason() == null ? null : n.statusReason().name(), n.sentAt());
    }
}
