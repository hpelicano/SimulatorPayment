package org.simulator.common.event;

import java.time.Instant;

/**
 * Emitted every time a transaction completes (successfully or not) in any module.
 *
 * @param source         module that produced the event (ACQUIRER, ISSUER, STRESS, REGRESSION, API)
 * @param mti            request MTI
 * @param processingCode DE3 of the request (may be null)
 * @param bin            first 6 digits of the PAN (may be null)
 * @param responseCode   DE39 of the response (null on timeout/error)
 * @param outcome        normalized outcome
 * @param latencyMicros  round trip latency in microseconds
 */
public record TransactionEvent(
        String source,
        String mti,
        String processingCode,
        String bin,
        String responseCode,
        Outcome outcome,
        long latencyMicros,
        Instant timestamp) {

    public enum Outcome { APPROVED, DECLINED, TIMEOUT, ERROR }

    public static Outcome outcomeFor(String responseCode) {
        if (responseCode == null || responseCode.isBlank()) {
            return Outcome.ERROR;
        }
        return switch (responseCode.trim()) {
            case "00", "08", "10", "11", "16", "000", "001" -> Outcome.APPROVED;
            default -> Outcome.DECLINED;
        };
    }
}
