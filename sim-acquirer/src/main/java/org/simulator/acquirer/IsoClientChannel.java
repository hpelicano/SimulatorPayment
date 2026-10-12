package org.simulator.acquirer;

import org.simulator.iso.IsoMessage;
import org.simulator.iso.MessageService;
import org.simulator.transport.Exchange;
import org.simulator.transport.IsoClient;
import org.simulator.transport.TransportOptions;

import java.util.concurrent.CompletableFuture;

/** {@link TransactionChannel} over a persistent TCP connection. */
public class IsoClientChannel implements TransactionChannel {

    private final IsoClient client;

    public IsoClientChannel(TransportOptions options, MessageService messages) {
        this.client = new IsoClient(options, messages);
    }

    public IsoClientChannel connect() {
        client.connect();
        return this;
    }

    public IsoClient client() {
        return client;
    }

    @Override
    public CompletableFuture<Exchange> send(IsoMessage request, int timeoutMs) {
        if (!client.isConnected()) {
            client.connect();
        }
        return client.send(request, timeoutMs);
    }

    @Override
    public boolean isConnected() {
        return client.isConnected();
    }

    @Override
    public String dialect() {
        return client.options().dialect();
    }

    @Override
    public int defaultTimeoutMs() {
        return client.options().timeoutMs();
    }

    @Override
    public String describe() {
        TransportOptions o = client.options();
        return "tcp://" + o.host() + ":" + o.port() + " (" + o.framing() + ", " + o.dialect()
                + (o.sslContext() != null ? ", TLS" : "") + ")";
    }

    @Override
    public void close() {
        client.close();
    }
}
