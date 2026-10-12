package org.simulator.stress.distributed;

import org.simulator.common.SimulatorException;
import org.simulator.common.json.Json;
import org.simulator.stress.StressResult;
import org.simulator.stress.StressSample;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Splits a load profile evenly across N agents, starts them together, polls their progress every
 * second and aggregates per-second samples and final results. Percentiles are aggregated as the
 * maximum across agents (conservative upper bound).
 */
public class StressCoordinator {

    private static final Logger log = LoggerFactory.getLogger(StressCoordinator.class);

    public record Agent(String url, String token) {
        String base() {
            return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        }
    }

    private final List<Agent> agents;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final AtomicBoolean stop = new AtomicBoolean();

    public StressCoordinator(List<Agent> agents) {
        if (agents == null || agents.isEmpty()) throw new IllegalArgumentException("At least one agent is required");
        this.agents = List.copyOf(agents);
    }

    /** Parses "http://host:port@token" lines. */
    public static List<Agent> parse(String spec) {
        List<Agent> out = new ArrayList<>();
        for (String line : spec.split("[\\r\\n,;]+")) {
            String s = line.trim();
            if (s.isEmpty() || s.startsWith("#")) continue;
            int at = s.lastIndexOf('@');
            if (at <= 0) throw new IllegalArgumentException("Formato de agente: http://host:puerto@token -> " + s);
            out.add(new Agent(s.substring(0, at), s.substring(at + 1)));
        }
        return out;
    }

    public void stop() {
        stop.set(true);
        for (Agent a : agents) {
            try {
                post(a, "/stop", "{}");
            } catch (RuntimeException e) {
                log.warn("Cannot stop agent {}: {}", a.url(), e.getMessage());
            }
        }
    }

    public Map<String, Boolean> health() {
        Map<String, Boolean> m = new HashMap<>();
        for (Agent a : agents) {
            try {
                m.put(a.url(), call(HttpRequest.newBuilder(URI.create(a.base() + "/health")).GET(), a).statusCode() == 200);
            } catch (RuntimeException e) {
                m.put(a.url(), false);
            }
        }
        return m;
    }

    public StressResult run(AgentRequest request, Consumer<StressSample> onAggregatedSample) {
        stop.set(false);
        int n = agents.size();
        String body = Json.write(new AgentRequest().setProfile(request.getProfile().scaled(1.0 / n)).setSla(request.getSla())
                .setTemplateId(request.getTemplateId()).setVariables(request.getVariables()));
        for (Agent a : agents) {
            HttpResponse<String> r = post(a, "/run", body);
            if (r.statusCode() != 202) {
                stop();
                throw new SimulatorException("Agente " + a.url() + " rechazó la corrida: HTTP " + r.statusCode() + " " + r.body());
            }
        }
        log.info("Distributed stress '{}' started on {} agents", request.getProfile().getName(), n);
        int emitted = 0;
        Map<String, AgentStatus> last = new HashMap<>();
        long deadline = System.currentTimeMillis() + (request.getProfile().getDurationSec() + 90L) * 1000;
        while (true) {
            sleep(1000);
            for (Agent a : agents) {
                try {
                    HttpResponse<String> r = call(HttpRequest.newBuilder(URI.create(a.base() + "/status")).GET(), a);
                    if (r.statusCode() == 200) last.put(a.url(), Json.read(r.body(), AgentStatus.class));
                } catch (RuntimeException e) {
                    log.warn("Agent {} status failed: {}", a.url(), e.getMessage());
                }
            }
            int ready = last.values().stream().mapToInt(s -> s.samples() == null ? 0 : s.samples().size()).min().orElse(0);
            if (last.size() < n) ready = 0;
            List<StressSample> agg = aggregateSamples(last.values().stream().map(AgentStatus::samples).toList());
            while (emitted < Math.min(ready, agg.size())) {
                if (onAggregatedSample != null) onAggregatedSample.accept(agg.get(emitted));
                emitted++;
            }
            boolean done = last.size() == n && last.values().stream().allMatch(s -> !s.running() && (s.result() != null || s.error() != null));
            if (done || System.currentTimeMillis() > deadline) break;
        }
        List<StressResult> results = last.values().stream().map(AgentStatus::result).filter(r -> r != null).toList();
        if (results.isEmpty()) {
            throw new SimulatorException("Ningún agente devolvió resultado: " + last.values().stream().map(AgentStatus::error).toList());
        }
        StressResult merged = merge(request.getProfile().getName(), results);
        List<StressSample> agg = aggregateSamples(last.values().stream().map(AgentStatus::samples).toList());
        while (emitted < agg.size()) {
            if (onAggregatedSample != null) onAggregatedSample.accept(agg.get(emitted));
            emitted++;
        }
        return merged;
    }

    static List<StressSample> aggregateSamples(List<List<StressSample>> perAgent) {
        TreeMap<Integer, StressSample> bySecond = new TreeMap<>();
        for (List<StressSample> list : perAgent) {
            if (list == null) continue;
            for (StressSample s : list) {
                bySecond.merge(s.second(), s, (a, b) -> new StressSample(a.second(), a.targetTps() + b.targetTps(),
                        a.sent() + b.sent(), a.completed() + b.completed(), a.approved() + b.approved(),
                        a.declined() + b.declined(), a.timeouts() + b.timeouts(), a.errors() + b.errors(),
                        a.throttled() + b.throttled(), a.inFlight() + b.inFlight(), Math.max(a.p50Ms(), b.p50Ms()),
                        Math.max(a.p99Ms(), b.p99Ms())));
            }
        }
        return new ArrayList<>(bySecond.values());
    }

    static StressResult merge(String name, List<StressResult> results) {
        long sent = 0, approved = 0, declined = 0, timeouts = 0, errors = 0, throttled = 0;
        double dur = 0, tps = 0, p50 = 0, p95 = 0, p99 = 0, max = 0;
        String status = "COMPLETED";
        String reason = null;
        List<List<StressSample>> samples = new ArrayList<>();
        for (StressResult r : results) {
            sent += r.sent();
            approved += r.approved();
            declined += r.declined();
            timeouts += r.timeouts();
            errors += r.errors();
            throttled += r.throttled();
            dur = Math.max(dur, r.durationSec());
            tps += r.achievedTps();
            p50 = Math.max(p50, r.p50Ms());
            p95 = Math.max(p95, r.p95Ms());
            p99 = Math.max(p99, r.p99Ms());
            max = Math.max(max, r.maxMs());
            if (!"COMPLETED".equals(r.status())) {
                status = r.status();
                reason = r.abortReason();
            }
            samples.add(r.samples());
        }
        return new StressResult(name + " (distribuido x" + results.size() + ")", status, reason, sent, approved, declined,
                timeouts, errors, throttled, dur, tps, p50, p95, p99, max, aggregateSamples(samples));
    }

    private HttpResponse<String> post(Agent a, String path, String body) {
        return call(HttpRequest.newBuilder(URI.create(a.base() + path))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)), a);
    }

    private HttpResponse<String> call(HttpRequest.Builder b, Agent a) {
        try {
            return http.send(b.header(StressAgentServer.TOKEN_HEADER, a.token()).timeout(Duration.ofSeconds(10)).build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SimulatorException("Interrupted", e);
        } catch (java.io.IOException e) {
            throw new SimulatorException("Agente " + a.url() + " no responde: " + e.getMessage(), e);
        }
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}