package com.oriah.events;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.sql.DataSource;

import com.oriah.events.SchemaGuardSupport.TableSpec;

import static com.oriah.events.SchemaGuardSupport.columns;

/**
 * Proves a consuming service's own migration created the inbox tables
 * exactly as agreed (the reference SQL in this module's db/oriah-events).
 *
 * <p>Shipped in this module's test-jar. A service names the tables it
 * keeps, because not every service keeps all three:
 *
 * <pre>{@code
 * // a producer that only dedupes replay requests
 * assertThat(InboxSchemaGuard.problems(dataSource, PROCESSED_EVENT)).isEmpty();
 * // a projection consumer such as search
 * assertThat(InboxSchemaGuard.problems(dataSource, PROCESSED_EVENT, PROCESSED_AGGREGATE)).isEmpty();
 * }</pre>
 *
 * <p>processed_event is append-only (it is the record that an event was
 * applied; removing a row would let the event apply twice), so the guard
 * tries UPDATE, DELETE and TRUNCATE on it, inside a transaction it always
 * rolls back, and expects each to be refused. processed_aggregate and
 * outbox_catch_up are bookkeeping and are meant to change.
 */
public final class InboxSchemaGuard {

    /** The inbox-side tables a service may keep. */
    public enum Table {
        PROCESSED_EVENT(new TableSpec("processed_event",
                columns(
                        "consumer", "varchar(64) not null",
                        "event_id", "uuid not null",
                        "processed_at", "timestamptz not null default now()"),
                Set.of("PRIMARY KEY (consumer, event_id)"),
                """
                INSERT INTO processed_event (consumer, event_id, processed_at)
                VALUES ('schema-guard', gen_random_uuid(), now())""",
                "consumer = 'schema-guard'")),

        PROCESSED_AGGREGATE(new TableSpec("processed_aggregate",
                columns(
                        "consumer", "varchar(64) not null",
                        "aggregate_type", "varchar(64) not null",
                        "aggregate_id", "varchar(64) not null",
                        "last_seq", "bigint not null"),
                Set.of("PRIMARY KEY (consumer, aggregate_type, aggregate_id)"),
                null, null)),

        OUTBOX_CATCH_UP(new TableSpec("outbox_catch_up",
                columns(
                        "producer", "varchar(64) not null",
                        "request_id", "uuid not null",
                        "replayed_through", "bigint not null default 0",
                        "completed_through", "bigint",
                        "completed_at", "timestamptz"),
                Set.of("PRIMARY KEY (producer)"),
                null, null));

        private final TableSpec spec;

        Table(TableSpec spec) {
            this.spec = spec;
        }
    }

    private InboxSchemaGuard() {
    }

    /**
     * @return one sentence per difference from the agreed shape, for the
     *         named tables only; empty when they match
     * @throws IllegalArgumentException when no table is named -- an empty
     *         answer must mean "checked and fine", never "checked nothing"
     */
    public static List<String> problems(DataSource dataSource, Table... tables) {
        if (tables.length == 0) {
            throw new IllegalArgumentException("name at least one table to check");
        }
        List<String> problems = new ArrayList<>();
        for (Table table : tables) {
            problems.addAll(SchemaGuardSupport.check(dataSource, table.spec));
        }
        return problems;
    }
}
