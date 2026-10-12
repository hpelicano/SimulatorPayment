package org.simulator.transport;

import javax.net.ssl.SSLContext;
import java.util.List;

/**
 * Connection options for {@link IsoClient} and {@link IsoServer}.
 *
 * @param sslContext null for plain TCP
 */
public record TransportOptions(
        String host,
        int port,
        Framing framing,
        String dialect,
        int timeoutMs,
        int connectTimeoutMs,
        int[] correlationFields,
        SSLContext sslContext,
        List<String> tlsProtocols) {

    public static TransportOptions of(String host, int port, Framing framing, String dialect) {
        return new TransportOptions(host, port, framing, dialect, 30_000, 5_000, new int[]{11, 41}, null, List.of());
    }

    public TransportOptions withTimeout(int ms) {
        return new TransportOptions(host, port, framing, dialect, ms, connectTimeoutMs, correlationFields, sslContext, tlsProtocols);
    }

    public TransportOptions withPort(int newPort) {
        return new TransportOptions(host, newPort, framing, dialect, timeoutMs, connectTimeoutMs, correlationFields, sslContext, tlsProtocols);
    }

    public TransportOptions withFraming(Framing newFraming) {
        return new TransportOptions(host, port, newFraming == null ? framing : newFraming, dialect, timeoutMs,
                connectTimeoutMs, correlationFields, sslContext, tlsProtocols);
    }

    public TransportOptions withHost(String newHost) {
        return new TransportOptions(newHost, port, framing, dialect, timeoutMs, connectTimeoutMs, correlationFields, sslContext, tlsProtocols);
    }

    public TransportOptions withTls(SSLContext ctx, List<String> protocols) {
        return new TransportOptions(host, port, framing, dialect, timeoutMs, connectTimeoutMs, correlationFields, ctx, protocols);
    }
}
