package com.oriah.events;

import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static com.oriah.events.InboxSchemaGuard.Table.OUTBOX_CATCH_UP;
import static com.oriah.events.InboxSchemaGuard.Table.PROCESSED_AGGREGATE;
import static com.oriah.events.InboxSchemaGuard.Table.PROCESSED_EVENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The guards each adopting service runs against its own migrated database.
 *
 * <p>Two halves, both needed. The reference SQL (what services copy) must
 * pass cleanly -- that proves the SQL is right. A near-miss copy must have
 * every difference named -- that proves the guards actually look; a guard
 * that checked nothing would pass the reference too.
 */
@ProbeTest
class SchemaGuardTest {

    private static final String REFUSE = "; an append-only table must refuse it";

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PostgreSQLContainer postgres;

    private DataSource nearMiss;

    @BeforeEach
    void loadNearMiss() throws Exception {
        nearMiss = NearMissSchema.load(postgres);
    }

    @Test
    @DisplayName("the reference SQL passes both guards")
    void referencePasses() {
        assertThat(OutboxSchemaGuard.problems(dataSource)).isEmpty();
        assertThat(InboxSchemaGuard.problems(dataSource, PROCESSED_EVENT, PROCESSED_AGGREGATE, OUTBOX_CATCH_UP))
                .isEmpty();
    }

    @Test
    @DisplayName("the outbox guard names every difference in a near-miss copy, including missing triggers")
    void outboxGuardNamesEveryDifference() {
        assertThat(OutboxSchemaGuard.problems(nearMiss)).containsExactlyInAnyOrder(
                "outbox: column outbox_id is bigint not null identity by default, expected bigint not null identity always",
                "outbox: outbox_id's sequence is START 0 MINVALUE 0 INCREMENT 1 CACHE 20 NO CYCLE, expected "
                        + "START 1 MINVALUE 1 INCREMENT 1 CACHE 1 NO CYCLE -- the relay sends only ids above its "
                        + "cursor, which starts at 0, and only safely while ids follow commit order; otherwise "
                        + "it skips a row for good",
                "outbox: column occurred_at is timestamptz not null default now(), expected timestamptz not null",
                "outbox: column actor is varchar(128), expected varchar(128) not null",
                "outbox: column payload is jsonb not null, expected json not null",
                "outbox: unexpected column note text",
                "outbox: missing constraint UNIQUE (aggregate_type, aggregate_id, seq)",
                "outbox: UPDATE was allowed" + REFUSE,
                "outbox: DELETE was allowed" + REFUSE,
                "outbox: TRUNCATE was allowed" + REFUSE,
                "outbox_relay_cursor: no 'main' row; seed ('main', 0) in the migration that creates the table");
    }

    @Test
    @DisplayName("a cursor seeded past 0 is named: the relay sends only rows above it, so the rows below "
            + "it would never be published")
    void cursorSeededPastZeroIsNamed() {
        new JdbcTemplate(nearMiss).update("INSERT INTO outbox_relay_cursor VALUES ('main', 7, now())");

        assertThat(OutboxSchemaGuard.problems(nearMiss))
                .contains("outbox_relay_cursor: 'main' is at 7, which is neither 0 nor the id of an outbox row "
                        + "-- the relay only ever moves it to a row it has sent, so it was seeded wrong and "
                        + "every row up to 7 would never be published; seed ('main', 0)")
                .noneMatch(problem -> problem.contains("no 'main' row"));
    }

    @Test
    @DisplayName("a cursor on a row the relay has sent passes: the guard can run after other tests have "
            + "published")
    void cursorOnASentRowPasses() {
        JdbcTemplate copy = new JdbcTemplate(nearMiss);
        long row = copy.queryForObject("SELECT max(outbox_id) FROM outbox", Long.class);
        copy.update("INSERT INTO outbox_relay_cursor VALUES ('main', ?, now())", row);

        assertThat(OutboxSchemaGuard.problems(nearMiss)).noneMatch(problem -> problem.startsWith("outbox_relay_cursor"));
    }

    @Test
    @DisplayName("the inbox guard names every difference in the tables it is asked about")
    void inboxGuardNamesEveryDifference() {
        assertThat(InboxSchemaGuard.problems(nearMiss, PROCESSED_EVENT, PROCESSED_AGGREGATE, OUTBOX_CATCH_UP))
                .containsExactlyInAnyOrder(
                        "processed_event: column processed_at is timestamptz not null, expected timestamptz not null default now()",
                        "processed_event: TRUNCATE was allowed" + REFUSE,
                        "processed_aggregate: column last_seq is bigint, expected bigint not null",
                        "processed_aggregate: missing constraint PRIMARY KEY (consumer, aggregate_type, aggregate_id)",
                        "processed_aggregate: unexpected constraint PRIMARY KEY (consumer, aggregate_id)",
                        "outbox_catch_up: table is missing");

        // A producer that keeps only processed_event (for replay-request
        // dedupe) asks about that table alone and hears about nothing else.
        assertThat(InboxSchemaGuard.problems(nearMiss, PROCESSED_EVENT)).containsExactlyInAnyOrder(
                "processed_event: column processed_at is timestamptz not null, expected timestamptz not null default now()",
                "processed_event: TRUNCATE was allowed" + REFUSE);
    }

    @Test
    @DisplayName("asking the inbox guard about no table is refused, not a silent pass")
    void inboxGuardNeedsATable() {
        assertThatThrownBy(() -> InboxSchemaGuard.problems(dataSource))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the guards leave every table as they found it, even where DELETE and TRUNCATE were allowed")
    void guardsAreNonDestructive() {
        JdbcTemplate reference = new JdbcTemplate(dataSource);
        JdbcTemplate copy = new JdbcTemplate(nearMiss);
        long referenceRows = countOutbox(reference);
        long referenceMarkers = reference.queryForObject("SELECT count(*) FROM processed_event", Long.class);

        OutboxSchemaGuard.problems(dataSource);
        InboxSchemaGuard.problems(dataSource, PROCESSED_EVENT, PROCESSED_AGGREGATE, OUTBOX_CATCH_UP);
        OutboxSchemaGuard.problems(nearMiss);
        InboxSchemaGuard.problems(nearMiss, PROCESSED_EVENT, PROCESSED_AGGREGATE, OUTBOX_CATCH_UP);

        assertThat(countOutbox(reference)).isEqualTo(referenceRows);
        assertThat(reference.queryForObject("SELECT count(*) FROM processed_event", Long.class))
                .isEqualTo(referenceMarkers);
        List<String> fixtureRows = copy.queryForList("SELECT event_id::text FROM outbox", String.class);
        assertThat(fixtureRows).containsExactly("00000000-0000-0000-0000-000000000001");
    }

    private static long countOutbox(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT count(*) FROM outbox", Long.class);
    }
}
