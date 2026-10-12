package org.simulator.persistence;

import org.simulator.common.SimulatorException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Tiny JDBC helper to avoid boilerplate in repositories. */
public final class Jdbc {

    private final Database db;

    public Jdbc(Database db) {
        this.db = db;
    }

    @FunctionalInterface
    public interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    public <T> List<T> query(String sql, RowMapper<T> mapper, Object... params) {
        try (Connection c = db.connection(); PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                List<T> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(mapper.map(rs));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new SimulatorException("Query failed: " + e.getMessage(), e);
        }
    }

    public <T> Optional<T> queryOne(String sql, RowMapper<T> mapper, Object... params) {
        List<T> list = query(sql, mapper, params);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public int update(String sql, Object... params) {
        try (Connection c = db.connection(); PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new SimulatorException("Update failed: " + e.getMessage(), e);
        }
    }

    public long insert(String sql, Object... params) {
        try (Connection c = db.connection(); PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bind(ps, params);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                return rs.next() ? rs.getLong(1) : -1;
            }
        } catch (SQLException e) {
            throw new SimulatorException("Insert failed: " + e.getMessage(), e);
        }
    }

    /** Executes the same statement for many parameter rows in a single transaction. */
    public void batch(String sql, List<Object[]> rows) {
        if (rows.isEmpty()) {
            return;
        }
        try (Connection c = db.connection()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (Object[] row : rows) {
                    bind(ps, row);
                    ps.addBatch();
                }
                ps.executeBatch();
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new SimulatorException("Batch failed: " + e.getMessage(), e);
        }
    }

    private static void bind(PreparedStatement ps, Object[] params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            ps.setObject(i + 1, params[i]);
        }
    }
}
