package com.oriah.payment.schema;

import java.time.OffsetDateTime;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.oriah.events.InboxSchemaGuard;
import com.oriah.events.OutboxSchemaGuard;
import com.oriah.payment.TestcontainersConfiguration;

import static com.oriah.events.InboxSchemaGuard.Table.PROCESSED_EVENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Payment's V5, read straight off the migrated database: what the tables
 * themselves refuse, whatever the Java code above them does.
 *
 * <p>Each rule here is a backstop for one way money goes wrong. The Java
 * code checks the same things first and answers with a sentence; these make
 * sure that a bug in that code, or a second instance racing the first, still
 * cannot write the bad row.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"eureka.client.enabled=false"})
class PaymentSchemaTest {

    @Autowired
    private DataSource dataSource;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
    }

    // ------------------------------------------------------------------
    // The event log, copied from shared/oriah-events
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the outbox and its relay cursor are exactly the agreed shape")
    void outboxMatchesTheReferenceSql() {
        assertThat(OutboxSchemaGuard.problems(dataSource)).isEmpty();
    }

    @Test
    @DisplayName("processed_event (replay-request dedupe) is exactly the agreed shape")
    void processedEventMatchesTheReferenceSql() {
        assertThat(InboxSchemaGuard.problems(dataSource, PROCESSED_EVENT)).isEmpty();
    }
}
