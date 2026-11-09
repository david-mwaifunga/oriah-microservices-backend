package com.oriah.order.events;

import java.util.Map;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.oriah.events.InboxSchemaGuard;
import com.oriah.events.OutboxSchemaGuard;

import static com.oriah.events.InboxSchemaGuard.Table.PROCESSED_EVENT;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * V5 gives order the event log's tables (MVP_PLAN Phase 1, item 2), copied
 * from the reference SQL in shared/oriah-events. The module's guards prove the
 * copy is exact -- every column, constraint and trigger -- and that the outbox
 * and processed_event really refuse UPDATE, DELETE and TRUNCATE: the outbox is
 * order's permanent record of every sale, and a row that could be edited
 * would make a replay lie.
 *
 * <p>processed_event is here although order consumes nothing: it is where
 * order records the replay requests it has answered, so a request delivered
 * twice is answered once.
 *
 * <p>Plain Flyway on a fresh Postgres, the migrations exactly as the service
 * runs them, with nothing else written first.
 */
class EventLogSchemaTest {

    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));

    private static DataSource dataSource;

    @BeforeAll
    static void migrate() {
        POSTGRES.start();
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    @AfterAll
    static void stop() {
        POSTGRES.stop();
    }

    @Test
    @DisplayName("the outbox and its relay cursor are exactly the reference tables, the cursor seeded at 0")
    void outboxMatchesTheReference() {
        assertThat(OutboxSchemaGuard.problems(dataSource)).isEmpty();
    }

    @Test
    @DisplayName("processed_event is exactly the reference table, append-only")
    void processedEventMatchesTheReference() {
        assertThat(InboxSchemaGuard.problems(dataSource, PROCESSED_EVENT)).isEmpty();
    }

    @Test
    @DisplayName("orders carries a version for optimistic locking: BIGINT NOT NULL DEFAULT 0")
    void ordersHaveAVersion() {
        // Every existing order starts at 0, so the first change to it after
        // V5 is checked like any other. A NULL version would make Hibernate
        // treat the row as never saved.
        Map<String, Object> column = new JdbcTemplate(dataSource).queryForMap("""
                SELECT data_type, is_nullable, column_default
                FROM information_schema.columns
                WHERE table_schema = current_schema() AND table_name = 'orders' AND column_name = 'version'""");
        assertThat(column).containsEntry("data_type", "bigint")
                .containsEntry("is_nullable", "NO")
                .containsEntry("column_default", "0");
    }
}
