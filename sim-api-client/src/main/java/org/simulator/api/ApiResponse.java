package org.simulator.api;

import org.simulator.iso.IsoMessage;

import java.util.List;
import java.util.Map;

/**
 * HTTP exchange with the Base24 API.
 *
 * @param response parsed ISO response (null when the body is not a message)
 */
public record ApiResponse(
        int status,
        Map<String, List<String>> headers,
        String requestBody,
        String body,
        IsoMessage request,
        IsoMessage response,
        long latencyNanos,
        int attempts,
        String protocol) {

    public boolean isSuccess() {
        return status >= 200 && status < 300;
    }

    public long latencyMicros() {
        return latencyNanos / 1000;
    }
}
