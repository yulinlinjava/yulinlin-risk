package com.yulinlin.riskapp.schema;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Adds the H2 constraints not expressible by the ORM's schema annotations. */
public final class RiskSchemaConstraints {
    private static final Logger LOG = LoggerFactory.getLogger(RiskSchemaConstraints.class);

    private final Connection connection;
    private final DatabaseMetaData metadata;
    private final String catalog;
    private final String schema;

    private RiskSchemaConstraints(Connection connection) throws SQLException {
        this.connection = connection;
        this.metadata = connection.getMetaData();
        this.catalog = connection.getCatalog();
        this.schema = connection.getSchema();
    }

    /** Checks every startup so an interrupted first initialization can safely resume. */
    public static void initialize(Connection connection) throws SQLException {
        new RiskSchemaConstraints(connection).initialize();
    }

    private void initialize() throws SQLException {
        required("RISK_ADMIN_ACCOUNT", "ID", "USERNAME", "PASSWORD_HASH", "CREATED_AT");
        primary("RISK_ADMIN_ACCOUNT", "ID");

        required("RISK_PROJECT", "ID", "NAME", "KEY_DIGEST", "CREATED_AT", "BLACKLIST_VERSION");
        primary("RISK_PROJECT", "ID");
        unique("RISK_PROJECT", "UQ_RISK_PROJECT_KEY_DIGEST", "KEY_DIGEST");
        defaultZero("RISK_PROJECT", "BLACKLIST_VERSION");

        required("RISK_EVENT", "ID", "PROJECT_ID", "EVENT_ID", "USER_ID", "BEHAVIOR_TYPE",
                "RESOURCE_ID", "OCCURRED_AT", "RECEIVED_AT", "VERDICT_STATUS");
        primary("RISK_EVENT", "ID");
        identity("RISK_EVENT", "ID");
        unique("RISK_EVENT", "UQ_RISK_EVENT_PROJECT_EVENT", "PROJECT_ID", "EVENT_ID");

        required("RISK_BLACKLIST", "PROJECT_ID", "USER_ID", "REASON", "ADDED_AT");
        primary("RISK_BLACKLIST", "PROJECT_ID", "USER_ID");

        required("RISK_GLOBAL_BATCH", "PROJECT_ID", "BATCH_ID", "PAYLOAD_DIGEST", "ACCEPTED", "CREATED_AT");
        primary("RISK_GLOBAL_BATCH", "PROJECT_ID", "BATCH_ID");

        required("RISK_GLOBAL_SUMMARY", "PROJECT_ID", "USER_ID", "BUCKET_AT", "REQUEST_COUNT",
                "FAILURES", "DENIED", "SUSPECTED", "DETAILS_JSON");
        primary("RISK_GLOBAL_SUMMARY", "PROJECT_ID", "USER_ID", "BUCKET_AT");

        required("RISK_GLOBAL_STATE", "PROJECT_ID", "USER_ID", "UPDATED_AT", "STATE_JSON");
        primary("RISK_GLOBAL_STATE", "PROJECT_ID", "USER_ID");

        required("RISK_GLOBAL_EVENT", "ID", "PROJECT_ID", "EVENT_ID", "USER_ID", "ACTION", "ROUTE", "OCCURRED_AT");
        primary("RISK_GLOBAL_EVENT", "ID");
        identity("RISK_GLOBAL_EVENT", "ID");

        required("RISK_GLOBAL_SAMPLE", "ID", "PROJECT_ID", "USER_ID", "EVENT_ID", "ACTION", "ROUTE",
                "METHOD", "OCCURRED_AT", "DURATION_MS", "RESPONSE_STATUS", "SAMPLE_KIND");
        primary("RISK_GLOBAL_SAMPLE", "ID");
        identity("RISK_GLOBAL_SAMPLE", "ID");

        required("RISK_GLOBAL_SAMPLE_BUDGET", "MINUTE_AT", "SAMPLE_COUNT");
        primary("RISK_GLOBAL_SAMPLE_BUDGET", "MINUTE_AT");

        for (String table : List.of("RISK_EVENT", "RISK_BLACKLIST", "RISK_GLOBAL_BATCH",
                "RISK_GLOBAL_SUMMARY", "RISK_GLOBAL_STATE", "RISK_GLOBAL_EVENT", "RISK_GLOBAL_SAMPLE")) {
            projectReference(table);
        }
    }

    private record Column(int type, boolean nullable, boolean identity, String defaultValue) { }

    private Map<String, Column> columns(String table) throws SQLException {
        Map<String, Column> result = new HashMap<>();
        try (var rows = metadata.getColumns(catalog, schema, table, "%")) {
            while (rows.next()) {
                // JDBC arguments are patterns: underscores in our table names are wildcards.
                if (!table.equals(rows.getString("TABLE_NAME")) || !schema.equals(rows.getString("TABLE_SCHEM"))) continue;
                result.put(rows.getString("COLUMN_NAME"), new Column(rows.getInt("DATA_TYPE"),
                        rows.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls,
                        "YES".equals(rows.getString("IS_AUTOINCREMENT")), rows.getString("COLUMN_DEF")));
            }
        }
        return result;
    }

    private static Column column(Map<String, Column> columns, String table, String name) throws SQLException {
        Column column = columns.get(name);
        if (column == null) throw new SQLException("Missing schema column " + table + "." + name);
        return column;
    }

    private void required(String table, String... names) throws SQLException {
        Map<String, Column> columns = columns(table);
        for (String name : names) {
            if (column(columns, table, name).nullable()) {
                execute("ALTER TABLE " + table(table) + " ALTER COLUMN " + quote(name) + " SET NOT NULL");
            }
        }
    }

    private void primary(String table, String... columns) throws SQLException {
        Map<Short, String> actual = new TreeMap<>();
        try (var rows = metadata.getPrimaryKeys(catalog, schema, table)) {
            while (rows.next()) actual.put(rows.getShort("KEY_SEQ"), rows.getString("COLUMN_NAME"));
        }
        if (actual.isEmpty()) {
            execute("ALTER TABLE " + table(table) + " ADD CONSTRAINT " + quote("PK_" + table)
                    + " PRIMARY KEY (" + columnList(columns) + ")");
        } else if (!new ArrayList<>(actual.values()).equals(List.of(columns))) {
            throw new SQLException("Unexpected primary key on " + table + ": " + actual.values()
                    + "; expected " + List.of(columns) + ". Migrate explicitly; existing keys are never replaced.");
        }
    }

    private void identity(String table, String name) throws SQLException {
        Column column = column(columns(table), table, name);
        if (column.identity()) return; // Never restart or replace an existing identity sequence.
        if (column.type() != Types.BIGINT) throw new SQLException("Expected BIGINT identity column " + table + "." + name);
        long start = 1;
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT MAX(" + quote(name) + ") FROM " + table(table))) {
            if (rows.next()) {
                long maximum = rows.getLong(1);
                if (!rows.wasNull() && maximum >= 1) {
                    if (maximum == Long.MAX_VALUE) throw new SQLException("Identity exhausted for " + table + "." + name);
                    start = maximum + 1;
                }
            }
        }
        execute("ALTER TABLE " + table(table) + " ALTER COLUMN " + quote(name)
                + " BIGINT GENERATED BY DEFAULT AS IDENTITY (START WITH " + start + ")");
    }

    private void unique(String table, String constraint, String... columns) throws SQLException {
        Map<String, Map<Short, String>> indexes = new HashMap<>();
        try (var rows = metadata.getIndexInfo(catalog, schema, table, true, false)) {
            while (rows.next()) {
                String name = rows.getString("INDEX_NAME");
                short position = rows.getShort("ORDINAL_POSITION");
                if (name != null && position > 0 && !rows.getBoolean("NON_UNIQUE")) {
                    indexes.computeIfAbsent(name, ignored -> new TreeMap<>())
                            .put(position, rows.getString("COLUMN_NAME"));
                }
            }
        }
        if (indexes.values().stream().anyMatch(index -> new ArrayList<>(index.values()).equals(List.of(columns)))) return;
        execute("ALTER TABLE " + table(table) + " ADD CONSTRAINT " + quote(constraint)
                + " UNIQUE (" + columnList(columns) + ")");
    }

    private void defaultZero(String table, String name) throws SQLException {
        String value = column(columns(table), table, name).defaultValue();
        if (value != null) {
            if (value.trim().equals("0")) return;
            throw new SQLException("Unexpected default for " + table + "." + name + ": " + value
                    + "; expected 0. Existing defaults are never overwritten.");
        }
        execute("ALTER TABLE " + table(table) + " ALTER COLUMN " + quote(name) + " SET DEFAULT 0");
    }

    private void projectReference(String table) throws SQLException {
        Map<String, List<String>> references = new HashMap<>();
        try (var rows = metadata.getImportedKeys(catalog, schema, table)) {
            while (rows.next()) {
                String name = rows.getString("FK_NAME");
                String target = rows.getString("PKTABLE_SCHEM") + "." + rows.getString("PKTABLE_NAME")
                        + "." + rows.getString("PKCOLUMN_NAME");
                references.computeIfAbsent(name, ignored -> new ArrayList<>())
                        .add(rows.getString("FKCOLUMN_NAME") + "->" + target);
            }
        }
        List<String> expected = List.of("PROJECT_ID->" + schema + ".RISK_PROJECT.ID");
        if (references.containsValue(expected)) return;
        execute("ALTER TABLE " + table(table) + " ADD CONSTRAINT " + quote("FK_" + table + "_PROJECT")
                + " FOREIGN KEY (\"PROJECT_ID\") REFERENCES " + table("RISK_PROJECT") + " (\"ID\")");
    }

    private String table(String table) { return quote(schema) + "." + quote(table); }

    private static String columnList(String... columns) {
        return String.join(", ", java.util.Arrays.stream(columns).map(RiskSchemaConstraints::quote).toList());
    }

    private static String quote(String name) { return "\"" + name.replace("\"", "\"\"") + "\""; }

    private void execute(String sql) throws SQLException {
        LOG.info("[risk][schema] {}", sql);
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
