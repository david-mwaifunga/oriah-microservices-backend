package com.oriah.payment.schema;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V5 adds "one intent per order that is pending, being charged or paid" as a
 * unique index. A database that already broke that rule would refuse the
 * index and the service would not start, so V5 settles what is there first,
 * the same way every time:
 *
 * <ul>
 *   <li>an order with a paid intent keeps it, and its pending ones fail;</li>
 *   <li>an order with only pending intents keeps the newest, and the older
 *       ones fail;</li>
 *   <li>each one that fails says why: the migration superseded it, by which
 *       intent, and that no charge was recorded for it.</li>
 * </ul>
 *
 * Failing a PENDING intent moves no money. The reason does not claim it was
 * never charged: V4 could charge and then roll the intent back to PENDING,
 * leaving no trace, so the migration states only what is on record.
 * Two PAID intents for one order is different -- the shopper may have been
 * charged twice -- and no migration can decide which charge stands, so V5
 * stops, names the order, and leaves the database as it was for a person to
 * sort out.
 *
 * <p>Each test migrates a fresh database to V4 (the schema before this
 * change), writes the rows a running V4 service could have written, then
 * runs V5 on them.
 */
@Testcontainers
class V5MigrationTest {

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));

    private static final Instant OCT_1 = Instant.parse("2026-10-01T08:00:00Z");
    private static final Instant OCT_2 = Instant.parse("2026-10-02T08:00:00Z");
    private static final Instant OCT_3 = Instant.parse("2026-10-03T08:00:00Z");

    @Test
    @DisplayName("duplicates are settled: the paid or newest intent stays, the others fail with a reason")
    void duplicatesAreSettledDeterministically() {
        DataSource db = freshDatabaseAtV4();
        JdbcTemplate jdbc = new JdbcTemplate(db);

        // Two pending intents for one order: the newer one stays.
        UUID orderA = UUID.randomUUID();
        UUID a1 = intent(jdbc, orderA, "PENDING", OCT_1);
        UUID a2 = intent(jdbc, orderA, "PENDING", OCT_2);

        // A paid intent and a newer pending one: the PAID one stays, however old.
        UUID orderB = UUID.randomUUID();
        UUID b1 = intent(jdbc, orderB, "SUCCEEDED", OCT_1);
        UUID b2 = intent(jdbc, orderB, "PENDING", OCT_3);

        // Already within the rule: nothing changes.
        UUID orderC = UUID.randomUUID();
        UUID c1 = intent(jdbc, orderC, "PENDING", OCT_1);
        UUID orderD = UUID.randomUUID();
        UUID d1 = intent(jdbc, orderD, "FAILED", OCT_1);
        UUID d2 = intent(jdbc, orderD, "PENDING", OCT_2);

        // Same moment: the tie is broken by id, so a re-run on a copy of the
        // data settles it the same way.
        UUID orderE = UUID.randomUUID();
        UUID e1 = intent(jdbc, orderE, UUID.fromString("00000000-0000-0000-0000-00000000000e"), "PENDING", OCT_1);
        UUID e2 = intent(jdbc, orderE, UUID.fromString("ffffffff-0000-0000-0000-00000000000e"), "PENDING", OCT_1);

        // V2 left created_at nullable: an intent with no date counts as the oldest.
        UUID orderF = UUID.randomUUID();
        UUID f1 = intent(jdbc, orderF, "PENDING", null);
        UUID f2 = intent(jdbc, orderF, "PENDING", OCT_1);

        // An Idempotency-Key stored by V4 is a completed one: it has its response.
        jdbc.update("INSERT INTO idempotency_keys (key, intent_id, response_json) VALUES ('v4-key', ?, '{}')", b1);

        migrateToLatest(db);

        assertThat(statusOf(jdbc, a1)).isEqualTo("FAILED");
        assertThat(reasonOf(jdbc, a1)).isEqualTo(supersededBy(a2));
        assertThat(statusOf(jdbc, a2)).isEqualTo("PENDING");

        assertThat(statusOf(jdbc, b1)).isEqualTo("SUCCEEDED");
        assertThat(statusOf(jdbc, b2)).isEqualTo("FAILED");
        assertThat(reasonOf(jdbc, b2)).isEqualTo(supersededBy(b1));

        assertThat(statusOf(jdbc, c1)).isEqualTo("PENDING");
        assertThat(reasonOf(jdbc, c1)).isNull();
        assertThat(statusOf(jdbc, d1)).isEqualTo("FAILED");
        assertThat(statusOf(jdbc, d2)).isEqualTo("PENDING");

        assertThat(statusOf(jdbc, e1)).isEqualTo("FAILED");
        assertThat(reasonOf(jdbc, e1)).isEqualTo(supersededBy(e2));
        assertThat(statusOf(jdbc, e2)).isEqualTo("PENDING");

        assertThat(statusOf(jdbc, f1)).isEqualTo("FAILED");
        assertThat(statusOf(jdbc, f2)).isEqualTo("PENDING");

        // No charge was ever recorded for a superseded intent, so confirmed_at
        // stays empty rather than claiming a decision nobody saw. The row says
        // when and by what it was changed.
        Map<String, Object> superseded = jdbc.queryForMap(
                "SELECT confirmed_at, updated_by, updated_at FROM payment_intents WHERE id = ?", a1);
        assertThat(superseded.get("confirmed_at")).isNull();
        assertThat(superseded.get("updated_by")).isEqualTo("flyway:V5");
        assertThat(superseded.get("updated_at")).isNotNull();

        assertThat(jdbc.queryForObject("SELECT state FROM idempotency_keys WHERE key = 'v4-key'", String.class))
                .isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_intents WHERE version <> 0", Long.class))
                .isZero();
    }

    @Test
    @DisplayName("two PAID intents for one order stop the migration, name the order, and change nothing")
    void twoPaidIntentsStopTheMigration() {
        DataSource db = freshDatabaseAtV4();
        JdbcTemplate jdbc = new JdbcTemplate(db);
        UUID order = UUID.randomUUID();
        UUID first = intent(jdbc, order, "SUCCEEDED", OCT_1);
        UUID second = intent(jdbc, order, "SUCCEEDED", OCT_2);
        UUID pending = intent(jdbc, order, "PENDING", OCT_3);

        assertThatThrownBy(() -> migrateToLatest(db))
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining(order.toString())
                .hasMessageContaining("2 SUCCEEDED");

        // Postgres runs the whole migration in one transaction, so the stop
        // leaves no half-applied V5 behind: still V4, every row untouched.
        assertThat(Flyway.configure().dataSource(db).locations("classpath:db/migration").load()
                .info().current().getVersion().getVersion()).isEqualTo("4");
        assertThat(statusOf(jdbc, first)).isEqualTo("SUCCEEDED");
        assertThat(statusOf(jdbc, second)).isEqualTo("SUCCEEDED");
        assertThat(statusOf(jdbc, pending)).isEqualTo("PENDING");
    }

    // ------------------------------------------------------------------

    /**
     * Says only what is on record. It does not say "never charged": V4
     * charged inside the transaction that recorded the result, so a failure
     * after the charge rolled the intent back to PENDING, and V4's fake
     * gateway kept no record -- a PENDING V4 intent may have been charged, and
     * nothing can tell.
     */
    private static String supersededBy(UUID survivor) {
        return "Superseded by payment intent " + survivor
                + " when V5 allowed one live intent per order; no charge was recorded for it";
    }

    private static DataSource freshDatabaseAtV4() {
        String name = "v5_" + UUID.randomUUID().toString().replace("-", "");
        new JdbcTemplate(dataSource(POSTGRES.getDatabaseName())).execute("CREATE DATABASE " + name);
        DataSource db = dataSource(name);
        Flyway.configure().dataSource(db).locations("classpath:db/migration").target("4").load().migrate();
        return db;
    }

    private static void migrateToLatest(DataSource db) {
        Flyway.configure().dataSource(db).locations("classpath:db/migration").load().migrate();
    }

    private static DataSource dataSource(String database) {
        String url = POSTGRES.getJdbcUrl().replace("/" + POSTGRES.getDatabaseName(), "/" + database);
        return new DriverManagerDataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static UUID intent(JdbcTemplate jdbc, UUID order, String status, Instant createdAt) {
        return intent(jdbc, order, UUID.randomUUID(), status, createdAt);
    }

    /** A row exactly as V4's PaymentIntent wrote it: no version, no charge or gateway reference. */
    private static UUID intent(JdbcTemplate jdbc, UUID order, UUID id, String status, Instant createdAt) {
        jdbc.update("""
                INSERT INTO payment_intents (id, order_id, keycloak_id, amount, currency, status,
                                             failure_reason, created_at, confirmed_at)
                VALUES (?, ?, 'kc-v4', 4500.00, 'ZMW', ?, NULL, ?, ?)""",
                id, order, status,
                createdAt == null ? null : Timestamp.from(createdAt),
                "SUCCEEDED".equals(status) ? Timestamp.from(OCT_1) : null);
        return id;
    }

    private static String statusOf(JdbcTemplate jdbc, UUID id) {
        return jdbc.queryForObject("SELECT status FROM payment_intents WHERE id = ?", String.class, id);
    }

    private static String reasonOf(JdbcTemplate jdbc, UUID id) {
        return jdbc.queryForObject("SELECT failure_reason FROM payment_intents WHERE id = ?", String.class, id);
    }
}
