package org.simulator.persistence.repo;

import org.simulator.persistence.Jdbc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Transaction log persistence. {@link #append(TransactionLogRecord)} is asynchronous and batched so that
 * high TPS runs are not slowed down by disk I/O. Callers must redact non-PAN sensitive data;
 * PAN is stored in plain text in the legacy {@code pan_masked} column.
 */
public class TransactionLogRepository implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(TransactionLogRepository.class);
    private static final String INSERT = "INSERT INTO transaction_log(source, environment, dialect, mti, processing_code, stan, rrn, "
            + "terminal_id, pan_masked, amount, response_code, outcome, latency_micros, request_json, response_json, request_hex, "
            + "response_hex, error_message) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
    private static final int MAX_QUEUE = 200_000;

    private final Jdbc jdbc;
    private final BlockingQueue<TransactionLogRecord> queue = new LinkedBlockingQueue<>(MAX_QUEUE);
    private final AtomicLong pending = new AtomicLong();
    private final Thread writer;
    private volatile boolean running = true;

    public TransactionLogRepository(Jdbc jdbc) {
        this.jdbc = jdbc;
        this.writer = new Thread(this::drainLoop, "txlog-writer");
        this.writer.setDaemon(true);
        this.writer.start();
    }

    /** Non-blocking; drops the record (with a warning) if the queue is full. */
    public void append(TransactionLogRecord r) {
        pending.incrementAndGet();
        if (!queue.offer(r)) {
            pending.decrementAndGet();
            LOG.warn("Transaction log queue full, dropping record");
        }
    }

    public void insertNow(TransactionLogRecord r) {
        jdbc.insert(INSERT, params(r));
        insertAudit(r);
    }

    public List<TransactionLogRecord> recent(int limit) {
        return jdbc.query("SELECT * FROM transaction_log ORDER BY id DESC LIMIT ?", TransactionLogRepository::map, limit);
    }

    public long count() {
        return jdbc.queryOne("SELECT COUNT(*) FROM transaction_log", rs -> rs.getLong(1)).orElse(0L);
    }

    public int purgeOlderThan(int days) {
        return jdbc.update("DELETE FROM transaction_log WHERE created_at < ?",
                Timestamp.from(Instant.now().minus(days, ChronoUnit.DAYS)));
    }

    /** Blocks until all queued records are written. */
    public void flush() {
        while (!queue.isEmpty()) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        synchronized (this) {
            // wait for the in-flight batch to finish
        }
    }

    private void drainLoop() {
        List<TransactionLogRecord> batch = new ArrayList<>(500);
        while (running || !queue.isEmpty()) {
            try {
                TransactionLogRecord first = queue.poll(200, TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                synchronized (this) {
                    batch.add(first);
                    queue.drainTo(batch, 499);
                    List<Object[]> rows = new ArrayList<>(batch.size());
                    batch.forEach(r -> rows.add(params(r)));
                    jdbc.batch(INSERT, rows);
                    jdbc.batch("INSERT INTO audit_log(request, response, status) VALUES (?,?,?)",
                            batch.stream().map(TransactionLogRepository::auditParams).toList());
                    batch.clear();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                LOG.error("Cannot persist transaction log batch", e);
                batch.clear();
            }
        }
    }

    private static Object[] params(TransactionLogRecord r) {
        return new Object[]{r.source(), r.environment(), r.dialect(), r.mti(), r.processingCode(), r.stan(), r.rrn(),
                r.terminalId(), r.panMasked(), r.amount(), r.responseCode(), r.outcome(), r.latencyMicros(), r.requestJson(),
                r.responseJson(), r.requestHex(), r.responseHex(), r.errorMessage()};
    }

    private void insertAudit(TransactionLogRecord r) {
        jdbc.insert("INSERT INTO audit_log(request, response, status) VALUES (?,?,?)", auditParams(r));
    }

    private static Object[] auditParams(TransactionLogRecord r) {
        return new Object[]{r.requestJson(), r.responseJson(), r.outcome()};
    }

    private static TransactionLogRecord map(ResultSet rs) throws SQLException {
        Timestamp ts = rs.getTimestamp("created_at");
        long lat = rs.getLong("latency_micros");
        return new TransactionLogRecord(rs.getLong("id"), rs.getString("source"), rs.getString("environment"),
                rs.getString("dialect"), rs.getString("mti"), rs.getString("processing_code"), rs.getString("stan"),
                rs.getString("rrn"), rs.getString("terminal_id"), rs.getString("pan_masked"), rs.getString("amount"),
                rs.getString("response_code"), rs.getString("outcome"), rs.wasNull() ? null : lat,
                rs.getString("request_json"), rs.getString("response_json"), rs.getString("request_hex"),
                rs.getString("response_hex"), rs.getString("error_message"), ts == null ? null : ts.toInstant());
    }

    @Override
    public void close() {
        flush();
        running = false;
        try {
            writer.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
