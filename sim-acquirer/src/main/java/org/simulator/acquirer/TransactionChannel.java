package org.simulator.acquirer;

import org.simulator.iso.IsoMessage;
import org.simulator.transport.Exchange;

import java.util.concurrent.CompletableFuture;

/**
 * Anything able to deliver a request and return the matching response: a TCP ISO client,
 * an HTTP API client, etc.
 */
public interface TransactionChannel extends AutoCloseable {

    CompletableFuture<Exchange> send(IsoMessage request, int timeoutMs);

    boolean isConnected();

    /** Dialect used by default for messages sent through this channel. */
    String dialect();

    int defaultTimeoutMs();

    String describe();

    @Override
    void close();
}
