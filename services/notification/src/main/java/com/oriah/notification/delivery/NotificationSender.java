package com.oriah.notification.delivery;

import com.oriah.notification.model.NotificationLog;

/**
 * Delivers one notification to the person it is for -- the step that leaves
 * this service and cannot be taken back.
 *
 * <p>Today the only implementation is {@link LogNotificationSender}, a stub
 * that writes the message to the log. Phase 11 brings real email, WhatsApp
 * and SMS behind this same interface.
 */
public interface NotificationSender {

    /**
     * @param notification the notification as recorded in notification_log:
     *                     who it is for, the channel, subject and body
     */
    void send(NotificationLog notification);
}
