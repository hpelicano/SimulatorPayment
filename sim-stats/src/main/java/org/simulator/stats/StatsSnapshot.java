package org.simulator.stats;

import java.util.List;
import java.util.Map;

/**
 * Immutable view of the statistics at a point in time. Latencies in milliseconds.
 *
 * @param tpsHistory transactions per second for the last N seconds (oldest first)
 */
public record StatsSnapshot(
        long total,
        long approved,
        long declined,
        long timeouts,
        long errors,
        double currentTps,
        double peakTps,
        double averageTps,
        double meanMs,
        double p50Ms,
        double p90Ms,
        double p95Ms,
        double p99Ms,
        double maxMs,
        Map<String, Long> byResponseCode,
        Map<String, Long> byMti,
        Map<String, Long> bySource,
        List<Long> tpsHistory,
        long elapsedSeconds) {

    public double approvalRate() {
        return total == 0 ? 0 : approved * 100.0 / total;
    }

    public double errorRate() {
        return total == 0 ? 0 : (timeouts + errors) * 100.0 / total;
    }
}
