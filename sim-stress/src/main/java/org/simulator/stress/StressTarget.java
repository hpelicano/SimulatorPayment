package org.simulator.stress;

import org.simulator.common.event.TransactionEvent.Outcome;

import java.util.concurrent.CompletableFuture;

/**
 * Fires one transaction. Implementations adapt the acquirer, the API client, etc.
 * The future should complete with the normalized outcome (never exceptionally ideally;
 * exceptional completion is counted as ERROR).
 */
@FunctionalInterface
public interface StressTarget {

    CompletableFuture<Outcome> fire(long sequence);
}
