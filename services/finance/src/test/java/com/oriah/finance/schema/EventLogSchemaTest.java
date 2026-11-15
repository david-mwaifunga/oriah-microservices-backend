package com.oriah.finance.schema;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.oriah.events.InboxSchemaGuard;
import com.oriah.finance.TestcontainersConfiguration;

import static com.oriah.events.InboxSchemaGuard.Table.OUTBOX_CATCH_UP;
import static com.oriah.events.InboxSchemaGuard.Table.PROCESSED_AGGREGATE;
import static com.oriah.events.InboxSchemaGuard.Table.PROCESSED_EVENT;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Finance's copy of the event log's consumer tables (Flyway V3) is the agreed one.
 *
 * <p>V3 copies the reference SQL from shared/oriah-events by hand, and a copy can drift: a column one size short, a missing trigger. Any of those lets the record of
 * what finance has applied be edited, so an event applies twice and a sale is counted twice. The module's guard compares every column, constraint and default, and
 * actually tries UPDATE, DELETE and TRUNCATE on the append-only tables, inside a transaction it rolls back. Finance has no outbox: it produces nothing.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
class EventLogSchemaTest {

    @Autowired
    private DataSource dataSource;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    @Test
    @DisplayName("processed_event, processed_aggregate and outbox_catch_up match the reference exactly, and processed_event refuses edits")
    void theConsumerTablesMatchTheReference() {
        assertThat(InboxSchemaGuard.problems(dataSource, PROCESSED_EVENT, PROCESSED_AGGREGATE, OUTBOX_CATCH_UP)).isEmpty();
    }
}
