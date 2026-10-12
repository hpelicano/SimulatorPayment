package org.simulator.stress;

/**
 * Metrics of one second of a stress run.
 *
 * @param throttled requests that could not be sent because maxInFlight was reached
 */
public record StressSample(
        int second,
        double targetTps,
        long sent,
        long completed,
        long approved,
        long declined,
        long timeouts,
        long errors,
        long throttled,
        int inFlight,
        double p50Ms,
        double p99Ms) {

    public double errorRatePct() {
        return completed == 0 ? 0 : (timeouts + errors) * 100.0 / completed;
    }

    /** Row for {@code stress_sample}: second_index, target_tps, sent, completed, timeouts, errors, p99_ms. */
    public Object[] toRow() {
        return new Object[]{second, targetTps, (int) sent, (int) completed, (int) timeouts, (int) errors, p99Ms};
    }
}
