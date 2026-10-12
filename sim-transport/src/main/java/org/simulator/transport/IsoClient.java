package org.simulator.transport;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.concurrent.ScheduledFuture;
import org.simulator.iso.IsoMessage;
import org.simulator.iso.MessageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Asynchronous ISO8583 TCP client with request/response correlation, per-request timeout and optional TLS.
 * Many requests can be in flight simultaneously on the same connection (pipelining), as Base24 does.
 */
public class IsoClient implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(IsoClient.class);

    private final TransportOptions opts;
    private final MessageService messages;
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private volatile Channel channel;
    private volatile Consumer<IsoMessage> unsolicitedListener = m -> LOG.info("Unsolicited message: {}", m);
    /** Auto-responder for host-initiated requests (e.g. 0800 echo from Base24). */
    private volatile Function<IsoMessage, IsoMessage> requestHandler;

    public IsoClient(TransportOptions opts, MessageService messages) {
        this.opts = opts;
        this.messages = messages;
    }

    public synchronized IsoClient connect() {
        if (isConnected()) {
            return this;
        }
        Bootstrap b = new Bootstrap()
                .group(NettySupport.group())
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, opts.connectTimeoutMs())
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        NettySupport.addTlsAndFraming(ch.pipeline(), opts, true);
                        ch.pipeline().addLast("handler", new InboundHandler());
                    }
                });
        ChannelFuture f = b.connect(opts.host(), opts.port()).awaitUninterruptibly();
        if (!f.isSuccess()) {
            throw new TransportException("Cannot connect to " + opts.host() + ":" + opts.port() + ": " + f.cause().getMessage(), f.cause());
        }
        channel = f.channel();
        channel.closeFuture().addListener(cf -> failAll("Connection closed"));
        LOG.info("Connected to {}:{} ({}, {})", opts.host(), opts.port(), opts.framing(), opts.dialect());
        return this;
    }

    public boolean isConnected() {
        Channel c = channel;
        return c != null && c.isActive();
    }

    public CompletableFuture<Exchange> send(IsoMessage request) {
        return send(request, opts.timeoutMs());
    }

    public CompletableFuture<Exchange> send(IsoMessage request, int timeoutMs) {
        CompletableFuture<Exchange> result = new CompletableFuture<>();
        Channel ch = channel;
        if (ch == null || !ch.isActive()) {
            result.completeExceptionally(new TransportException("Not connected", false));
            return result;
        }
        if (request.getDialect() == null) {
            request.setDialect(opts.dialect());
        }
        byte[] raw;
        try {
            raw = messages.pack(request);
        } catch (RuntimeException e) {
            result.completeExceptionally(e);
            return result;
        }
        String key = NettySupport.correlationKey(request, opts.correlationFields());
        Pending p = new Pending(request, raw, result);
        if (pending.putIfAbsent(key, p) != null) {
            result.completeExceptionally(new TransportException("Duplicate in-flight correlation key " + key, false));
            return result;
        }
        p.timeout = ch.eventLoop().schedule(() -> {
            if (pending.remove(key, p)) {
                result.completeExceptionally(new TransportException("Timeout after " + timeoutMs + " ms (" + key + ")", true));
            }
        }, timeoutMs, TimeUnit.MILLISECONDS);
        p.startNanos = System.nanoTime();
        ch.writeAndFlush(raw).addListener(wf -> {
            if (!wf.isSuccess() && pending.remove(key, p)) {
                p.timeout.cancel(false);
                result.completeExceptionally(new TransportException("Write failed: " + wf.cause().getMessage(), wf.cause()));
            }
        });
        return result;
    }

    /** Sends a message without waiting for any response (e.g. advice responses). */
    public void sendOneWay(IsoMessage msg) {
        if (msg.getDialect() == null) {
            msg.setDialect(opts.dialect());
        }
        Channel ch = channel;
        if (ch == null || !ch.isActive()) {
            throw new TransportException("Not connected", false);
        }
        ch.writeAndFlush(messages.pack(msg));
    }

    public int inFlight() {
        return pending.size();
    }

    public void setUnsolicitedListener(Consumer<IsoMessage> listener) {
        this.unsolicitedListener = listener;
    }

    public void setRequestHandler(Function<IsoMessage, IsoMessage> handler) {
        this.requestHandler = handler;
    }

    public TransportOptions options() {
        return opts;
    }

    @Override
    public void close() {
        Channel c = channel;
        if (c != null) {
            c.close().awaitUninterruptibly(2000);
        }
        failAll("Client closed");
    }

    private void failAll(String reason) {
        pending.forEach((k, p) -> {
            if (pending.remove(k, p)) {
                if (p.timeout != null) {
                    p.timeout.cancel(false);
                }
                p.future.completeExceptionally(new TransportException(reason, false));
            }
        });
    }

    private final class InboundHandler extends SimpleChannelInboundHandler<ByteBuf> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, ByteBuf frame) {
            long now = System.nanoTime();
            byte[] raw = NettySupport.bytes(frame);
            IsoMessage msg;
            try {
                msg = messages.unpack(opts.dialect(), raw);
            } catch (RuntimeException e) {
                LOG.warn("Cannot decode incoming frame ({} bytes): {}", raw.length, e.getMessage());
                return;
            }
            if (msg.isRequest()) {
                Function<IsoMessage, IsoMessage> h = requestHandler;
                if (h != null) {
                    IsoMessage resp = h.apply(msg);
                    if (resp != null) {
                        resp.setDialect(opts.dialect());
                        ctx.writeAndFlush(messages.pack(resp));
                    }
                } else {
                    unsolicitedListener.accept(msg);
                }
                return;
            }
            String key = NettySupport.correlationKey(msg, opts.correlationFields());
            Pending p = pending.remove(key);
            if (p == null) {
                unsolicitedListener.accept(msg);
                return;
            }
            p.timeout.cancel(false);
            p.future.complete(new Exchange(p.request, msg, p.raw, raw, now - p.startNanos));
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            LOG.warn("Connection error {}:{}: {}", opts.host(), opts.port(), cause.getMessage());
            ctx.close();
        }
    }

    private static final class Pending {
        final IsoMessage request;
        final byte[] raw;
        final CompletableFuture<Exchange> future;
        volatile ScheduledFuture<?> timeout;
        volatile long startNanos;

        Pending(IsoMessage request, byte[] raw, CompletableFuture<Exchange> future) {
            this.request = request;
            this.raw = raw;
            this.future = future;
        }
    }
}
