package org.simulator.stress.distributed;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.simulator.common.json.Json;
import org.simulator.stress.StressEngine;
import org.simulator.stress.StressResult;
import org.simulator.stress.StressSample;
import org.simulator.stress.StressTarget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * HTTP control endpoint of a stress agent (run with {@code --headless agent}).
 * <pre>
 *   POST /run     body AgentRequest   -> 202 | 409 if busy
 *   GET  /status                      -> AgentStatus
 *   POST /stop                        -> 200
 *   GET  /health                      -> 200 (no token)
 * </pre>
 * Every call except /health requires the header {@code X-Agent-Token}.
 */
public class StressAgentServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(StressAgentServer.class);
    public static final String TOKEN_HEADER = "X-Agent-Token";

    private final String name;
    private final byte[] token;
    private final Function<AgentRequest, StressTarget> targetFactory;
    private final StressEngine engine = new StressEngine();
    private final List<StressSample> samples = new ArrayList<>();
    private volatile StressResult result;
    private volatile String error;
    private HttpServer server;

    public StressAgentServer(String name, String token, Function<AgentRequest, StressTarget> targetFactory) {
        if (token == null || token.length() < 8) {
            throw new IllegalArgumentException("Agent token must have at least 8 characters");
        }
        this.name = name;
        this.token = token.getBytes(StandardCharsets.UTF_8);
        this.targetFactory = targetFactory;
    }

    public StressAgentServer start(String bindAddress, int port) throws IOException {
        InetAddress addr = bindAddress == null ? null : InetAddress.getByName(bindAddress);
        server = HttpServer.create(addr == null ? new InetSocketAddress(port) : new InetSocketAddress(addr, port), 16);
        server.setExecutor(Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "stress-agent-http");
            t.setDaemon(true);
            return t;
        }));
        server.createContext("/health", ex -> send(ex, 200, "{\"agent\":\"" + name + "\",\"ok\":true}"));
        server.createContext("/run", ex -> guarded(ex, "POST", () -> run(ex)));
        server.createContext("/status", ex -> guarded(ex, "GET", () -> send(ex, 200, Json.write(status()))));
        server.createContext("/stop", ex -> guarded(ex, "POST", () -> {
            engine.stop();
            send(ex, 200, "{\"stopping\":true}");
        }));
        server.start();
        log.info("Stress agent '{}' listening on port {}", name, port());
        return this;
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public synchronized AgentStatus status() {
        return new AgentStatus(name, engine.isRunning(), List.copyOf(samples), result, error);
    }

    private void run(HttpExchange ex) throws IOException {
        AgentRequest req;
        StressTarget target;
        try {
            req = Json.read(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), AgentRequest.class);
            target = targetFactory.apply(req);
        } catch (RuntimeException e) {
            send(ex, 400, "{\"error\":" + Json.write(String.valueOf(e.getMessage())) + "}");
            return;
        }
        synchronized (this) {
            if (engine.isRunning()) {
                send(ex, 409, "{\"error\":\"busy\"}");
                return;
            }
            samples.clear();
            result = null;
            error = null;
        }
        Thread t = new Thread(() -> {
            try {
                engine.run(req.getProfile(), req.getSla(), target, new StressEngine.Listener() {
                    @Override
                    public void onSample(StressSample s) {
                        synchronized (StressAgentServer.this) {
                            samples.add(s);
                        }
                    }

                    @Override
                    public void onFinished(StressResult r) {
                        result = r;
                    }
                });
            } catch (RuntimeException e) {
                error = e.getMessage();
                log.warn("Agent run failed: {}", e.getMessage());
            }
        }, "stress-agent-run");
        t.setDaemon(true);
        t.start();
        long until = System.currentTimeMillis() + 2000;
        while (!engine.isRunning() && result == null && error == null && System.currentTimeMillis() < until) {
            Thread.onSpinWait();
        }
        send(ex, 202, "{\"accepted\":true,\"agent\":\"" + name + "\"}");
    }

    private interface IoAction {
        void run() throws IOException;
    }

    private void guarded(HttpExchange ex, String method, IoAction action) throws IOException {
        try {
            String got = ex.getRequestHeaders().getFirst(TOKEN_HEADER);
            if (got == null || !MessageDigest.isEqual(token, got.getBytes(StandardCharsets.UTF_8))) {
                send(ex, 401, "{\"error\":\"unauthorized\"}");
                return;
            }
            if (!method.equalsIgnoreCase(ex.getRequestMethod())) {
                send(ex, 405, "{\"error\":\"method not allowed\"}");
                return;
            }
            action.run();
        } catch (RuntimeException e) {
            send(ex, 500, "{\"error\":" + Json.write(String.valueOf(e.getMessage())) + "}");
        }
    }

    private static void send(HttpExchange ex, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    @Override
    public void close() {
        engine.stop();
        if (server != null) {
            server.stop(0);
            ((java.util.concurrent.ExecutorService) server.getExecutor()).shutdownNow();
        }
    }
}