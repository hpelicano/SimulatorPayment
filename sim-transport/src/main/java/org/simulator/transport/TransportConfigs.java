package org.simulator.transport;

import org.simulator.config.crypto.SslContexts;
import org.simulator.config.model.TcpEndpoint;

import javax.net.ssl.SSLContext;
import java.util.function.UnaryOperator;

/** Builds {@link TransportOptions} from the user configuration. */
public final class TransportConfigs {

    private TransportConfigs() {
    }

    public static TransportOptions from(TcpEndpoint ep, UnaryOperator<String> secrets) {
        SSLContext ssl = ep.getTls() != null && ep.getTls().isEnabled() ? SslContexts.create(ep.getTls(), secrets) : null;
        return new TransportOptions(ep.getHost(), ep.getPort(), Framing.parse(ep.getFraming()), ep.getDialect(),
                ep.getTimeoutMs(), ep.getConnectTimeoutMs(), ep.getCorrelationFields(), ssl,
                ep.getTls() == null ? java.util.List.of() : ep.getTls().getProtocols());
    }
}
