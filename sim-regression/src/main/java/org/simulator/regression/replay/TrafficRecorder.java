package org.simulator.regression.replay;

import org.simulator.acquirer.TransactionOutcome;
import org.simulator.iso.IsoMessage;
import org.simulator.iso.MessageService;

import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Captures live traffic while active. Feed it from {@code AcquirerService.addOutcomeListener(recorder::onOutcome)}
 * and from issuer traffic via {@link #record}.
 */
public class TrafficRecorder {

    private final MessageService messages;
    private final AtomicReference<Recording> current = new AtomicReference<>();
    private final Set<String> ignoredSources;
    private volatile long startNanos;

    public TrafficRecorder(MessageService messages) {
        this(messages, Set.of("REPLAY", "STRESS"));
    }

    public TrafficRecorder(MessageService messages, Set<String> ignoredSources) {
        this.messages = messages;
        this.ignoredSources = ignoredSources;
    }

    public synchronized void start(String name, String environment) {
        startNanos = System.nanoTime();
        current.set(new Recording(name, environment));
    }

    public synchronized Recording stop() {
        return current.getAndSet(null);
    }

    public boolean isRecording() {
        return current.get() != null;
    }

    public int size() {
        Recording r = current.get();
        return r == null ? 0 : r.getItems().size();
    }

    public void onOutcome(TransactionOutcome o, String source) {
        record(source, o.request(), o.responseCode(), o.status().name(), o.latencyMicros());
    }

    public void record(String source, IsoMessage request, String rc, String status, long latencyMicros) {
        Recording r = current.get();
        if (r == null || request == null || (source != null && ignoredSources.contains(source))) {
            return;
        }
        long offset = (System.nanoTime() - startNanos) / 1_000_000;
        RecordedTransaction t = new RecordedTransaction(offset, source, request.getMti(), messages.toJson(request.replayCopy()), rc,
                status, latencyMicros);
        synchronized (r) {
            r.getItems().add(t);
        }
    }
}