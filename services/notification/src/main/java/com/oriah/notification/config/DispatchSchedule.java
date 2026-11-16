package com.oriah.notification.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.oriah.notification.delivery.MessageDispatcher;

/**
 * Runs the message dispatcher on its schedule: a pass, then {@code oriah.notification.send.delay} milliseconds of rest, then the next (so two
 * passes of one instance never overlap, however long a mail server takes).
 *
 * <p>It exists only while {@code oriah.notification.send.enabled} is true, which it is as shipped. The test configuration turns it off: a pass
 * landing in the middle of a test would send, or give up on, the very message the test is asserting on. Tests call the dispatcher themselves.
 */
@Component
@ConditionalOnProperty(name = "oriah.notification.send.enabled", havingValue = "true")
public class DispatchSchedule {

    private final MessageDispatcher dispatcher;

    public DispatchSchedule(MessageDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Scheduled(fixedDelayString = "${oriah.notification.send.delay}", initialDelayString = "${oriah.notification.send.initial-delay}")
    void pass() {
        dispatcher.run();
    }
}
