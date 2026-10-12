package org.simulator.stress;

/**
 * Service level thresholds. The run is aborted when a threshold is violated for
 * {@code consecutiveSeconds} consecutive seconds after {@code graceSec}.
 * A value of 0 disables the corresponding check.
 */
public class SlaPolicy {

    private double maxP99Ms;
    private double maxErrorRatePct;
    private int graceSec = 5;
    private int consecutiveSeconds = 3;

    public static SlaPolicy none() {
        return new SlaPolicy();
    }

    public double getMaxP99Ms() { return maxP99Ms; }
    public SlaPolicy setMaxP99Ms(double v) { this.maxP99Ms = v; return this; }
    public double getMaxErrorRatePct() { return maxErrorRatePct; }
    public SlaPolicy setMaxErrorRatePct(double v) { this.maxErrorRatePct = v; return this; }
    public int getGraceSec() { return graceSec; }
    public SlaPolicy setGraceSec(int v) { this.graceSec = v; return this; }
    public int getConsecutiveSeconds() { return consecutiveSeconds; }
    public SlaPolicy setConsecutiveSeconds(int v) { this.consecutiveSeconds = Math.max(1, v); return this; }
}
