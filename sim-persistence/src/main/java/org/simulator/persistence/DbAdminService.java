package org.simulator.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import org.simulator.common.SimulatorException;
import org.simulator.common.json.Json;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Local H2 administration: table explorer, SQL console, export, backup/restore and maintenance. */
public class DbAdminService {

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final Database db;

    public DbAdminService(Database db) {
        this.db = db;
    }

    public List<String> tables() {
        try (Connection c = db.connection()) {
            DatabaseMetaData md = c.getMetaData();
            List<String> out = new ArrayList<>();
            try (ResultSet rs = md.getTables(null, "PUBLIC", "%", new String[]{"TABLE"})) {
                while (rs.next()) {
                    out.add(rs.getString("TABLE_NAME"));
                }
            }
            return out;
        } catch (SQLException e) {
            throw new SimulatorException("Cannot list tables", e);
        }
    }

    /** Tables that contain persisted ISO requests. */
    public List<String> messageTables() {
        return tables().stream().filter(t -> hasColumn(t, "REQUEST") || hasColumn(t, "REQUEST_JSON")).toList();
    }

    public List<String> reusableMessageTables() {
        return messageTables().stream().filter(t -> hasColumn(t, "SUITE_ID") && hasColumn(t, "NAME")).toList();
    }

    public List<String> dialects() {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement("SELECT id FROM dialect ORDER BY id");
             ResultSet rs = ps.executeQuery()) {
            List<String> out = new ArrayList<>();
            while (rs.next()) {
                out.add(rs.getString(1));
            }
            return out;
        } catch (SQLException e) {
            throw new SimulatorException("Cannot list dialects", e);
        }
    }

    public void createDialect(String id, String description) {
        if (id == null || id.isBlank()) {
            throw new SimulatorException("Dialect id is required");
        }
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement("INSERT INTO dialect(id, description) VALUES (?, ?)")) {
            ps.setString(1, id.trim());
            ps.setString(2, description == null || description.isBlank() ? null : description.trim());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new SimulatorException("Cannot create dialect: " + e.getMessage(), e);
        }
    }

    /** Creates a reusable ISO-message table with the standard transaction-test structure. */
    public void createMessageTable(String table) {
        requireIdentifier(table);
        try (Connection c = db.connection(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE " + table + " ("
                    + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                    + "suite_id VARCHAR(120) NOT NULL REFERENCES dialect(id),"
                    + "name VARCHAR(200) NOT NULL,"
                    + "request CLOB NOT NULL,"
                    + "expected_rc VARCHAR(4),"
                    + "ignore_fields VARCHAR(400),"
                    + "enabled BOOLEAN DEFAULT TRUE NOT NULL,"
                    + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,"
                    + "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL)");
            st.execute("CREATE INDEX ix_" + table + "_suite ON " + table + "(suite_id)");
        } catch (SQLException e) {
            throw new SimulatorException("Cannot create table " + table + ": " + e.getMessage(), e);
        }
    }

    /** Returns the newest rows of a table for read-only browsing. */
    public SqlResult tableRows(String table, int maxRows) {
        return tableRows(table, null, maxRows);
    }

    /** Returns newest rows, filtering by suite when the selected table supports it. */
    public SqlResult tableRows(String table, String suiteId, int maxRows) {
        requireIdentifier(table);
        boolean filter = suiteId != null && !suiteId.isBlank() && hasColumn(table, "SUITE_ID");
        String sql = "SELECT * FROM " + table + (filter ? " WHERE suite_id = ?" : "") + " ORDER BY 1 DESC";
        try (Connection c = db.connection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setMaxRows(Math.max(1, maxRows));
            if (filter) {
                ps.setString(1, suiteId);
            }
            long start = System.nanoTime();
            try (ResultSet rs = ps.executeQuery()) {
                return toResult(rs, (System.nanoTime() - start) / 1_000_000);
            }
        } catch (SQLException e) {
            throw new SimulatorException("Cannot read " + table + ": " + e.getMessage(), e);
        }
    }

    public long insertMessage(String table, String suiteId, String name, String request, String expectedRc,
                              String ignoreFields, boolean enabled) {
        requireIdentifier(table);
        if (!hasColumn(table, "SUITE_ID") || !hasColumn(table, "REQUEST")) {
            throw new SimulatorException("Table " + table + " does not support reusable messages");
        }
        String requestDialect = requestDialect(request);
        String sql = "INSERT INTO " + table + "(suite_id, name, request, expected_rc, ignore_fields, enabled)"
                + " VALUES (?,?,?,?,?,?)";
        try (Connection c = db.connection(); PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            validateDialect(c, suiteId, "suite_id");
            validateDialect(c, requestDialect, "request.dialect");
            c.setAutoCommit(false);
            try {
                ps.setString(1, suiteId);
                ps.setString(2, name);
                ps.setString(3, request);
                ps.setString(4, expectedRc);
                ps.setString(5, ignoreFields);
                ps.setBoolean(6, enabled);
                ps.executeUpdate();
                long id;
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    id = rs.next() ? rs.getLong(1) : -1;
                }
                c.commit();
                return id;
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new SimulatorException("Cannot save message in " + table + ": " + e.getMessage(), e);
        }
    }

    public void updateMessage(String table, long id, String suiteId, String name, String request, String expectedRc,
                              String ignoreFields, boolean enabled) {
        requireIdentifier(table);
        if (!hasColumn(table, "SUITE_ID") || !hasColumn(table, "REQUEST")) {
            throw new SimulatorException("Table " + table + " does not support reusable messages");
        }
        String requestDialect = requestDialect(request);
        String sql = "UPDATE " + table + " SET suite_id=?, name=?, request=?, expected_rc=?, ignore_fields=?, "
                + "enabled=?, updated_at=CURRENT_TIMESTAMP WHERE id=?";
        try (Connection c = db.connection(); PreparedStatement ps = c.prepareStatement(sql)) {
            validateDialect(c, suiteId, "suite_id");
            validateDialect(c, requestDialect, "request.dialect");
            ps.setString(1, suiteId);
            ps.setString(2, name);
            ps.setString(3, request);
            ps.setString(4, expectedRc);
            ps.setString(5, ignoreFields);
            ps.setBoolean(6, enabled);
            ps.setLong(7, id);
            if (ps.executeUpdate() != 1) {
                throw new SimulatorException("Message #" + id + " was not found in " + table);
            }
        } catch (SQLException e) {
            throw new SimulatorException("Cannot update message in " + table + ": " + e.getMessage(), e);
        }
    }

    private static String requestDialect(String request) {
        if (request == null || request.isBlank()) {
            throw new SimulatorException("Request JSON is required");
        }
        try {
            JsonNode root = Json.mapper().readTree(request);
            if (root == null || !root.isObject()) {
                throw new SimulatorException("Request must be a JSON object");
            }
            JsonNode dialect = root.get("dialect");
            if (dialect == null || !dialect.isTextual() || dialect.asText().isBlank()) {
                throw new SimulatorException("Request JSON must contain a non-empty string property 'dialect'");
            }
            return dialect.asText().trim();
        } catch (java.io.IOException e) {
            throw new SimulatorException("Invalid request JSON: " + e.getMessage(), e);
        }
    }

    private static void validateDialect(Connection c, String id, String field) throws SQLException {
        if (id == null || id.isBlank()) {
            throw new SimulatorException(field + " is required");
        }
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM dialect WHERE id = ?")) {
            ps.setString(1, id.trim());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SimulatorException("Unknown " + field + " reference: " + id);
                }
            }
        }
    }

    /**
     * Executes any SQL statement. SELECT-like statements return rows; others return the update count.
     *
     * @param maxRows maximum rows to return for queries
     */
    public SqlResult execute(String sql, int maxRows) {
        try (Connection c = db.connection(); Statement st = c.createStatement()) {
            st.setMaxRows(maxRows);
            long start = System.nanoTime();
            boolean isQuery = st.execute(sql);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            if (!isQuery) {
                return new SqlResult(List.of("UPDATE_COUNT"), List.of(List.of(String.valueOf(st.getUpdateCount()))), elapsedMs);
            }
            try (ResultSet rs = st.getResultSet()) {
                return toResult(rs, elapsedMs);
            }
        } catch (SQLException e) {
            throw new SimulatorException("SQL error: " + e.getMessage(), e);
        }
    }

    public String exportTableJson(String table) {
        requireIdentifier(table);
        try (Connection c = db.connection(); PreparedStatement ps = c.prepareStatement("SELECT * FROM " + table);
             ResultSet rs = ps.executeQuery()) {
            ResultSetMetaData md = rs.getMetaData();
            List<Map<String, Object>> rows = new ArrayList<>();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= md.getColumnCount(); i++) {
                    Object v = rs.getObject(i);
                    row.put(md.getColumnLabel(i).toLowerCase(), v instanceof java.sql.Clob clob
                            ? clob.getSubString(1, (int) clob.length()) : v == null ? null : v.toString());
                }
                rows.add(row);
            }
            return Json.write(rows);
        } catch (SQLException e) {
            throw new SimulatorException("Cannot export " + table, e);
        }
    }

    /** Full SQL script backup (optionally zipped when file ends with .zip). */
    public void backup(Path file) {
        String compression = file.toString().endsWith(".zip") ? " COMPRESSION ZIP" : "";
        runPath("SCRIPT TO ?" + compression, file);
    }

    /** Restores a backup produced by {@link #backup(Path)} dropping current objects first. */
    public void restore(Path file) {
        String compression = file.toString().endsWith(".zip") ? " COMPRESSION ZIP" : "";
        try (Connection c = db.connection(); Statement st = c.createStatement()) {
            st.execute("DROP ALL OBJECTS");
        } catch (SQLException e) {
            throw new SimulatorException("Cannot drop objects before restore", e);
        }
        runPath("RUNSCRIPT FROM ?" + compression, file);
    }

    public int purgeTransactionLog(int olderThanDays) {
        return (int) Long.parseLong(execute("DELETE FROM transaction_log WHERE created_at < DATEADD('DAY', -" + Math.max(0, olderThanDays)
                + ", CURRENT_TIMESTAMP)", 1).rows().get(0).get(0));
    }

    public String url() {
        return db.url();
    }

    private void runPath(String sql, Path file) {
        try (Connection c = db.connection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, file.toAbsolutePath().toString());
            ps.execute();
        } catch (SQLException e) {
            throw new SimulatorException("Backup/restore failed: " + e.getMessage(), e);
        }
    }

    private static SqlResult toResult(ResultSet rs, long elapsedMs) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        List<String> cols = new ArrayList<>();
        for (int i = 1; i <= md.getColumnCount(); i++) {
            cols.add(md.getColumnLabel(i));
        }
        List<List<String>> rows = new ArrayList<>();
        while (rs.next()) {
            List<String> row = new ArrayList<>(cols.size());
            for (int i = 1; i <= cols.size(); i++) {
                Object v = rs.getObject(i);
                if (v instanceof java.sql.Clob clob) {
                    v = clob.getSubString(1, (int) Math.min(clob.length(), 4000));
                }
                row.add(v == null ? null : v.toString());
            }
            rows.add(row);
        }
        return new SqlResult(cols, rows, elapsedMs);
    }

    private static void requireIdentifier(String name) {
        if (!IDENTIFIER.matcher(name).matches()) {
            throw new SimulatorException("Invalid table name: " + name);
        }
    }

    private boolean hasColumn(String table, String column) {
        requireIdentifier(table);
        try (Connection c = db.connection();
             ResultSet rs = c.getMetaData().getColumns(null, "PUBLIC", table.toUpperCase(), column.toUpperCase())) {
            return rs.next();
        } catch (SQLException e) {
            throw new SimulatorException("Cannot inspect table " + table, e);
        }
    }

    public record SqlResult(List<String> columns, List<List<String>> rows, long elapsedMs) {
    }
}
