package com.oriah.notification.delivery;

import org.springframework.stereotype.Component;

import com.oriah.notification.model.NotificationLog;

import lombok.extern.slf4j.Slf4j;

/**
 * The stand-in for real delivery until Phase 11: it "sends" a notification by
 * writing it to the log, so the message can be seen in the service's output
 * (and in Loki).
 */
@Component
@Slf4j
public class LogNotificationSender implements NotificationSender {

    @Override
    public void send(NotificationLog notification) {
        log.info("[EMAIL STUB] to={} subject=\"{}\" body=\"{}\"",
                notification.keycloakId(), notification.subject(), notification.body());
    }
}
