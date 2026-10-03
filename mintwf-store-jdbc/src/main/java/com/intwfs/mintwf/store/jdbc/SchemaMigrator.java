package com.intwfs.mintwf.store.jdbc;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;

/**
 * Brings the database schema up to date by running the numbered scripts in {@code schema/} that have not run yet.
 *
 * <p>The CLI and the worker may both open a new database at once. If a script fails because another process applied
 * it first, the migrator notices the recorded version and carries on.
 */
final class SchemaMigrator {

    /** The scripts in order. Script {@code n} brings the schema to version {@code n}. */
    private static final List<String> SCRIPTS = List.of("V1__create_tables.sql", "V2__create_node_instance.sql",
            "V3__add_instance_caller.sql");

    private final DataSource dataSource;

    /** Returns the schema version the scripts bring a database to. */
    static int latestVersion() {
        return SCRIPTS.size();
    }

    SchemaMigrator(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    void migrate() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE IF NOT EXISTS mintwf_schema ("
                        + "version INT NOT NULL PRIMARY KEY, applied_at TIMESTAMP(9) WITH TIME ZONE NOT NULL)");
            }
            for (int version = currentVersion(connection) + 1; version <= SCRIPTS.size(); version++) {
                apply(connection, version);
            }
        }
    }

    private void apply(Connection connection, int version) throws SQLException {
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            for (String sql : statements(SCRIPTS.get(version - 1))) {
                statement.execute(sql);
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO mintwf_schema (version, applied_at) VALUES (?, ?)")) {
                insert.setInt(1, version);
                insert.setObject(2, OffsetDateTime.now(ZoneOffset.UTC));
                insert.executeUpdate();
            }
            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            if (currentVersion(connection) < version) {
                throw e;
            }
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private static int currentVersion(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT MAX(version) FROM mintwf_schema")) {
            result.next();
            return result.getInt(1);
        }
    }

    /** Splits a script into statements at semicolons that end a line, dropping comment lines. */
    private static List<String> statements(String script) {
        String text;
        try (InputStream in = SchemaMigrator.class.getResourceAsStream("schema/" + script)) {
            if (in == null) {
                throw new IllegalStateException("migration script " + script + " is missing");
            }
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read migration script " + script, e);
        }
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : text.split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("--")) {
                continue;
            }
            current.append(line).append('\n');
            if (trimmed.endsWith(";")) {
                statements.add(current.substring(0, current.lastIndexOf(";")));
                current.setLength(0);
            }
        }
        if (!current.toString().isBlank()) {
            statements.add(current.toString());
        }
        return statements;
    }
}
