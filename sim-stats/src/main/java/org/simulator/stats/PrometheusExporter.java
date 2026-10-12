package org.simulator.stats;

import com.sun.net.httpserver.HttpServer;
import org.simulator.common.event.EventBus;
import org.simulator.common.event.TransactionEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

/**
 * Exposes simulator metrics in Prometheus text format (v0.0.4) on {@code http://host:port/metrics}.
 * Counters are monotonic since start (independent of the dashboard "reset"); latency is a
 * histogram per source so it can be aggregated in Grafana with {@code histogram_quantile}.
 */
public class PrometheusExporter implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PrometheusExporter.class);
    static final double[] BUCKETS = {0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10};

    private final Map<String, LongAdder> byOutcome = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> byRc = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> byMti = new ConcurrentHashMap<>();
    private final Map<String, Histo> latency = new ConcurrentHashMap<>();
    private final Map<String, Gauge> gauges = new ConcurrentHashMap<>();
    private final Supplier<StatsSnapshot> snapshot;
    private EventBus.Subscription subscription;
    private HttpServer server;

    private record Gauge(String help, DoubleSupplier value) {}

    private static final class Histo {
        final LongAdder[] buckets = new LongAdder[BUCKETS.length];
        final LongAdder count = new LongAdder();
        final java.util.concurrent.atomic.DoubleAdder sum = new java.util.concurrent.atomic.DoubleAdder();

        Histo() {
            for (int i = 0; i < buckets.length; i++) buckets[i] = new LongAdder();
        }

        void observe(double seconds) {
            for (int i = 0; i < BUCKETS.length; i++) {
                if (seconds <= BUCKETS[i]) buckets[i].increment();
            }
            count.increment();
            sum.add(seconds);
        }
    }

    /** @param snapshot optional live stats (TPS / percentiles gauges); may be null */
    public PrometheusExporter(Supplier<StatsSnapshot> snapshot) {
        this.snapshot = snapshot;
    }

    public PrometheusExporter attach(EventBus bus) {
        detach();
        subscription = bus.subscribe(TransactionEvent.class, this::record);
        return this;
    }

    public void detach() {
        if (subscription != null) {
            try {
                subscription.close();
            } catch (Exception ignored) {
                // nothing to release
            }
            subscription = null;
        }
    }

    public void registerGauge(String name, String help, DoubleSupplier value) {
        gauges.put(name, new Gauge(help, value));
    }

    public void record(TransactionEvent e) {
        String src = nz(e.source());
        byOutcome.computeIfAbsent(src + '\u0000' + e.outcome(), k -> new LongAdder()).increment();
        byRc.computeIfAbsent(src + '\u0000' + (e.responseCode() == null ? "none" : e.responseCode()), k -> new LongAdder()).increment();
        byMti.computeIfAbsent(nz(e.mti()), k -> new LongAdder()).increment();
        if (e.outcome() != TransactionEvent.Outcome.TIMEOUT) {
            latency.computeIfAbsent(src, k -> new Histo()).observe(e.latencyMicros() / 1e6);
        }
    }

    public synchronized PrometheusExporter start(int port) throws IOException {
        stop();
        server = HttpServer.create(new InetSocketAddress(port), 8);
        server.createContext("/metrics", ex -> {
            byte[] body = scrape().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "text/plain; version=0.0.4; charset=utf-8");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        log.info("Prometheus metrics on http://localhost:{}/metrics", port());
        return this;
    }

    public synchronized void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    public boolean isRunning() {
        return server != null;
    }

    public int port() {
        return server == null ? -1 : server.getAddress().getPort();
    }

    public String scrape() {
        StringBuilder sb = new StringBuilder(4096);
        header(sb, "simulator_transactions_total", "counter", "Completed transactions by source and outcome");
        new TreeMap<>(byOutcome).forEach((k, v) -> {
            String[] p = k.split("\u0000", 2);
            sb.append("simulator_transactions_total{source=\"").append(esc(p[0])).append("\",outcome=\"").append(p[1])
                    .append("\"} ").append(v.sum()).append('\n');
        });
        header(sb, "simulator_response_codes_total", "counter", "Responses by source and DE39 response code");
        new TreeMap<>(byRc).forEach((k, v) -> {
            String[] p = k.split("\u0000", 2);
            sb.append("simulator_response_codes_total{source=\"").append(esc(p[0])).append("\",rc=\"").append(esc(p[1]))
                    .append("\"} ").append(v.sum()).append('\n');
        });
        header(sb, "simulator_transactions_by_mti_total", "counter", "Completed transactions by request MTI");
        new TreeMap<>(byMti).forEach((k, v) -> sb.append("simulator_transactions_by_mti_total{mti=\"").append(esc(k))
                .append("\"} ").append(v.sum()).append('\n'));
        header(sb, "simulator_latency_seconds", "histogram", "Round-trip latency (timeouts excluded)");
        new TreeMap<>(latency).forEach((src, h) -> {
            for (int i = 0; i < BUCKETS.length; i++) {
                sb.append("simulator_latency_seconds_bucket{source=\"").append(esc(src)).append("\",le=\"")
                        .append(num(BUCKETS[i])).append("\"} ").append(h.buckets[i].sum()).append('\n');
            }
            sb.append("simulator_latency_seconds_bucket{source=\"").append(esc(src)).append("\",le=\"+Inf\"} ")
                    .append(h.count.sum()).append('\n');
            sb.append("simulator_latency_seconds_sum{source=\"").append(esc(src)).append("\"} ").append(num(h.sum.sum())).append('\n');
            sb.append("simulator_latency_seconds_count{source=\"").append(esc(src)).append("\"} ").append(h.count.sum()).append('\n');
        });
        if (snapshot != null) {
            StatsSnapshot s = snapshot.get();
            gauge(sb, "simulator_tps_current", "Transactions per second (last second)", s.currentTps());
            gauge(sb, "simulator_tps_peak", "Peak TPS since last dashboard reset", s.peakTps());
            gauge(sb, "simulator_latency_p50_ms", "Latency p50 in ms (since last reset)", s.p50Ms());
            gauge(sb, "simulator_latency_p95_ms", "Latency p95 in ms (since last reset)", s.p95Ms());
            gauge(sb, "simulator_latency_p99_ms", "Latency p99 in ms (since last reset)", s.p99Ms());
            gauge(sb, "simulator_approval_rate", "Approved / total (since last reset)", s.approvalRate());
        }
        new TreeMap<>(gauges).forEach((name, g) -> {
            double v;
            try {
                v = g.value().getAsDouble();
            } catch (RuntimeException e) {
                v = Double.NaN;
            }
            gauge(sb, name, g.help(), v);
        });
        return sb.toString();
    }

    private static void gauge(StringBuilder sb, String name, String help, double v) {
        header(sb, name, "gauge", help);
        sb.append(name).append(' ').append(num(v)).append('\n');
    }

    private static void header(StringBuilder sb, String name, String type, String help) {
        sb.append("# HELP ").append(name).append(' ').append(help).append('\n');
        sb.append("# TYPE ").append(name).append(' ').append(type).append('\n');
    }

    private static String num(double d) {
        if (Double.isNaN(d)) return "NaN";
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return String.valueOf((long) d);
        if (Double.isInfinite(d)) return d > 0 ? "+Inf" : "-Inf";
        return java.math.BigDecimal.valueOf(d).round(new java.math.MathContext(9)).stripTrailingZeros().toPlainString();
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    private static String nz(String s) {
        return s == null ? "UNKNOWN" : s;
    }

    @Override
    public void close() {
        stop();
        detach();
    }
}