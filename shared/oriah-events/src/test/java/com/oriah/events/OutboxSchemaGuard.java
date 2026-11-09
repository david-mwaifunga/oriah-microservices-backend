package com.oriah.events;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.sql.DataSource;

import com.oriah.events.SchemaGuardSupport.TableSpec;

import static com.oriah.events.SchemaGuardSupport.columns;

/**
 * Proves a producing service's own migration created the outbox and the
 * relay cursor exactly as agreed (the reference SQL in this module's
 * db/oriah-events), pattern of oriah-money's MoneyEdgeGuard.
 *
 * <p>Shipped in this module's test-jar. Each producing service runs it
 * against its own migrated Testcontainers database:
 *
 * <pre>{@code
 * assertThat(OutboxSchemaGuard.problems(dataSource)).isEmpty();
 * }</pre>
 *
 * <p>It checks every column (type, length, nullability, default, identity),
 * that outbox_id's sequence hands out ids one at a time, every constraint,
 * that the cursor has its 'main' row, and -- by actually
 * trying, inside a transaction it always rolls back -- that the outbox
 * refuses UPDATE, DELETE and TRUNCATE. The outbox is the service's
 * permanent record of what happened; if a row could be edited or removed,
 * a replay would no longer tell the truth.
 */
public final class OutboxSchemaGuard {

    static final TableSpec OUTBOX = new TableSpec("outbox",
            columns(
                    "outbox_id", "bigint not null identity always",
                    "event_id", "uuid not null",
                    "aggregate_type", "varchar(64) not null",
                    "aggregate_id", "varchar(64) not null",
                    "seq", "bigint not null",
                    "event_type", "varchar(128) not null",
                    "schema_version", "int not null",
                    "topic", "varchar(128) not null",
                    // Never defaulted: a fact keyed late carries its true, earlier date.
                    "occurred_at", "timestamptz not null",
                    "recorded_at", "timestamptz not null default now()",
                    "actor", "varchar(128) not null",
                    // json, not jsonb: json keeps the text verbatim, so the
                    // bytes stored are the bytes published.
                    "payload", "json not null"),
            Set.of("PRIMARY KEY (outbox_id)",
                    "UNIQUE (event_id)",
                    "UNIQUE (aggregate_type, aggregate_id, seq)"),
            """
            INSERT INTO outbox (event_id, aggregate_type, aggregate_id, seq, event_type,
                                schema_version, topic, occurred_at, recorded_at, actor, payload)
            VALUES (gen_random_uuid(), 'schema-guard', 'probe', 1, 'schema-guard.probe',
                    1, 'schema-guard', now(), now(), 'system:schema-guard', '{}')""",
            "aggregate_type = 'schema-guard'");

    static final TableSpec RELAY_CURSOR = new TableSpec("outbox_relay_cursor",
            columns(
                    "relay", "varchar(64) not null",
                    "last_outbox_id", "bigint not null",
                    "updated_at", "timestamptz not null"),
            Set.of("PRIMARY KEY (relay)"),
            null, null);

    private OutboxSchemaGuard() {
    }

    /**
     * How the outbox_id sequence must hand out ids: from 1, one at a time,
     * upwards, never wrapping. The relay sends only ids above its cursor,
     * which starts at 0, so an id of 0 or below is never sent. And its
     * "published up to N" is safe only because ids follow commit order,
     * which they do only if each id is taken from the sequence under the
     * append lock: with CACHE above 1 each pooled connection holds back its
     * own block of ids (connection 2 commits 21 while connection 1 still has
     * 2 to hand out), and the relay, already past 21, never sends row 2.
     */
    static final String OUTBOX_ID_SEQUENCE = "START 1 MINVALUE 1 INCREMENT 1 CACHE 1 NO CYCLE";

    /** @return one sentence per difference from the agreed shape; empty when it matches */
    public static List<String> problems(DataSource dataSource) {
        List<String> problems = new ArrayList<>(SchemaGuardSupport.check(dataSource, OUTBOX));
        String sequence = SchemaGuardSupport.sequenceOptions(dataSource, "outbox", "outbox_id");
        if (sequence != null && !sequence.equals(OUTBOX_ID_SEQUENCE)) {
            problems.add("outbox: outbox_id's sequence is " + sequence + ", expected " + OUTBOX_ID_SEQUENCE
                    + " -- the relay sends only ids above its cursor, which starts at 0, and only safely while "
                    + "ids follow commit order; otherwise it skips a row for good");
        }
        List<String> cursor = SchemaGuardSupport.check(dataSource, RELAY_CURSOR);
        problems.addAll(cursor);
        if (cursor.isEmpty() && SchemaGuardSupport.count(dataSource,
                "SELECT count(*) FROM outbox_relay_cursor WHERE relay = 'main'") == 0) {
            problems.add("outbox_relay_cursor: no 'main' row; seed ('main', 0) in the migration that creates the table");
        } else if (cursor.isEmpty() && problems.stream().noneMatch(p -> p.equals("outbox: table is missing"))) {
            // The relay only ever moves the cursor to the id of a row it has
            // just sent, so a correct cursor is 0 or an existing row's id --
            // whatever other tests have published before the guard runs.
            long seeded = SchemaGuardSupport.count(dataSource,
                    "SELECT last_outbox_id FROM outbox_relay_cursor WHERE relay = 'main'");
            if (seeded != 0 && SchemaGuardSupport.count(dataSource,
                    "SELECT count(*) FROM outbox WHERE outbox_id = " + seeded) == 0) {
                problems.add("outbox_relay_cursor: 'main' is at " + seeded + ", which is neither 0 nor the id of "
                        + "an outbox row -- the relay only ever moves it to a row it has sent, so it was seeded "
                        + "wrong and every row up to " + seeded + " would never be published; seed ('main', 0)");
            }
        }
        return problems;
    }
}
