package org.simulator.transport;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.simulator.iso.IsoMessage;

import javax.net.ssl.SSLEngine;
import java.util.StringJoiner;

/** Shared Netty resources and pipeline helpers. */
final class NettySupport {

    private static volatile EventLoopGroup group;

    private NettySupport() {
    }

    /** Process wide event loop group with daemon threads. */
    static EventLoopGroup group() {
        EventLoopGroup g = group;
        if (g == null) {
            synchronized (NettySupport.class) {
                g = group;
                if (g == null) {
                    g = new NioEventLoopGroup(Math.max(2, Runtime.getRuntime().availableProcessors()),
                            new DefaultThreadFactory("sim-net", true));
                    group = g;
                }
            }
        }
        return g;
    }

    static void addTlsAndFraming(ChannelPipeline p, TransportOptions opts, boolean client) {
        if (opts.sslContext() != null) {
            SSLEngine engine = client ? opts.sslContext().createSSLEngine(opts.host(), opts.port()) : opts.sslContext().createSSLEngine();
            engine.setUseClientMode(client);
            if (opts.tlsProtocols() != null && !opts.tlsProtocols().isEmpty()) {
                engine.setEnabledProtocols(opts.tlsProtocols().toArray(String[]::new));
            }
            p.addLast("tls", new SslHandler(engine));
        }
        p.addLast("frameDecoder", opts.framing().decoder());
        p.addLast("frameEncoder", opts.framing().encoder());
    }

    static byte[] bytes(ByteBuf buf) {
        return ByteBufUtil.getBytes(buf);
    }

    /** Correlation key built from the configured fields, independent of MTI request/response. */
    static String correlationKey(IsoMessage msg, int[] fields) {
        StringJoiner j = new StringJoiner("|");
        for (int f : fields) {
            String v = msg.get(f);
            j.add(f + "=" + (v == null ? "" : v.trim()));
        }
        return j.toString();
    }
}
