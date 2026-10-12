package org.simulator.transport;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.concurrent.GlobalEventExecutor;
import org.simulator.iso.IsoMessage;
import org.simulator.iso.MessageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

/**
 * ISO8583 TCP server. Each decoded request is handed to a {@link RequestHandler} which decides the response,
 * an optional artificial delay, or no response at all (to force timeouts on the peer).
 */
public class IsoServer implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(IsoServer.class);

    /** Decision for one incoming message. */
    public record Reply(IsoMessage response, long delayMs) {
        public static Reply none() {
            return new Reply(null, 0);
        }

        public static Reply of(IsoMessage response) {
            return new Reply(response, 0);
        }
    }

    @FunctionalInterface
    public interface RequestHandler {
        Reply handle(IsoMessage request, byte[] raw);
    }

    private final TransportOptions opts;
    private final MessageService messages;
    private final RequestHandler handler;
    private final ChannelGroup clients = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
    private Channel serverChannel;

    public IsoServer(TransportOptions opts, MessageService messages, RequestHandler handler) {
        this.opts = opts;
        this.messages = messages;
        this.handler = handler;
    }

    public synchronized IsoServer start() {
        ServerBootstrap b = new ServerBootstrap()
                .group(NettySupport.group())
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, 256)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        NettySupport.addTlsAndFraming(ch.pipeline(), opts, false);
                        ch.pipeline().addLast("handler", new ServerHandler());
                    }
                });
        String host = opts.host() == null || opts.host().isBlank() ? "0.0.0.0" : opts.host();
        ChannelFuture f = b.bind(host, opts.port()).awaitUninterruptibly();
        if (!f.isSuccess()) {
            throw new TransportException("Cannot bind " + host + ":" + opts.port() + ": " + f.cause().getMessage(), f.cause());
        }
        serverChannel = f.channel();
        LOG.info("ISO server listening on {}:{} ({}, {})", host, port(), opts.framing(), opts.dialect());
        return this;
    }

    public int port() {
        return serverChannel == null ? opts.port() : ((InetSocketAddress) serverChannel.localAddress()).getPort();
    }

    public boolean isRunning() {
        return serverChannel != null && serverChannel.isActive();
    }

    public int connectionCount() {
        return clients.size();
    }

    /** Sends a server-initiated message (e.g. 0800 sign-on/echo) to every connected peer. */
    public void broadcast(IsoMessage msg) {
        msg.setDialect(opts.dialect());
        clients.writeAndFlush(messages.pack(msg));
    }

    @Override
    public synchronized void close() {
        clients.close().awaitUninterruptibly(2000);
        if (serverChannel != null) {
            serverChannel.close().awaitUninterruptibly(2000);
            serverChannel = null;
        }
    }

    private final class ServerHandler extends SimpleChannelInboundHandler<ByteBuf> {
        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            clients.add(ctx.channel());
            LOG.info("Peer connected {}", ctx.channel().remoteAddress());
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            LOG.info("Peer disconnected {}", ctx.channel().remoteAddress());
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, ByteBuf frame) {
            byte[] raw = NettySupport.bytes(frame);
            IsoMessage req;
            try {
                req = messages.unpack(opts.dialect(), raw);
            } catch (RuntimeException e) {
                LOG.warn("Cannot decode request from {}: {}", ctx.channel().remoteAddress(), e.getMessage());
                return;
            }
            Reply reply;
            try {
                reply = handler.handle(req, raw);
            } catch (RuntimeException e) {
                LOG.error("Request handler failed", e);
                return;
            }
            if (reply == null || reply.response() == null) {
                return;
            }
            IsoMessage resp = reply.response();
            resp.setDialect(opts.dialect());
            byte[] out;
            try {
                out = messages.pack(resp);
            } catch (RuntimeException e) {
                LOG.error("Cannot pack response: {}", e.getMessage());
                return;
            }
            if (reply.delayMs() > 0) {
                ctx.executor().schedule(() -> ctx.writeAndFlush(out), reply.delayMs(), TimeUnit.MILLISECONDS);
            } else {
                ctx.writeAndFlush(out);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            LOG.warn("Server connection error: {}", cause.getMessage());
            ctx.close();
        }
    }
}
