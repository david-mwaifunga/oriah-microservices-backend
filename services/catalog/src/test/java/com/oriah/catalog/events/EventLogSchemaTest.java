package com.oriah.catalog.events;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cloud.stream.binder.test.TestChannelBinderConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.oriah.catalog.TestcontainersConfiguration;
import com.oriah.events.InboxSchemaGuard;
import com.oriah.events.OutboxSchemaGuard;

import static com.oriah.events.InboxSchemaGuard.Table.PROCESSED_EVENT;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Catalog's copy of the event log tables (Flyway V4) is the agreed one.
 *
 * <p>V4 copies the reference SQL from shared/oriah-events by hand, and a copy
 * can drift: a column one size short, a missing trigger, a cursor seeded past
 * zero. Any of those either lets the permanent log be edited or makes the
 * relay skip events for good, and none of them would fail a write. The
 * module's guards compare every column, constraint and default, and actually
 * try UPDATE, DELETE and TRUNCATE on the append-only tables, inside a
 * transaction they roll back.
 *
 * <p>{@code processed_event} is here although catalog consumes nothing: it is
 * where the replay listener records the requests it has answered, so a
 * redelivered request is not replayed twice.
 *
 * <p>Same configuration as the other event tests, so they share one context.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestChannelBinderConfiguration.class})
@TestPropertySource(properties = {"eureka.client.enabled=false"})
class EventLogSchemaTest {

    @Autowired
    private DataSource dataSource;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    @Test
    @DisplayName("the outbox and its relay cursor match the reference exactly, and the outbox refuses edits")
    void outboxMatchesTheReference() {
        assertThat(OutboxSchemaGuard.problems(dataSource)).isEmpty();
    }

    @Test
    @DisplayName("processed_event, where answered replay requests are recorded, matches the reference exactly")
    void processedEventMatchesTheReference() {
        assertThat(InboxSchemaGuard.problems(dataSource, PROCESSED_EVENT)).isEmpty();
    }
}
