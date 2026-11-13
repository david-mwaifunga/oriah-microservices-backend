package com.oriah.inventory.migration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V10 lets stock be set aside: a packaging row gets a {@code committed} count, a product's set-aside boxes are a record of their own
 * (one per product), and what inventory knows of each product (its kind, whether it is on sale, its default box, how often it was
 * unpublished) is kept so those boxes can be let go when the product no longer needs them.
 *
 * <p>Driven through Flyway directly, as the other migration tests are, because the rows that matter are the ones that exist BEFORE the
 * migration: stock already received must come through with nothing set aside, and still satisfy every rule.
 */
@Testcontainers
class V10CommitmentsTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));

    private static final UUID RING = UUID.fromString("a1111111-1111-4111-8111-111111111111");
    private static final UUID BOX = UUID.fromString("b2222222-2222-4222-8222-222222222222");

    @BeforeAll
    static void migrateWithStockFromBeforeV10() throws Exception {
        migrateTo(MigrationVersion.fromVersion("9"));
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.executeUpdate("INSERT INTO inventory (variant_id, on_hand, reserved, kind) VALUES ('%s', 7, 2, 'SELLABLE'), ('%s', 200, 5, 'PACKAGING')"
                    .formatted(RING, BOX));
        }
        migrateTo(MigrationVersion.LATEST);
    }

    @Test
    @DisplayName("stock received before V10 has nothing set aside, and keeps its counts")
    void existingStockHasNothingCommitted() throws Exception {
        assertThat(stringOf("SELECT committed::text FROM inventory WHERE variant_id = '" + BOX + "'")).isEqualTo("0");
        assertThat(stringOf("SELECT on_hand::text || '/' || reserved::text FROM inventory WHERE variant_id = '" + BOX + "'")).isEqualTo("200/5");
        assertThat(stringOf("SELECT committed::text FROM inventory WHERE variant_id = '" + RING + "'")).isEqualTo("0");
    }

    @Test
    @DisplayName("what is reserved and what is set aside can never exceed what is held: the database refuses it")
    void freeCannotGoNegative() {
        assertThatThrownBy(() -> execute("UPDATE inventory SET committed = 196 WHERE variant_id = '" + BOX + "'"))
                .isInstanceOf(SQLException.class).hasMessageContaining("inventory_committed_within_stock");
        assertThatThrownBy(() -> execute("UPDATE inventory SET committed = -1 WHERE variant_id = '" + BOX + "'"))
                .isInstanceOf(SQLException.class).hasMessageContaining("inventory_committed_nonnegative");
    }

    @Test
    @DisplayName("only packaging can have anything set aside: a ring row refuses it, a box row accepts it")
    void onlyPackagingIsSetAside() throws Exception {
        assertThatThrownBy(() -> execute("UPDATE inventory SET committed = 1 WHERE variant_id = '" + RING + "'"))
                .isInstanceOf(SQLException.class).hasMessageContaining("inventory_committed_only_packaging");
        execute("UPDATE inventory SET committed = 10 WHERE variant_id = '" + BOX + "'");
        execute("UPDATE inventory SET committed = 0 WHERE variant_id = '" + BOX + "'");
    }

    @Test
    @DisplayName("a product has at most one commitment, of at least one box, on one box")
    void oneCommitmentPerProduct() throws Exception {
        UUID product = UUID.randomUUID();
        insertCommitment(product, 5);

        assertThatThrownBy(() -> insertCommitment(product, 3)).isInstanceOf(SQLException.class).hasMessageContaining("box_commitment_product_id_key");
        assertThatThrownBy(() -> insertCommitment(UUID.randomUUID(), 0)).isInstanceOf(SQLException.class).hasMessageContaining("box_commitment_quantity_positive");
    }

    @Test
    @DisplayName("the change history of the boxes is append-only: update, delete and truncate are refused, and a kind it does not know is too")
    void historyIsAppendOnly() throws Exception {
        execute("""
                INSERT INTO box_commitment_change (commitment_id, product_id, box_variant_id, kind, qty_delta, quantity_after, occurred_at, actor)
                VALUES ('%s', '%s', '%s', 'ALLOCATE', 5, 5, now(), 'user:kc-maya')""".formatted(UUID.randomUUID(), UUID.randomUUID(), BOX));

        assertThatThrownBy(() -> execute("UPDATE box_commitment_change SET qty_delta = 1")).isInstanceOf(SQLException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> execute("DELETE FROM box_commitment_change")).isInstanceOf(SQLException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> execute("TRUNCATE box_commitment_change")).isInstanceOf(SQLException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> execute("""
                INSERT INTO box_commitment_change (commitment_id, product_id, box_variant_id, kind, qty_delta, quantity_after, occurred_at, actor)
                VALUES ('%s', '%s', '%s', 'WISH', 5, 5, now(), 'user:kc-maya')""".formatted(UUID.randomUUID(), UUID.randomUUID(), BOX)))
                .isInstanceOf(SQLException.class).hasMessageContaining("box_commitment_change_kind_known");
    }

    @Test
    @DisplayName("what is known of a product: its kind and status are checked, an unpublish count never goes below zero, and a product is there once")
    void productState() throws Exception {
        UUID product = UUID.randomUUID();
        execute("INSERT INTO product_state (product_id, kind, status, unpublish_count) VALUES ('%s', 'SELLABLE', 'DRAFT', 0)".formatted(product));

        assertThatThrownBy(() -> execute("INSERT INTO product_state (product_id, kind, status) VALUES ('%s', 'SELLABLE', 'DRAFT')".formatted(product)))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> execute("INSERT INTO product_state (product_id, kind, status) VALUES ('%s', 'GADGET', 'DRAFT')".formatted(UUID.randomUUID())))
                .isInstanceOf(SQLException.class).hasMessageContaining("product_state_kind_known");
        assertThatThrownBy(() -> execute("UPDATE product_state SET unpublish_count = -1 WHERE product_id = '" + product + "'"))
                .isInstanceOf(SQLException.class).hasMessageContaining("product_state_unpublish_count_nonnegative");
    }

    private static void insertCommitment(UUID product, int quantity) throws Exception {
        execute("""
                INSERT INTO box_commitment (id, product_id, box_variant_id, quantity, unpublish_count, committed_at, committed_by)
                VALUES ('%s', '%s', '%s', %d, 0, now(), 'user:kc-maya')""".formatted(UUID.randomUUID(), product, BOX, quantity));
    }

    private static void execute(String sql) throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.executeUpdate(sql);
        }
    }

    private static void migrateTo(MigrationVersion version) {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .target(version)
                .load()
                .migrate();
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static String stringOf(String sql) throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }
}
