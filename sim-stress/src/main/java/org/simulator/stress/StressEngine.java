package org.simulator.stress;

import org.HdrHistogram.Histogram;
import org.HdrHistogram.Recorder;
import org.simulator.common.event.TransactionEvent.Outcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;

/**
 * Open-model load generator: sends at the rate dictated by {@link LoadProfile} regardless of
 * response times (bounded by maxInFlight), measuring per-second throughput and latency.
 */
public class StressEngine {

    private static final Logger log = LoggerFactory.getLogger(StressEngine.class);
    private static final long MAX_LATENCY_MICROS = 10 * 60 * 1_000_000L;

    public interface Listener {
        default void onSample(StressSample sample) { }

        default void onFinished(StressResult result) { }
    }

    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean stopRequested = new AtomicBoolean();

    public boolean isRunning() {
        return running.get();
    }

    public void stop() {
        stopRequested.set(true);
    }

    /** Runs synchronously until the profile ends, the SLA is violated or {@link #stop()} is called. */
    public StressResult run(LoadProfile profile, SlaPolicy sla, StressTarget target, Listener listener) {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("A stress run is already in progress");
        }
        stopRequested.set(false);
        Listener l = listener != null ? listener : new Listener() { };
        SlaPolicy policy = sla != null ? sla : SlaPolicy.none();
        try {
            return execute(profile, policy, target, l);
        } finally {
            running.set(false);
        }
    }

    private StressResult execute(LoadProfile profile, SlaPolicy sla, StressTarget target, Listener l) {
        Semaphore inFlight = new Semaphore(profile.getMaxInFlight());
        LongAdder sent = new LongAdder();
        LongAdder approved = new LongAdder();
        LongAdder declined = new LongAdder();
        LongAdder timeouts = new LongAdder();
        LongAdder errors = new LongAdder();
        LongAdder throttled = new LongAdder();
        Recorder recorder = new Recorder(3);
        Histogram total = new Histogram(MAX_LATENCY_MICROS, 3);
        List<StressSample> samples = new ArrayList<>();

        long start = System.nanoTime();
        long durationNanos = TimeUnit.SECONDS.toNanos(profile.getDurationSec());
        long nextSampleAt = start + TimeUnit.SECONDS.toNanos(1);
        long last = start;
        double allowance = 0;
        long seq = 0;
        long[] prev = new long[7];
        int violations = 0;
        String status = "COMPLETED";
        String abortReason = null;
        log.info("Stress '{}' started: {} {} TPS for {}s (maxInFlight {})", profile.getName(), profile.getType(),
                profile.getTargetTps(), profile.getDurationSec(), profile.getMaxInFlight());

        while (true) {
            long now = System.nanoTime();
            long elapsed = now - start;
            if (stopRequested.get()) {
                status = "CANCELLED";
                break;
            }
            if (elapsed >= durationNanos) {
                break;
            }
            double tps = profile.tpsAt(elapsed / 1e9);
            allowance = Math.min(allowance + tps * (now - last) / 1e9, Math.max(1, tps));
            last = now;
            while (allowance >= 1) {
                allowance -= 1;
                if (!inFlight.tryAcquire()) {
                    throttled.increment();
                    continue;
                }
                sent.increment();
                long t0 = System.nanoTime();
                CompletableFuture<Outcome> f;
                try {
                    f = target.fire(seq++);
                } catch (RuntimeException e) {
                    f = CompletableFuture.failedFuture(e);
                }
                f.whenComplete((o, err) -> {
                    inFlight.release();
                    long micros = (System.nanoTime() - t0) / 1000;
                    Outcome outcome = err != null || o == null ? Outcome.ERROR : o;
                    switch (outcome) {
                        case APPROVED -> approved.increment();
                        case DECLINED -> declined.increment();
                        case TIMEOUT -> timeouts.increment();
                        case ERROR -> errors.increment();
                    }
                    if (outcome != Outcome.TIMEOUT) {
                        recorder.recordValue(Math.max(1, Math.min(micros, MAX_LATENCY_MICROS)));
                    }
                });
            }
            if (now >= nextSampleAt) {
                int second = samples.size() + 1;
                Histogram h = recorder.getIntervalHistogram();
                total.add(h);
                long[] cur = {sent.sum(), approved.sum(), declined.sum(), timeouts.sum(), errors.sum(), throttled.sum(), 0};
                long completedDelta = (cur[1] - prev[1]) + (cur[2] - prev[2]) + (cur[3] - prev[3]) + (cur[4] - prev[4]);
                StressSample s = new StressSample(second, profile.tpsAt(second - 1), cur[0] - prev[0], completedDelta,
                        cur[1] - prev[1], cur[2] - prev[2], cur[3] - prev[3], cur[4] - prev[4], cur[5] - prev[5],
                        profile.getMaxInFlight() - inFlight.availablePermits(),
                        h.getTotalCount() == 0 ? 0 : h.getValueAtPercentile(50) / 1000.0,
                        h.getTotalCount() == 0 ? 0 : h.getValueAtPercentile(99) / 1000.0);
                prev = cur;
                samples.add(s);
                safe(() -> l.onSample(s));
                nextSampleAt += TimeUnit.SECONDS.toNanos(1);
                if (second > sla.getGraceSec() && violates(sla, s)) {
                    violations++;
                    if (violations >= sla.getConsecutiveSeconds()) {
                        status = "ABORTED_SLA";
                        abortReason = String.format(java.util.Locale.ROOT,
                                "SLA violated for %d s (p99=%.1f ms, errors=%.1f%%)", violations, s.p99Ms(), s.errorRatePct());
                        log.warn("Stress '{}' aborted: {}", profile.getName(), abortReason);
                        break;
                    }
                } else {
                    violations = 0;
                }
            }
            LockSupport.parkNanos(200_000);
        }

        // Drain outstanding requests (bounded wait).
        long drainUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (inFlight.availablePermits() < profile.getMaxInFlight() && System.nanoTime() < drainUntil) {
            LockSupport.parkNanos(1_000_000);
        }
        total.add(recorder.getIntervalHistogram());
        double secs = (System.nanoTime() - start) / 1e9;
        long completed = approved.sum() + declined.sum() + timeouts.sum() + errors.sum();
        boolean empty = total.getTotalCount() == 0;
        StressResult result = new StressResult(profile.getName(), status, abortReason, sent.sum(), approved.sum(),
                declined.sum(), timeouts.sum(), errors.sum(), throttled.sum(), secs,
                Math.min(profile.getDurationSec(), secs) > 0 ? completed / Math.max(secs, 1e-3) : 0,
                empty ? 0 : total.getValueAtPercentile(50) / 1000.0,
                empty ? 0 : total.getValueAtPercentile(95) / 1000.0,
                empty ? 0 : total.getValueAtPercentile(99) / 1000.0,
                empty ? 0 : total.getMaxValue() / 1000.0,
                List.copyOf(samples));
        log.info("Stress '{}' {}: sent={} approved={} declined={} timeouts={} errors={} tps={}",
                profile.getName(), status, result.sent(), result.approved(), result.declined(), result.timeouts(),
                result.errors(), String.format(java.util.Locale.ROOT, "%.1f", result.achievedTps()));
        safe(() -> l.onFinished(result));
        return result;
    }

    private static boolean violates(SlaPolicy sla, StressSample s) {
        return (sla.getMaxP99Ms() > 0 && s.p99Ms() > sla.getMaxP99Ms())
                || (sla.getMaxErrorRatePct() > 0 && s.errorRatePct() > sla.getMaxErrorRatePct());
    }

    private static void safe(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            log.debug("Stress listener failed", e);
        }
    }
}
