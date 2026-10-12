package org.simulator.persistence.repo;

import org.simulator.persistence.Jdbc;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/** Persistence of regression runs, stress runs, Base24 snapshots and audit trail. */
public class RunRepository {

    private final Jdbc jdbc;

    public RunRepository(Jdbc jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------------- regression

    public long startRegression(String suiteId, String suiteName, String environment) {
        return jdbc.insert("INSERT INTO regression_execution(suite_id, suite_name, environment, started_at, status) VALUES (?,?,?,?,?)",
                suiteId, suiteName, environment, Timestamp.from(Instant.now()), "RUNNING");
    }

    public void finishRegression(long runId, int total, int passed, int failed, String status) {
        jdbc.update("UPDATE regression_execution SET finished_at=?, total=?, passed=?, failed=?, status=? WHERE id=?",
                Timestamp.from(Instant.now()), total, passed, failed, status, runId);
    }

    public void addRegressionResult(long runId, Long testCaseId, String name, boolean passed, String expectedRc,
                                    String actualRc, Long latencyMicros, String issuesJson, String responseJson) {
        jdbc.insert("INSERT INTO regression_execution_result(run_id, test_case_id, test_case_name, passed, expected_rc, actual_rc, "
                        + "latency_micros, issues_json, response_json) VALUES (?,?,?,?,?,?,?,?,?)",
                runId, testCaseId, name, passed, expectedRc, actualRc, latencyMicros, issuesJson, responseJson);
    }

    public List<RegressionRunRecord> regressionRuns(int limit) {
        return jdbc.query("SELECT id, suite_name, environment, started_at, finished_at, total, passed, failed, status "
                        + "FROM regression_execution ORDER BY id DESC LIMIT ?",
                rs -> new RegressionRunRecord(rs.getLong(1), rs.getString(2), rs.getString(3),
                        toInstant(rs.getTimestamp(4)), toInstant(rs.getTimestamp(5)), rs.getInt(6), rs.getInt(7),
                        rs.getInt(8), rs.getString(9)), limit);
    }

    public List<RegressionResultRecord> regressionResults(long runId) {
        return jdbc.query("SELECT id, run_id, test_case_id, test_case_name, passed, expected_rc, actual_rc, latency_micros, "
                        + "issues_json, response_json FROM regression_execution_result WHERE run_id=? ORDER BY id",
                rs -> new RegressionResultRecord(rs.getLong(1), rs.getLong(2), (Long) rs.getObject(3), rs.getString(4),
                        rs.getBoolean(5), rs.getString(6), rs.getString(7), (Long) rs.getObject(8), rs.getString(9),
                        rs.getString(10)), runId);
    }

    public java.util.Optional<RegressionRunRecord> regressionRun(long runId) {
        return jdbc.queryOne("SELECT id, suite_name, environment, started_at, finished_at, total, passed, failed, status "
                        + "FROM regression_execution WHERE id=?",
                rs -> new RegressionRunRecord(rs.getLong(1), rs.getString(2), rs.getString(3),
                        toInstant(rs.getTimestamp(4)), toInstant(rs.getTimestamp(5)), rs.getInt(6), rs.getInt(7),
                        rs.getInt(8), rs.getString(9)), runId);
    }

    // ---------------------------------------------------------------- stress

    public long startStress(String name, String environment, String profileJson) {
        return jdbc.insert("INSERT INTO stress_execution(name, environment, profile_json, started_at, status) VALUES (?,?,?,?,?)",
                name, environment, profileJson, Timestamp.from(Instant.now()), "RUNNING");
    }

    public void finishStress(long runId, long sent, long approved, long declined, long timeouts, long errors,
                             double tps, double p50, double p95, double p99, double max, String status) {
        jdbc.update("UPDATE stress_execution SET finished_at=?, sent=?, approved=?, declined=?, timeouts=?, errors=?, achieved_tps=?, "
                        + "p50_ms=?, p95_ms=?, p99_ms=?, max_ms=?, status=? WHERE id=?",
                Timestamp.from(Instant.now()), sent, approved, declined, timeouts, errors, tps, p50, p95, p99, max, status, runId);
    }

    public void addStressSamples(long runId, List<Object[]> samples) {
        List<Object[]> rows = samples.stream().map(s -> {
            Object[] r = new Object[s.length + 1];
            r[0] = runId;
            System.arraycopy(s, 0, r, 1, s.length);
            return r;
        }).toList();
        jdbc.batch("INSERT INTO stress_execution_sample(run_id, second_index, target_tps, sent, completed, timeouts, errors, p99_ms) "
                + "VALUES (?,?,?,?,?,?,?,?)", rows);
    }

    // ---------------------------------------------------------------- base24 snapshots

    public long saveSnapshot(String queryName, String environment, String paramsJson, int rows, String resultJson) {
        return jdbc.insert("INSERT INTO base24_snapshot(query_name, environment, params_json, rows_count, result_json) VALUES (?,?,?,?,?)",
                queryName, environment, paramsJson, rows, resultJson);
    }

    // ---------------------------------------------------------------- audit

    public void audit(String user, String action, String target, String detail, boolean success) {
        jdbc.insert("INSERT INTO system_audit_log(username, action, target, detail, success) VALUES (?,?,?,?,?)",
                user, action, target, detail, success);
    }

    public List<String[]> auditTrail(int limit) {
        return jdbc.query("SELECT created_at, username, action, target, success, detail FROM system_audit_log ORDER BY id DESC LIMIT ?",
                rs -> new String[]{String.valueOf(rs.getTimestamp(1)), rs.getString(2), rs.getString(3), rs.getString(4),
                        String.valueOf(rs.getBoolean(5)), rs.getString(6)}, limit);
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    public record RegressionRunRecord(long id, String suiteName, String environment, Instant startedAt, Instant finishedAt,
                                      int total, int passed, int failed, String status) {
    }

    public record RegressionResultRecord(long id, long runId, Long testCaseId, String testCaseName, boolean passed,
                                         String expectedRc, String actualRc, Long latencyMicros, String issuesJson,
                                         String responseJson) {
    }
}
