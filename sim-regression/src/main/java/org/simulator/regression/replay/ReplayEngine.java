package org.simulator.regression.replay;

import org.simulator.acquirer.AcquirerService;
import org.simulator.acquirer.TransactionOutcome;
import org.simulator.iso.IsoMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/**
 * Re-sends a {@link Recording} through the acquirer keeping the original inter-arrival times
 * (scaled by {@code speed}; 0 = as fast as possible) and compares response codes.
 */
public class ReplayEngine {

    private static final Logger log = LoggerFactory.getLogger(ReplayEngine.class);

    private final AcquirerService acquirer;
    private final AtomicBoolean cancelled = new AtomicBoolean();

    public ReplayEngine(AcquirerService acquirer) {
        this.acquirer = acquirer;
    }

    public void cancel() {
        cancelled.set(true);
    }

    /**
     * @param sources  sources to replay (e.g. ACQUIRER, REGRESSION, ISSUER); null/empty = all
     * @param progress optional (done, total) callback
     */
    public ReplayReport replay(Recording rec, double speed, boolean refreshDynamicFields, Set<String> sources,
                               BiConsumer<Integer, Integer> progress) {
        cancelled.set(false);
        List<RecordedTransaction> items = rec.getItems().stream()
                .filter(t -> sources == null || sources.isEmpty() || sources.contains(t.source()))
                .toList();
        Instant started = Instant.now();
        long t0 = System.nanoTime();
        long firstOffset = items.isEmpty() ? 0 : items.get(0).offsetMs();
        List<CompletableFuture<ReplayReport.Item>> futures = new ArrayList<>();
        for (int i = 0; i < items.size() && !cancelled.get(); i++) {
            RecordedTransaction t = items.get(i);
            if (speed > 0) {
                long due = (long) ((t.offsetMs() - firstOffset) / speed);
                long wait = due - (System.nanoTime() - t0) / 1_000_000;
                if (wait > 0) sleep(wait);
            }
            futures.add(send(i + 1, t, refreshDynamicFields));
            if (progress != null) progress.accept(i + 1, items.size());
        }
        List<ReplayReport.Item> out = new ArrayList<>();
        for (CompletableFuture<ReplayReport.Item> f : futures) {
            try {
                out.add(f.get(60, TimeUnit.SECONDS));
            } catch (Exception e) {
                log.warn("Replay item failed: {}", e.getMessage());
            }
        }
        ReplayReport report = new ReplayReport(rec.getName(), acquirer.getEnvironment(), started, Instant.now(), speed, out);
        log.info("Replay '{}' finished: {}/{} RC matches", rec.getName(), report.matches(), out.size());
        return report;
    }

    private CompletableFuture<ReplayReport.Item> send(int index, RecordedTransaction t, boolean refresh) {
        IsoMessage req;
        try {
            req = acquirer.messages().fromJson(t.requestJson());
            if (refresh) refresh(req);
        } catch (RuntimeException e) {
            return CompletableFuture.completedFuture(new ReplayReport.Item(index, t.source(), t.mti(), null, t.responseRc(),
                    null, "ERROR", false, t.latencyMicros(), 0, e.getMessage()));
        }
        String stan = req.get(11);
        return acquirer.send(req, null, "REPLAY").thenApply((TransactionOutcome o) -> new ReplayReport.Item(index,
                t.source(), t.mti(), stan, t.responseRc(), o.responseCode(), o.status().name(),
                Objects.equals(t.responseRc(), o.responseCode()) && Objects.equals(t.status(), o.status().name()),
                t.latencyMicros(), o.latencyMicros(), o.error()));
    }

    private void refresh(IsoMessage req) {
        String stan = acquirer.sequences().nextStan();
        if (req.has(11)) req.set(11, stan);
        if (req.has(37)) req.set(37, acquirer.sequences().rrn(stan));
        if (req.has(7)) req.set(7, acquirer.transmissionDateTime());
        Map<String, String> none = Map.of();
        if (req.has(12)) req.set(12, acquirer.templateEngine().resolve("${LOCAL_TIME}", none));
        if (req.has(13)) req.set(13, acquirer.templateEngine().resolve("${LOCAL_DATE}", none));
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}