package com.oriah.notification.events;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.oriah.events.InboxSchemaGuard;

import com.oriah.notification.ServiceTest;

import static com.oriah.events.InboxSchemaGuard.Table.PROCESSED_EVENT;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * This service's migration created the inbox table exactly as the event log
 * convention agreed (shared/oriah-events, db/oriah-events/V2), and it refuses
 * UPDATE, DELETE and TRUNCATE.
 *
 * <p>Notification sends one message per event, so it keeps only
 * processed_event, the record of what it has already sent. It keeps no
 * processed_aggregate: it is not a copy of anybody's state.
 */
@ServiceTest
class InboxSchemaTest {

    @Autowired
    private DataSource dataSource;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    @Test
    @DisplayName("processed_event is exactly the agreed shape, and append-only")
    void processedEventIsTheAgreedShape() {
        assertThat(InboxSchemaGuard.problems(dataSource, PROCESSED_EVENT)).isEmpty();
    }
}
