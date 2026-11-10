package com.oriah.procurement.schema;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.oriah.procurement.TestcontainersConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The suppliers table as the database itself enforces it, with raw SQL and no
 * entity: a rule that lives only in Java is one bad migration or one direct
 * insert away from being false.
 *
 * <p>Constraints are pinned <strong>by name</strong>, because the service maps one
 * of them ({@code uq_suppliers_name_live}) to a 409 and must not mistake any
 * other integrity failure for it.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false"
})
class SupplierSchemaTest {

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM suppliers");
    }

    private void insert(String name, String country, boolean deleted) {
        jdbc.update("INSERT INTO suppliers (id, name, country, is_deleted) VALUES (?, ?, ?, ?)",
                UUID.randomUUID(), name, country, deleted);
    }

    @Test
    @DisplayName("the table has the columns, types and nullability the aggregate relies on")
    void columns() {
        Map<String, Map<String, Object>> columns = new java.util.LinkedHashMap<>();
        jdbc.queryForList("""
                SELECT column_name, data_type, character_maximum_length, is_nullable, column_default
                FROM information_schema.columns WHERE table_name = 'suppliers' ORDER BY ordinal_position""")
                .forEach(row -> columns.put((String) row.get("column_name"), row));

        assertThat(columns.keySet()).containsExactlyInAnyOrder(
                "id", "name", "country", "contact_name", "contact_details", "payment_notes", "active", "version",
                "created_at", "updated_at", "created_by", "updated_by", "is_deleted");
        assertThat(columns.get("id")).containsEntry("data_type", "uuid").containsEntry("is_nullable", "NO");
        assertThat(columns.get("name")).containsEntry("data_type", "character varying")
                .containsEntry("character_maximum_length", 120).containsEntry("is_nullable", "NO");
        // varchar, not char: Hibernate maps a String to varchar, and PostgreSQL's
        // char(n) is bpchar, which fails ddl-auto=validate.
        assertThat(columns.get("country")).containsEntry("data_type", "character varying")
                .containsEntry("character_maximum_length", 2).containsEntry("is_nullable", "NO");
        assertThat(columns.get("contact_name")).containsEntry("character_maximum_length", 120)
                .containsEntry("is_nullable", "YES");
        assertThat(columns.get("contact_details")).containsEntry("character_maximum_length", 500)
                .containsEntry("is_nullable", "YES");
        assertThat(columns.get("payment_notes")).containsEntry("character_maximum_length", 500)
                .containsEntry("is_nullable", "YES");
        assertThat(columns.get("active")).containsEntry("data_type", "boolean").containsEntry("is_nullable", "NO");
        assertThat(columns.get("version")).containsEntry("data_type", "bigint").containsEntry("is_nullable", "NO");
        assertThat(columns.get("created_at")).containsEntry("data_type", "timestamp with time zone")
                .containsEntry("is_nullable", "NO");
        assertThat(columns.get("updated_at")).containsEntry("data_type", "timestamp with time zone")
                .containsEntry("is_nullable", "NO");
        assertThat(columns.get("created_by")).containsEntry("character_maximum_length", 64);
        assertThat(columns.get("is_deleted")).containsEntry("data_type", "boolean").containsEntry("is_nullable", "NO");
    }

    @Test
    @DisplayName("a row inserted with only what it must have gets the defaults: active, version 0, not deleted, stamped")
    void defaults() {
        insert("Fycargo", "CN", false);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT active, version, is_deleted, created_at, updated_at FROM suppliers");
        assertThat(row).containsEntry("active", true).containsEntry("version", 0L)
                .containsEntry("is_deleted", false);
        assertThat(row.get("created_at")).isNotNull();
        assertThat(row.get("updated_at")).isNotNull();
    }

    @Test
    @DisplayName("a country is two capital letters: the check constraint ck_suppliers_country refuses anything else")
    void countryIsTwoCapitalLetters() {
        for (String bad : List.of("zm", "Zm", "Z", "1A", "Z ", "  ")) {
            assertThatThrownBy(() -> insert("Supplier " + bad, bad, false))
                    .as("country '%s'", bad)
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("ck_suppliers_country");
        }
        // Too long for varchar(2) is refused before the check is reached.
        assertThatThrownBy(() -> insert("Too long", "ZMB", false))
                .isInstanceOf(DataIntegrityViolationException.class);

        insert("Fine", "ZM", false);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM suppliers", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("two live suppliers cannot share a name, in any letter case: uq_suppliers_name_live")
    void liveNamesAreUniqueIgnoringCase() {
        insert("Fycargo", "CN", false);

        assertThatThrownBy(() -> insert("Fycargo", "CN", false))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_suppliers_name_live");
        assertThatThrownBy(() -> insert("FYCARGO", "AE", false))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_suppliers_name_live");
        // A different name is fine, and so is the same name with different letters inside it.
        insert("Fycargo Guangzhou", "CN", false);
    }

    @Test
    @DisplayName("deleting a supplier frees its name, and deleted rows never collide with each other")
    void aDeletedSupplierFreesItsName() {
        insert("Fycargo", "CN", true);
        insert("Fycargo", "CN", true);
        insert("fycargo", "CN", false);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM suppliers WHERE NOT is_deleted", Integer.class))
                .isEqualTo(1);

        // Deleting the live one makes room for another live one.
        jdbc.update("UPDATE suppliers SET is_deleted = TRUE WHERE NOT is_deleted");
        insert("FYCARGO", "CN", false);
    }

    @Test
    @DisplayName("the unique index is partial (live rows only) and case-folded, so the rule above is the index's own")
    void theIndexIsPartialAndCaseFolded() {
        String definition = jdbc.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE tablename = 'suppliers' AND indexname = 'uq_suppliers_name_live'",
                String.class);

        assertThat(definition).startsWith("CREATE UNIQUE INDEX")
                .contains("lower(")
                .containsIgnoringCase("WHERE");
    }

    @Test
    @DisplayName("a name is required and an active flag cannot be null")
    void requiredColumns() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO suppliers (id, name, country) VALUES (?, NULL, 'ZM')", UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO suppliers (id, name, country, active) VALUES (?, 'X', 'ZM', NULL)", UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
