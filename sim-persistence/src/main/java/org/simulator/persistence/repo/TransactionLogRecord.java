package org.simulator.persistence.repo;

import java.time.Instant;

/** {@code panMasked} retains its legacy name for schema compatibility; new records contain plain PAN. */
public record TransactionLogRecord(
        Long id,
        String source,
        String environment,
        String dialect,
        String mti,
        String processingCode,
        String stan,
        String rrn,
        String terminalId,
        String panMasked,
        String amount,
        String responseCode,
        String outcome,
        Long latencyMicros,
        String requestJson,
        String responseJson,
        String requestHex,
        String responseHex,
        String errorMessage,
        Instant createdAt) {
}
