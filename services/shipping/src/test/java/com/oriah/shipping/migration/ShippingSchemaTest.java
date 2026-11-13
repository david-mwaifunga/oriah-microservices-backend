package com.oriah.shipping.migration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import org.flywaydb.core.Flyway;
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
 * Shipping has its own database since Phase 7: Zambia's provinces and main towns (reference data), the options a parcel can go by, and a
 * dated price for each town and option that an admin enters and PUBLISHES (Q24: the customer knows the delivery price before paying).
 *
 * <p>The rules the schema holds even if the code forgets them: a town is switched off, never deleted; a price is a draft until it is published
 * and cannot be changed once it is (a new price replaces it, the old one is kept as history); one live price per town and option; prices are
 * never seeded (the client's own figures, entered by admin); the free-delivery threshold is off until set, and every change to it is kept.
 */
@Testcontainers
class ShippingSchemaTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));

    @BeforeAll
    static void migrate() {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").load().migrate();
    }

    // ---------------------------------------------------------------- the seed: reference data, never prices

    @Test
    @DisplayName("Zambia's ten provinces are there, each with its main towns, and Lusaka is a town of Lusaka Province")
    void provincesAndTownsAreSeeded() throws Exception {
        assertThat(stringOf("SELECT count(*)::text FROM province")).isEqualTo("10");
        assertThat(stringOf("SELECT string_agg(name, ',' ORDER BY name) FROM province"))
                .isEqualTo("Central,Copperbelt,Eastern,Luapula,Lusaka,Muchinga,North-Western,Northern,Southern,Western");
        assertThat(stringOf("SELECT count(*)::text FROM province p WHERE NOT EXISTS (SELECT 1 FROM town t WHERE t.province_id = p.id)"))
                .as("every province has at least one main town").isEqualTo("0");
        assertThat(stringOf("SELECT p.name FROM town t JOIN province p ON p.id = t.province_id WHERE t.slug = 'lusaka'")).isEqualTo("Lusaka");
        assertThat(stringOf("SELECT p.name FROM town t JOIN province p ON p.id = t.province_id WHERE t.slug = 'kapiri-mposhi'")).isEqualTo("Central");
        assertThat(stringOf("SELECT count(*)::text FROM town WHERE NOT active")).as("seeded towns start switched on").isEqualTo("0");
    }

    @Test
    @DisplayName("the options a parcel can go by are seeded (Oriah's own delivery, Yango, Platinum, EMS); prices are NEVER seeded")
    void optionsAreSeededPricesAreNot() throws Exception {
        // What the SEED made (other tests in this class add rows of their own).
        assertThat(stringOf("SELECT string_agg(code, ',' ORDER BY code) FROM delivery_option WHERE created_by = 'system:seed'")).isEqualTo("EMS,OWN_DELIVERY,PLATINUM,YANGO");
        assertThat(stringOf("SELECT count(*)::text FROM delivery_price WHERE created_by = 'system:seed'"))
                .as("a price is the client's own figure, entered and published by admin: the seed made none").isEqualTo("0");
        assertThat(stringOf("SELECT count(*)::text FROM delivery_option WHERE kind = 'COURIER' AND created_by = 'system:seed'")).isEqualTo("2");
    }

    @Test
    @DisplayName("the free-delivery threshold is off until someone sets it")
    void thresholdStartsOff() throws Exception {
        assertThat(stringOf("SELECT count(*)::text FROM delivery_settings")).isEqualTo("1");
        assertThat(stringOf("SELECT coalesce(free_delivery_threshold::text, 'off') FROM delivery_settings")).isEqualTo("off");
    }

    // ---------------------------------------------------------------- towns and options

    @Test
    @DisplayName("a town's name is its own within its province, and its slug is unique: no two towns can be the same place")
    void townsAreUnique() throws Exception {
        UUID province = UUID.fromString(stringOf("SELECT id::text FROM province WHERE name = 'Central'"));

        assertThatThrownBy(() -> insertTown(province, "Kabwe", "kabwe-2")).isInstanceOf(SQLException.class).hasMessageContaining("town_name_per_province");
        assertThatThrownBy(() -> insertTown(province, "Kabwe Two", "kabwe")).isInstanceOf(SQLException.class).hasMessageContaining("town_slug_key");
        insertTown(province, "Chibombo", "chibombo");
    }

    @Test
    @DisplayName("an option's code is an upper-case name of at most 40 characters, and its kind is one of the known ones")
    void optionsHaveTidyCodes() throws Exception {
        assertThatThrownBy(() -> execute("INSERT INTO delivery_option (code, name, kind, position, created_by) VALUES ('lower', 'Lower', 'COURIER', 9, 'system:test')"))
                .isInstanceOf(SQLException.class).hasMessageContaining("delivery_option_code_tidy");
        assertThatThrownBy(() -> execute("INSERT INTO delivery_option (code, name, kind, position, created_by) VALUES ('ROCKET', 'Rocket', 'ROCKET', 9, 'system:test')"))
                .isInstanceOf(SQLException.class).hasMessageContaining("delivery_option_kind_known");
        execute("INSERT INTO delivery_option (code, name, kind, position, created_by) VALUES ('JOURNEY_BUS', 'Journey Bus', 'BUS_PARCEL', 9, 'system:test')");
    }

    // ---------------------------------------------------------------- prices: draft, publish, supersede

    @Test
    @DisplayName("a price is a whole ngwee amount in Kwacha, offered to at least one payer, with ETAs that go together and do not run backwards")
    void aPriceIsWellFormed() throws Exception {
        UUID town = townId("ndola");

        assertThatThrownBy(() -> insertPrice(town, "YANGO", "-1.00", true, false, null, null, "DRAFT")).isInstanceOf(SQLException.class)
                .hasMessageContaining("delivery_price_amount_check");
        assertThatThrownBy(() -> insertPrice(town, "YANGO", "50.00", false, false, null, null, "DRAFT")).isInstanceOf(SQLException.class)
                .hasMessageContaining("delivery_price_someone_pays");
        assertThatThrownBy(() -> insertPrice(town, "YANGO", "50.00", true, false, 3, null, "DRAFT")).isInstanceOf(SQLException.class)
                .hasMessageContaining("delivery_price_eta_together");
        assertThatThrownBy(() -> insertPrice(town, "YANGO", "50.00", true, false, 5, 2, "DRAFT")).isInstanceOf(SQLException.class)
                .hasMessageContaining("delivery_price_eta_together");
        insertPrice(town, "YANGO", "50.00", true, true, 1, 2, "DRAFT");
    }

    @Test
    @DisplayName("a town has one live price per option and one draft at a time; earlier prices stay as history")
    void oneLivePricePerTownAndOption() throws Exception {
        UUID town = townId("kitwe");
        insertPrice(town, "EMS", "40.00", true, false, null, null, "DRAFT");
        assertThatThrownBy(() -> insertPrice(town, "EMS", "45.00", true, false, null, null, "DRAFT")).isInstanceOf(SQLException.class)
                .hasMessageContaining("delivery_price_one_draft");
        execute("UPDATE delivery_price SET status = 'PUBLISHED', published_at = now(), published_by = 'user:kc-maya' WHERE town_id = '" + town + "' AND option_code = 'EMS'");

        assertThatThrownBy(() -> insertPrice(town, "EMS", "60.00", true, false, null, null, "PUBLISHED")).isInstanceOf(SQLException.class)
                .hasMessageContaining("delivery_price_one_published");
        insertPrice(town, "EMS", "55.00", true, false, null, null, "DRAFT");          // a new draft beside the live price
        execute("UPDATE delivery_price SET status = 'SUPERSEDED', superseded_at = now() WHERE town_id = '" + town + "' AND option_code = 'EMS' AND status = 'PUBLISHED'");
        execute("UPDATE delivery_price SET status = 'PUBLISHED', published_at = now(), published_by = 'user:kc-maya' WHERE town_id = '" + town + "' AND option_code = 'EMS' AND status = 'DRAFT'");
        assertThat(stringOf("SELECT string_agg(status || ':' || amount, ',' ORDER BY amount) FROM delivery_price WHERE town_id = '" + town + "' AND option_code = 'EMS'"))
                .isEqualTo("SUPERSEDED:40.00,PUBLISHED:55.00");
    }

    @Test
    @DisplayName("a published price can never be changed or deleted, only superseded: what a customer was quoted is what was published")
    void aPublishedPriceIsHistory() throws Exception {
        UUID town = townId("mansa");
        insertPrice(town, "PLATINUM", "70.00", true, false, null, null, "DRAFT");
        execute("UPDATE delivery_price SET amount = 72.00 WHERE town_id = '" + town + "' AND option_code = 'PLATINUM'");          // a draft may be edited
        execute("UPDATE delivery_price SET status = 'PUBLISHED', published_at = now(), published_by = 'user:kc-maya' WHERE town_id = '" + town + "' AND option_code = 'PLATINUM'");

        assertThatThrownBy(() -> execute("UPDATE delivery_price SET amount = 10.00 WHERE town_id = '" + town + "' AND option_code = 'PLATINUM'"))
                .isInstanceOf(SQLException.class).hasMessageContaining("published price");
        assertThatThrownBy(() -> execute("DELETE FROM delivery_price WHERE town_id = '" + town + "' AND option_code = 'PLATINUM'"))
                .isInstanceOf(SQLException.class).hasMessageContaining("published price");
        execute("UPDATE delivery_price SET status = 'SUPERSEDED', superseded_at = now() WHERE town_id = '" + town + "' AND option_code = 'PLATINUM'");
        assertThatThrownBy(() -> execute("UPDATE delivery_price SET status = 'PUBLISHED', superseded_at = NULL WHERE town_id = '" + town + "' AND option_code = 'PLATINUM'"))
                .isInstanceOf(SQLException.class);
    }

    @Test
    @DisplayName("a draft is not a fact: it can be deleted")
    void aDraftCanBeDeleted() throws Exception {
        UUID town = townId("chipata");
        insertPrice(town, "OWN_DELIVERY", "30.00", true, false, null, null, "DRAFT");

        execute("DELETE FROM delivery_price WHERE town_id = '" + town + "' AND option_code = 'OWN_DELIVERY'");

        assertThat(stringOf("SELECT count(*)::text FROM delivery_price WHERE town_id = '" + town + "'")).isEqualTo("0");
    }

    // ---------------------------------------------------------------- settings

    @Test
    @DisplayName("there is one settings row; the threshold, when set, is above zero; every change to it is kept and can never be edited")
    void settings() throws Exception {
        assertThatThrownBy(() -> execute("INSERT INTO delivery_settings (id, updated_by) VALUES (2, 'system:test')")).isInstanceOf(SQLException.class)
                .hasMessageContaining("delivery_settings_one_row");
        assertThatThrownBy(() -> execute("UPDATE delivery_settings SET free_delivery_threshold = 0")).isInstanceOf(SQLException.class)
                .hasMessageContaining("delivery_settings_threshold_positive");

        execute("INSERT INTO delivery_settings_change (threshold_before, threshold_after, changed_by, reason) VALUES (NULL, 1500.00, 'user:kc-maya', 'trial')");
        assertThatThrownBy(() -> execute("UPDATE delivery_settings_change SET reason = 'x'")).isInstanceOf(SQLException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> execute("DELETE FROM delivery_settings_change")).isInstanceOf(SQLException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> execute("TRUNCATE delivery_settings_change")).isInstanceOf(SQLException.class).hasMessageContaining("append-only");
    }

    // ---------------------------------------------------------------- helpers

    private static void insertTown(UUID province, String name, String slug) throws Exception {
        execute("INSERT INTO town (id, province_id, name, slug, created_by) VALUES ('%s', '%s', '%s', '%s', 'system:test')".formatted(UUID.randomUUID(), province, name, slug));
    }

    private static void insertPrice(UUID town, String option, String amount, boolean oriah, boolean recipient, Integer etaMin, Integer etaMax, String status) throws Exception {
        boolean published = status.equals("PUBLISHED");
        execute("""
                INSERT INTO delivery_price (id, town_id, option_code, amount, allows_oriah_collects, allows_recipient_pays, eta_days_min, eta_days_max, status,
                                            created_by, published_at, published_by)
                VALUES ('%s', '%s', '%s', %s, %s, %s, %s, %s, '%s', 'user:kc-maya', %s, %s)""".formatted(UUID.randomUUID(), town, option, amount, oriah, recipient,
                etaMin == null ? "NULL" : etaMin, etaMax == null ? "NULL" : etaMax, status, published ? "now()" : "NULL", published ? "'user:kc-maya'" : "NULL"));
    }

    private static UUID townId(String slug) throws Exception {
        return UUID.fromString(stringOf("SELECT id::text FROM town WHERE slug = '" + slug + "'"));
    }

    private static void execute(String sql) throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.executeUpdate(sql);
        }
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
