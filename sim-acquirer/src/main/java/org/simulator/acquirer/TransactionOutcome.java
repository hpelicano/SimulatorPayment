package org.simulator.acquirer;

import org.simulator.common.event.TransactionEvent.Outcome;
import org.simulator.iso.IsoMessage;
import org.simulator.validator.ValidationResult;

/**
 * Result of a transaction sent by the acquirer.
 *
 * @param response   null on timeout / error
 * @param validation null when no validation was requested
 */
public record TransactionOutcome(
        IsoMessage request,
        IsoMessage response,
        byte[] rawRequest,
        byte[] rawResponse,
        Outcome status,
        String responseCode,
        long latencyMicros,
        ValidationResult validation,
        String error) {

    public boolean passed() {
        return validation == null ? status == Outcome.APPROVED : validation.passed();
    }

    public double latencyMillis() {
        return latencyMicros / 1000.0;
    }
}
