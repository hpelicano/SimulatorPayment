package org.simulator.stress;

/**
 * Load shape over time. All rates in transactions per second.
 */
public class LoadProfile {

    public enum Type { CONSTANT, RAMP, STEP, SPIKE }

    private String name = "stress";
    private Type type = Type.CONSTANT;
    private int durationSec = 60;
    private double targetTps = 10;
    /** RAMP: initial rate, rising linearly to targetTps over rampSec, then constant. */
    private double startTps = 1;
    private int rampSec = 30;
    /** STEP: rate starts at startTps and increases by stepTps every stepEverySec up to targetTps. */
    private double stepTps = 10;
    private int stepEverySec = 10;
    /** SPIKE: targetTps baseline with spikeTps during [spikeAtSec, spikeAtSec + spikeDurationSec). */
    private double spikeTps = 100;
    private int spikeAtSec = 20;
    private int spikeDurationSec = 5;
    /** Max outstanding requests (back-pressure). */
    private int maxInFlight = 500;

    public static LoadProfile constant(double tps, int durationSec) {
        LoadProfile p = new LoadProfile();
        p.type = Type.CONSTANT;
        p.targetTps = tps;
        p.durationSec = durationSec;
        return p;
    }

    public static LoadProfile ramp(double from, double to, int rampSec, int durationSec) {
        LoadProfile p = constant(to, durationSec);
        p.type = Type.RAMP;
        p.startTps = from;
        p.rampSec = rampSec;
        return p;
    }

    /** Target rate at the given elapsed time. */
    public double tpsAt(double elapsedSec) {
        return switch (type) {
            case CONSTANT -> targetTps;
            case RAMP -> rampSec <= 0 || elapsedSec >= rampSec
                    ? targetTps
                    : startTps + (targetTps - startTps) * (elapsedSec / rampSec);
            case STEP -> Math.min(targetTps, startTps + stepTps * Math.floor(elapsedSec / Math.max(1, stepEverySec)));
            case SPIKE -> elapsedSec >= spikeAtSec && elapsedSec < spikeAtSec + spikeDurationSec ? spikeTps : targetTps;
        };
    }

    public String getName() { return name; }
    public LoadProfile setName(String name) { this.name = name; return this; }
    public Type getType() { return type; }
    public LoadProfile setType(Type type) { this.type = type; return this; }
    public int getDurationSec() { return durationSec; }
    public LoadProfile setDurationSec(int durationSec) { this.durationSec = durationSec; return this; }
    public double getTargetTps() { return targetTps; }
    public LoadProfile setTargetTps(double targetTps) { this.targetTps = targetTps; return this; }
    public double getStartTps() { return startTps; }
    public LoadProfile setStartTps(double startTps) { this.startTps = startTps; return this; }
    public int getRampSec() { return rampSec; }
    public LoadProfile setRampSec(int rampSec) { this.rampSec = rampSec; return this; }
    public double getStepTps() { return stepTps; }
    public LoadProfile setStepTps(double stepTps) { this.stepTps = stepTps; return this; }
    public int getStepEverySec() { return stepEverySec; }
    public LoadProfile setStepEverySec(int stepEverySec) { this.stepEverySec = stepEverySec; return this; }
    public double getSpikeTps() { return spikeTps; }
    public LoadProfile setSpikeTps(double spikeTps) { this.spikeTps = spikeTps; return this; }
    public int getSpikeAtSec() { return spikeAtSec; }
    public LoadProfile setSpikeAtSec(int spikeAtSec) { this.spikeAtSec = spikeAtSec; return this; }
    public int getSpikeDurationSec() { return spikeDurationSec; }
    public LoadProfile setSpikeDurationSec(int spikeDurationSec) { this.spikeDurationSec = spikeDurationSec; return this; }
    public int getMaxInFlight() { return maxInFlight; }
    public LoadProfile setMaxInFlight(int maxInFlight) { this.maxInFlight = Math.max(1, maxInFlight); return this; }
    /** Copy with every TPS value (and maxInFlight) multiplied by {@code factor}; used to split load across agents. */
    public LoadProfile scaled(double factor) {
        LoadProfile p = new LoadProfile();
        p.name = name;
        p.type = type;
        p.durationSec = durationSec;
        p.targetTps = targetTps * factor;
        p.startTps = startTps * factor;
        p.rampSec = rampSec;
        p.stepTps = stepTps * factor;
        p.stepEverySec = stepEverySec;
        p.spikeTps = spikeTps * factor;
        p.spikeAtSec = spikeAtSec;
        p.spikeDurationSec = spikeDurationSec;
        p.maxInFlight = Math.max(1, (int) Math.ceil(maxInFlight * factor));
        return p;
    }
}