package org.simulator.transport;

import org.simulator.iso.IsoMessage;

/**
 * Result of a request/response round trip.
 *
 * @param latencyNanos wall clock from write to response decoded
 */
public record Exchange(IsoMessage request, IsoMessage response, byte[] rawRequest, byte[] rawResponse, long latencyNanos) {

    public long latencyMicros() {
        return latencyNanos / 1_000;
    }

    public double latencyMillis() {
        return latencyNanos / 1_000_000.0;
    }
}
