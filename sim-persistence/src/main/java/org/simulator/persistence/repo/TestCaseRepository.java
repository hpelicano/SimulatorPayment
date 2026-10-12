package org.simulator.persistence.repo;

import org.simulator.persistence.Jdbc;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

public class TestCaseRepository {

    private final Jdbc jdbc;

    public TestCaseRepository(Jdbc jdbc) {
        this.jdbc = jdbc;
    }

    public String createSuite(String name, String description) {
        jdbc.update("INSERT INTO dialect(id, description) VALUES (?, ?)", name, description);
        return name;
    }

    public Optional<String> findSuiteId(String name) {
        return jdbc.queryOne("SELECT id FROM dialect WHERE id = ?", rs -> rs.getString(1), name);
    }

    public String suiteIdOrCreate(String name) {
        return findSuiteId(name).orElseGet(() -> createSuite(name, null));
    }

    public List<SuiteRecord> suites() {
        return jdbc.query("SELECT d.id, d.description, (SELECT COUNT(*) FROM regresion r WHERE r.suite_id = d.id) "
                + "FROM dialect d ORDER BY d.id", rs -> new SuiteRecord(rs.getString(1), rs.getString(2), rs.getInt(3)));
    }

    public void deleteSuite(String id) {
        jdbc.update("DELETE FROM dialect WHERE id = ?", id);
    }

    public long save(TestCaseRecord tc) {
        if (tc.id() == null) {
            return jdbc.insert("INSERT INTO regresion(suite_id, name, request, expected_rc, ignore_fields, enabled) "
                            + "VALUES (?,?,?,?,?,?)",
                    tc.suiteId(), tc.name(), tc.requestJson(), tc.expectedRc(), tc.ignoreFields(), tc.enabled());
        }
        jdbc.update("UPDATE regresion SET suite_id=?, name=?, request=?, expected_rc=?, ignore_fields=?, "
                        + "enabled=?, updated_at=CURRENT_TIMESTAMP WHERE id=?",
                tc.suiteId(), tc.name(), tc.requestJson(), tc.expectedRc(), tc.ignoreFields(), tc.enabled(), tc.id());
        return tc.id();
    }

    public List<TestCaseRecord> bySuite(String suiteId) {
        return jdbc.query("SELECT * FROM regresion WHERE suite_id = ? ORDER BY id", TestCaseRepository::map, suiteId);
    }

    public List<TestCaseRecord> enabledBySuite(String suiteId) {
        return jdbc.query("SELECT * FROM regresion WHERE suite_id = ? AND enabled = TRUE ORDER BY id", TestCaseRepository::map, suiteId);
    }

    public List<TestCaseRecord> all() {
        return jdbc.query("SELECT * FROM regresion ORDER BY id", TestCaseRepository::map);
    }

    public Optional<TestCaseRecord> byId(long id) {
        return jdbc.queryOne("SELECT * FROM regresion WHERE id = ?", TestCaseRepository::map, id);
    }

    public void delete(long id) {
        jdbc.update("DELETE FROM regresion WHERE id = ?", id);
    }

    private static TestCaseRecord map(ResultSet rs) throws SQLException {
        Timestamp created = rs.getTimestamp("created_at");
        String suite = rs.getString("suite_id");
        return new TestCaseRecord(rs.getLong("id"), suite, rs.getString("name"), suite,
                rs.getString("request"), rs.getString("expected_rc"), rs.getString("ignore_fields"),
                null, "MANUAL", rs.getBoolean("enabled"),
                created == null ? null : created.toInstant());
    }

    public record SuiteRecord(String id, String description, int caseCount) {
        public String name() {
            return id;
        }

        @Override
        public String toString() {
            return id + " (" + caseCount + ")";
        }
    }
}
