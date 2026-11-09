package com.oriah.events;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A new, empty database in a running Postgres container. Every service owns
 * its own database, so a test that runs a producer and two consumers gives
 * each one of these -- on one container, so it starts once.
 */
record FreshDatabase(String url, String username, String password) {

    static FreshDatabase in(PostgreSQLContainer postgres) {
        String name = "svc_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + name);
        } catch (SQLException e) {
            throw new IllegalStateException("could not create database " + name, e);
        }
        String url = "jdbc:postgresql://" + postgres.getHost() + ":"
                + postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT) + "/" + name;
        return new FreshDatabase(url, postgres.getUsername(), postgres.getPassword());
    }

    String[] properties() {
        return new String[] {
                "spring.datasource.url=" + url,
                "spring.datasource.username=" + username,
                "spring.datasource.password=" + password};
    }
}
