package com.oriah.notification.events;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.sql.DataSource;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import com.oriah.notification.delivery.NotificationSender;
import com.oriah.notification.model.NotificationLog;

/**
 * Stands in for real delivery (today a log line; Phase 11 email, WhatsApp,
 * SMS) and records every notification it is asked to send -- together with
 * whether that notification's row was already committed at that moment.
 *
 * <p>"Committed" is checked the way anybody else would see it: on a
 * connection of its own, straight from the pool, not the one the
 * consumer's transaction holds. Inside that transaction the row is not yet
 * visible to any other connection, so a send made before the commit records
 * {@code false}, and one made after it records {@code true}.
 */
final class RecordingSender implements NotificationSender {

    /** One send: the notification, and whether its row was committed when it went out. */
    record Sent(NotificationLog notification, boolean committedWhenSent) {
    }

    private final DataSource dataSource;
    private final List<Sent> sent = new CopyOnWriteArrayList<>();

    RecordingSender(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void send(NotificationLog notification) {
        sent.add(new Sent(notification, committed(notification)));
    }

    List<Sent> sent() {
        return List.copyOf(sent);
    }

    void clear() {
        sent.clear();
    }

    private boolean committed(NotificationLog notification) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement query = connection.prepareStatement(
                     "SELECT count(*) FROM notification_log WHERE id = ?")) {
            query.setObject(1, notification.id());
            try (ResultSet rows = query.executeQuery()) {
                rows.next();
                return rows.getLong(1) == 1;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not check whether notification " + notification.id()
                    + " was committed", e);
        }
    }

    /** Puts the recorder in place of the real sender for the test that imports it. */
    @TestConfiguration(proxyBeanMethods = false)
    static class InPlaceOfTheRealSender {

        @Bean
        @Primary
        RecordingSender recordingSender(DataSource dataSource) {
            return new RecordingSender(dataSource);
        }
    }
}
