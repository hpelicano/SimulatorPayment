package org.simulator.stats;

import org.HdrHistogram.Histogram;
import org.HdrHistogram.Recorder;
import org.simulator.common.event.EventBus;
import org.simulator.common.event.TransactionEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Predicate;

/**
 * Aggregates {@link TransactionEvent}s: counters per outcome, response code, MTI and source,
 * latency percentiles (HdrHistogram) and a per-second TPS history.
 * <p>
 * Thread-safe and lock-free on the recording path, suitable for thousands of TPS.
 */
public class StatisticsCollector implements AutoCloseable {

    private static final long MAX_LATENCY_MICROS = 10 * 60 * 1_000_000L;

    private final int historySeconds;
    private final Predicate<TransactionEvent> filter;
    private final LongAdder total = new LongAdder();
    private final LongAdder approved = new LongAdder();
    private final LongAdder declined = new LongAdder();
    private final LongAdder timeouts = new LongAdder();
    private final LongAdder errors = new LongAdder();
    private final Map<String, LongAdder> byRc = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> byMti = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> bySource = new ConcurrentHashMap<>();
    private final Recorder recorder = new Recorder(3);
    private final Histogram accumulated = new Histogram(MAX_LATENCY_MICROS, 3);
    private final long[] perSecond;
    private final long[] slotEpoch;
    private volatile long startMillis = System.currentTimeMillis();
    private volatile double peakTps;
    private EventBus.Subscription subscription;

    public StatisticsCollector() {
        this(300, e -> true);
    }

    public StatisticsCollector(int historySeconds, Predicate<TransactionEvent> filter) {
        this.historySeconds = historySeconds;
        this.filter = filter;
        this.perSecond = new long[historySeconds + 2];
        this.slotEpoch = new long[historySeconds + 2];
    }

    public StatisticsCollector attach(EventBus bus) {
        detach();
        subscription = bus.subscribe(TransactionEvent.class, this::record);
        return this;
    }

    public void detach() {
        if (subscription != null) {
            subscription.close();
            subscription = null;
        }
    }

    public void record(TransactionEvent e) {
        if (!filter.test(e)) {
            return;
        }
        total.increment();
        switch (e.outcome()) {
            case APPROVED -> approved.increment();
            case DECLINED -> declined.increment();
            case TIMEOUT -> timeouts.increment();
            case ERROR -> errors.increment();
        }
        count(byRc, e.responseCode() != null ? e.responseCode() : e.outcome().name());
        count(byMti, e.mti() != null ? e.mti() : "?");
        count(bySource, e.source() != null ? e.source() : "?");
        if (e.outcome() != TransactionEvent.Outcome.TIMEOUT) {
            recorder.recordValue(Math.max(1, Math.min(e.latencyMicros(), MAX_LATENCY_MICROS)));
        }
        long sec = System.currentTimeMillis() / 1000;
        int slot = (int) (sec % perSecond.length);
        synchronized (perSecond) {
            if (slotEpoch[slot] != sec) {
                slotEpoch[slot] = sec;
                perSecond[slot] = 0;
            }
            perSecond[slot]++;
        }
    }

    private static void count(Map<String, LongAdder> map, String key) {
        map.computeIfAbsent(key, k -> new LongAdder()).increment();
    }

    public synchronized StatsSnapshot snapshot() {
        Histogram interval = recorder.getIntervalHistogram();
        accumulated.add(interval);

        long nowSec = System.currentTimeMillis() / 1000;
        List<Long> history = new ArrayList<>(historySeconds);
        long lastComplete = 0;
        synchronized (perSecond) {
            for (long s = nowSec - historySeconds + 1; s <= nowSec; s++) {
                int slot = (int) (Math.floorMod(s, (long) perSecond.length));
                history.add(slotEpoch[slot] == s ? perSecond[slot] : 0L);
            }
            int prev = (int) Math.floorMod(nowSec - 1, (long) perSecond.length);
            lastComplete = slotEpoch[prev] == nowSec - 1 ? perSecond[prev] : 0;
        }
        peakTps = Math.max(peakTps, lastComplete);
        long elapsed = Math.max(1, (System.currentTimeMillis() - startMillis) / 1000);
        long t = total.sum();
        boolean empty = accumulated.getTotalCount() == 0;
        return new StatsSnapshot(t, approved.sum(), declined.sum(), timeouts.sum(), errors.sum(),
                lastComplete, peakTps, t / (double) elapsed,
                empty ? 0 : accumulated.getMean() / 1000.0,
                ms(accumulated, 50), ms(accumulated, 90), ms(accumulated, 95), ms(accumulated, 99),
                empty ? 0 : accumulated.getMaxValue() / 1000.0,
                freeze(byRc), freeze(byMti), freeze(bySource), Collections.unmodifiableList(history), elapsed);
    }

    private static double ms(Histogram h, double pct) {
        return h.getTotalCount() == 0 ? 0 : h.getValueAtPercentile(pct) / 1000.0;
    }

    private static Map<String, Long> freeze(Map<String, LongAdder> m) {
        Map<String, Long> out = new TreeMap<>();
        m.forEach((k, v) -> out.put(k, v.sum()));
        return Collections.unmodifiableMap(out);
    }

    public synchronized void reset() {
        total.reset();
        approved.reset();
        declined.reset();
        timeouts.reset();
        errors.reset();
        byRc.clear();
        byMti.clear();
        bySource.clear();
        recorder.reset();
        accumulated.reset();
        synchronized (perSecond) {
            java.util.Arrays.fill(perSecond, 0);
            java.util.Arrays.fill(slotEpoch, 0);
        }
        peakTps = 0;
        startMillis = System.currentTimeMillis();
    }

    /** CSV export of the current snapshot (one metric per line). */
    public String toCsv() {
        StatsSnapshot s = snapshot();
        StringBuilder sb = new StringBuilder("metric,value\n");
        sb.append("total,").append(s.total()).append('\n')
                .append("approved,").append(s.approved()).append('\n')
                .append("declined,").append(s.declined()).append('\n')
                .append("timeouts,").append(s.timeouts()).append('\n')
                .append("errors,").append(s.errors()).append('\n')
                .append("average_tps,").append(String.format(java.util.Locale.ROOT, "%.2f", s.averageTps())).append('\n')
                .append("peak_tps,").append(s.peakTps()).append('\n')
                .append("p50_ms,").append(s.p50Ms()).append('\n')
                .append("p95_ms,").append(s.p95Ms()).append('\n')
                .append("p99_ms,").append(s.p99Ms()).append('\n')
                .append("max_ms,").append(s.maxMs()).append('\n');
        s.byResponseCode().forEach((k, v) -> sb.append("rc_").append(k).append(',').append(v).append('\n'));
        return sb.toString();
    }

    @Override
    public void close() {
        detach();
    }
}
