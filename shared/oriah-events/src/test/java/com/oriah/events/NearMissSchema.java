package com.oriah.events;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

import org.postgresql.Driver;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Loads guard-fixtures/near_miss.sql into its own Postgres schema and hands
 * back a DataSource whose connections see only that schema -- the same
 * database the reference SQL lives in, but a deliberately wrong copy of it.
 */
final class NearMissSchema {

    static final String SCHEMA = "near_miss";

    private NearMissSchema() {
    }

    static DataSource load(PostgreSQLContainer postgres) throws SQLException, IOException {
        DataSource dataSource = dataSource(postgres);
        String fixture = read("/guard-fixtures/near_miss.sql");
        try (Connection connection = dataSource(postgres, "public").getConnection();
             Statement statement = connection.createStatement()) {
            // The Postgres driver runs a multi-statement string in one go and
            // understands the $$ quoting of the trigger function.
            statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE; "
                    + "CREATE SCHEMA " + SCHEMA + "; SET search_path TO " + SCHEMA + "; " + fixture);
        }
        return dataSource;
    }

    static DataSource dataSource(PostgreSQLContainer postgres) {
        return dataSource(postgres, SCHEMA);
    }

    private static DataSource dataSource(PostgreSQLContainer postgres, String schema) {
        String url = postgres.getJdbcUrl();
        url += (url.contains("?") ? "&" : "?") + "currentSchema=" + schema;
        return new SimpleDriverDataSource(new Driver(), url, postgres.getUsername(), postgres.getPassword());
    }

    private static String read(String resource) throws IOException {
        try (InputStream in = NearMissSchema.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("missing test resource " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
