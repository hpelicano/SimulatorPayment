package org.simulator.ui;

import org.simulator.acquirer.TransactionChannel;
import org.simulator.api.Base24ApiClient;
import org.simulator.iso.IsoMessage;
import org.simulator.transport.Exchange;
import org.simulator.transport.TransportException;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Adapts the HTTP API client to the acquirer {@link TransactionChannel}. */
public class ApiChannel implements TransactionChannel {

    private final Base24ApiClient client;
    private final String dialect;

    public ApiChannel(Base24ApiClient client, String dialect) {
        this.client = client;
        this.dialect = dialect;
    }

    @Override
    public CompletableFuture<Exchange> send(IsoMessage request, int timeoutMs) {
        return client.send(request).handle((r, err) -> {
            if (err != null) {
                throw new CompletionException(Base24ApiClient.isTimeout(err)
                        ? new TransportException("API timeout", true)
                        : new TransportException("API error: " + err.getMessage(), err));
            }
            if (r.response() == null) {
                throw new CompletionException(new TransportException("HTTP " + r.status() + ": " + r.body(), false));
            }
            return new Exchange(request, r.response(), r.requestBody().getBytes(StandardCharsets.UTF_8),
                    r.body().getBytes(StandardCharsets.UTF_8), r.latencyNanos());
        });
    }

    @Override
    public boolean isConnected() {
        return true;
    }

    @Override
    public String dialect() {
        return dialect;
    }

    @Override
    public int defaultTimeoutMs() {
        return client.endpoint().getTimeoutMs();
    }

    @Override
    public String describe() {
        return client.describe();
    }

    @Override
    public void close() {
        // stateless HTTP client
    }
}
