package org.simulator.stress;

import java.util.List;

/** Final result of a stress run. */
public record StressResult(
        String name,
        String status,
        String abortReason,
        long sent,
        long approved,
        long declined,
        long timeouts,
        long errors,
        long throttled,
        double durationSec,
        double achievedTps,
        double p50Ms,
        double p95Ms,
        double p99Ms,
        double maxMs,
        List<StressSample> samples) {

    public long completed() {
        return approved + declined + timeouts + errors;
    }

    public String toCsv() {
        StringBuilder sb = new StringBuilder("second,target_tps,sent,completed,approved,declined,timeouts,errors,throttled,in_flight,p50_ms,p99_ms\n");
        for (StressSample s : samples) {
            sb.append(s.second()).append(',').append(s.targetTps()).append(',').append(s.sent()).append(',')
                    .append(s.completed()).append(',').append(s.approved()).append(',').append(s.declined()).append(',')
                    .append(s.timeouts()).append(',').append(s.errors()).append(',').append(s.throttled()).append(',')
                    .append(s.inFlight()).append(',').append(s.p50Ms()).append(',').append(s.p99Ms()).append('\n');
        }
        return sb.toString();
    }
}
