package org.simulator.regression.replay;

/**
 * One captured transaction. {@code requestJson} retains clear PAN/track data for replay,
 * without PIN blocks or security control fields.
 */
public record RecordedTransaction(long offsetMs, String source, String mti, String requestJson, String responseRc,
                                  String status, long latencyMicros) {
}