package org.simulator.issuer;

import org.simulator.common.event.EventBus;
import org.simulator.common.event.TransactionEvent;
import org.simulator.iso.IsoMessage;
import org.simulator.iso.MessageService;
import org.simulator.transport.IsoClient;
import org.simulator.transport.IsoServer;
import org.simulator.transport.TransportOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BinaryOperator;
import java.util.function.Consumer;

/**
 * Issuer module: listens for authorization requests (e.g. from Base24 acting as acquirer/switch)
 * and answers them according to the configured {@link IssuerRules}.
 */
public class IssuerSimulator implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(IssuerSimulator.class);

    /** Live traffic notification for the UI. {@code response} is null when no reply was sent. */
    public record Traffic(IsoMessage request, IsoMessage response, String rule, long delayMs, Instant at, String route) {
        public Traffic(IsoMessage request, IsoMessage response, String rule, long delayMs, Instant at) {
            this(request, response, rule, delayMs, at, Routing.FULL_DUPLEX.name());
        }
    }

    /**
     * How responses reach the originator.
     * <ul>
     *   <li>{@code FULL_DUPLEX}: on the same connection that carried the request (port 1).</li>
     *   <li>{@code SPLIT_LISTEN}: on a second listening port (port 2) the originator connects to.</li>
     *   <li>{@code SPLIT_CONNECT}: on an outgoing connection to the originator host/port 2.</li>
     * </ul>
     */
    public enum Routing {
        FULL_DUPLEX, SPLIT_LISTEN, SPLIT_CONNECT;

        public static Routing parse(String value) {
            if (value == null || value.isBlank()) {
                return FULL_DUPLEX;
            }
            return Routing.valueOf(value.trim().toUpperCase());
        }
    }

    private final MessageService messages;
    private final EventBus bus;
    private final IssuerRuleEngine engine;
    private final AtomicLong handled = new AtomicLong();
    private final AtomicLong routeErrors = new AtomicLong();
    private volatile Consumer<Traffic> trafficListener;
    private final List<Consumer<Traffic>> trafficObservers = new CopyOnWriteArrayList<>();
    private final List<Consumer<String>> routeErrorListeners = new CopyOnWriteArrayList<>();
    private volatile BinaryOperator<IsoMessage> responseProcessor;
    private IsoServer server;
    private volatile Routing routing = Routing.FULL_DUPLEX;
    private volatile TransportOptions outboundOptions;
    private volatile IsoServer outboundServer;
    private volatile IsoClient outboundClient;
    private volatile ScheduledExecutorService scheduler;

    public IssuerSimulator(MessageService messages, EventBus bus, IssuerRules rules) {
        this.messages = messages;
        this.bus = bus;
        this.engine = new IssuerRuleEngine(messages, rules);
    }

    /** Full-duplex server: responses go back on the same connection. */
    public synchronized IssuerSimulator start(TransportOptions options) {
        return start(options, Routing.FULL_DUPLEX, null);
    }

    /**
     * Starts the server on the input port. With a split routing the responses are sent through
     * {@code outbound} (port 2), either listening on it or connecting to the originator.
     */
    public synchronized IssuerSimulator start(TransportOptions inbound, Routing mode, TransportOptions outbound) {
        stop();
        Routing r = mode == null ? Routing.FULL_DUPLEX : mode;
        if (r != Routing.FULL_DUPLEX && outbound == null) {
            throw new IllegalArgumentException("Output port options are required for routing " + r);
        }
        server = new IsoServer(inbound, messages, this::handle).start();
        try {
            routing = r;
            outboundOptions = r == Routing.FULL_DUPLEX ? null : outbound;
            if (r == Routing.SPLIT_LISTEN) {
                outboundServer = new IsoServer(outbound, messages, this::onOutputPortMessage).start();
            } else if (r == Routing.SPLIT_CONNECT) {
                outboundClient = new IsoClient(outbound, messages);
                outboundClient.setUnsolicitedListener(m -> log.info("Message received on output link: MTI {}", m.getMti()));
                try {
                    outboundClient.connect();
                } catch (RuntimeException e) {
                    log.warn("Output link {}:{} not available yet ({}); will retry on first response",
                            outbound.host(), outbound.port(), e.getMessage());
                }
            }
            if (r != Routing.FULL_DUPLEX) {
                scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread t = new Thread(runnable, "issuer-output-router");
                    t.setDaemon(true);
                    return t;
                });
            }
        } catch (RuntimeException e) {
            stop();
            throw e;
        }
        log.info("Issuer simulator started on port {} ({}{})", server.port(), r,
                r == Routing.FULL_DUPLEX ? "" : " -> " + outboundDescription());
        return this;
    }

    private IsoServer.Reply onOutputPortMessage(IsoMessage msg, byte[] raw) {
        log.info("Message received on output port (ignored): MTI {} STAN {}", msg.getMti(), msg.get(11));
        return IsoServer.Reply.none();
    }

    private IsoServer.Reply handle(IsoMessage req, byte[] raw) {
        handled.incrementAndGet();
        IssuerRuleEngine.Decision d;
        try {
            d = engine.decide(req);
        } catch (RuntimeException e) {
            log.warn("Rule evaluation failed for MTI {} STAN {}: {}", req.getMti(), req.get(11), e.getMessage());
            publish(req, null, TransactionEvent.Outcome.ERROR, 0);
            return IsoServer.Reply.none();
        }
        IsoMessage response = d.response();
        BinaryOperator<IsoMessage> processor = responseProcessor;
        if (response != null && processor != null) {
            try {
                response = processor.apply(req, response);
            } catch (RuntimeException e) {
                log.warn("Response post-processing failed for STAN {}: {}", req.get(11), e.getMessage());
            }
        }
        String rc = response != null ? response.get(39) : null;
        TransactionEvent.Outcome outcome = response == null
                ? TransactionEvent.Outcome.TIMEOUT : TransactionEvent.outcomeFor(rc);
        publish(req, rc, outcome, d.delayMs() * 1000);
        Routing r = routing;
        Traffic traffic = new Traffic(req, response, d.ruleName(), d.delayMs(), Instant.now(), r.name());
        Consumer<Traffic> l = trafficListener;
        if (l != null) {
            notify(l, traffic);
        }
        trafficObservers.forEach(o -> notify(o, traffic));
        if (r == Routing.FULL_DUPLEX) {
            return new IsoServer.Reply(response, d.delayMs());
        }
        if (response != null) {
            IsoMessage out = response;
            ScheduledExecutorService s = scheduler;
            if (s == null || d.delayMs() <= 0) {
                route(out);
            } else {
                s.schedule(() -> route(out), d.delayMs(), TimeUnit.MILLISECONDS);
            }
        }
        return IsoServer.Reply.none();
    }

    /** Sends a message through the output port (split routing). */
    private void route(IsoMessage msg) {
        try {
            TransportOptions o = outboundOptions;
            if (o != null) {
                msg.setDialect(o.dialect());
            }
            IsoServer out = outboundServer;
            IsoClient client = outboundClient;
            if (out != null) {
                if (out.connectionCount() == 0) {
                    throw new IllegalStateException("no peer connected on output port " + out.port());
                }
                out.broadcast(msg);
            } else if (client != null) {
                if (!client.isConnected()) {
                    client.connect();
                }
                client.sendOneWay(msg);
            } else {
                throw new IllegalStateException("output link is not started");
            }
        } catch (RuntimeException e) {
            routeErrors.incrementAndGet();
            String error = "Response " + msg.getMti() + " STAN " + msg.get(11) + " not routed: " + e.getMessage();
            log.warn(error);
            routeErrorListeners.forEach(listener -> {
                try {
                    listener.accept(error);
                } catch (RuntimeException ignored) {
                    // UI listener failures must not affect routing
                }
            });
        }
    }

    private static void notify(Consumer<Traffic> listener, Traffic traffic) {
        try {
            listener.accept(traffic);
        } catch (RuntimeException e) {
            log.debug("Traffic listener failed", e);
        }
    }

    private void publish(IsoMessage req, String rc, TransactionEvent.Outcome outcome, long micros) {
        if (bus == null) {
            return;
        }
        String pan = req.get(2);
        bus.publish(new TransactionEvent("ISSUER", req.getMti(), req.get(3),
                pan != null && pan.length() >= 6 ? pan.substring(0, 6) : null, rc, outcome, micros, Instant.now()));
    }

    /** Sends an unsolicited message (e.g. 0800 sign-on, 0620 administrative) to every connected peer. */
    public void broadcast(IsoMessage msg) {
        if (routing != Routing.FULL_DUPLEX) {
            route(msg);
            return;
        }
        IsoServer s = server;
        if (s != null) {
            s.broadcast(msg);
        }
    }

    public synchronized void stop() {
        ScheduledExecutorService s = scheduler;
        scheduler = null;
        if (s != null) {
            s.shutdownNow();
        }
        if (outboundServer != null) {
            outboundServer.close();
            outboundServer = null;
        }
        if (outboundClient != null) {
            outboundClient.close();
            outboundClient = null;
        }
        outboundOptions = null;
        routing = Routing.FULL_DUPLEX;
        if (server != null) {
            server.close();
            server = null;
            log.info("Issuer simulator stopped");
        }
    }

    public Routing routing() {
        return routing;
    }

    /** Human readable output link, e.g. "escucha :9101" or "-> 10.0.0.5:9101". */
    public String outboundDescription() {
        TransportOptions o = outboundOptions;
        if (o == null) {
            return "mismo socket";
        }
        return routing == Routing.SPLIT_LISTEN ? "escucha :" + (outboundServer != null ? outboundServer.port() : o.port())
                : "-> " + o.host() + ":" + o.port();
    }

    /** Peers able to receive responses on the output port (split routing). */
    public int outboundConnections() {
        IsoServer out = outboundServer;
        if (out != null) {
            return out.connectionCount();
        }
        IsoClient client = outboundClient;
        return client != null && client.isConnected() ? 1 : 0;
    }

    public int outboundPort() {
        IsoServer out = outboundServer;
        if (out != null) {
            return out.port();
        }
        TransportOptions o = outboundOptions;
        return o == null ? -1 : o.port();
    }

    public long routeErrorCount() {
        return routeErrors.get();
    }

    public void addRouteErrorListener(Consumer<String> listener) {
        routeErrorListeners.add(listener);
    }

    public void removeRouteErrorListener(Consumer<String> listener) {
        routeErrorListeners.remove(listener);
    }

    public boolean isRunning() {
        IsoServer s = server;
        return s != null && s.isRunning();
    }

    public int port() {
        return server != null ? server.port() : -1;
    }

    public int connections() {
        return server != null ? server.connectionCount() : 0;
    }

    public long handledCount() {
        return handled.get();
    }

    public IssuerRuleEngine engine() {
        return engine;
    }

    /** Hook applied to every response before it is sent (e.g. ARPC and MAC generation). */
    public void setResponseProcessor(BinaryOperator<IsoMessage> processor) {
        this.responseProcessor = processor;
    }

    public void addTrafficObserver(Consumer<Traffic> observer) {
        trafficObservers.add(observer);
    }

    public void removeTrafficObserver(Consumer<Traffic> observer) {
        trafficObservers.remove(observer);
    }

    public void setTrafficListener(Consumer<Traffic> listener) {
        this.trafficListener = listener;
    }

    @Override
    public void close() {
        stop();
    }
}
