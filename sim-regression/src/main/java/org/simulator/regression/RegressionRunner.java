package org.simulator.regression;

import org.simulator.acquirer.AcquirerService;
import org.simulator.acquirer.TransactionOutcome;
import org.simulator.common.SimulatorException;
import org.simulator.common.json.Json;
import org.simulator.iso.IsoMessage;
import org.simulator.persistence.repo.RunRepository;
import org.simulator.persistence.repo.TestCaseRecord;
import org.simulator.persistence.repo.TestCaseRepository;
import org.simulator.validator.ValidationIssue;
import org.simulator.validator.ValidationProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Replays the test cases of a suite stored in H2 through the acquirer, validates each response
 * and persists results in the regression execution history.
 */
public class RegressionRunner {

    private static final Logger log = LoggerFactory.getLogger(RegressionRunner.class);

    /** Progress callback (invoked from worker threads). */
    public interface Listener {
        void onCaseFinished(CaseResult result, int done, int total);
    }

    private final AcquirerService acquirer;
    private final TestCaseRepository cases;
    private final RunRepository runs;
    private final AtomicBoolean cancelled = new AtomicBoolean();

    public RegressionRunner(AcquirerService acquirer, TestCaseRepository cases, RunRepository runs) {
        this.acquirer = acquirer;
        this.cases = cases;
        this.runs = runs;
    }

    public void cancel() {
        cancelled.set(true);
    }

    public RegressionReport run(String suiteId, String suiteName, String environment, RegressionOptions opts,
                                Listener listener) {
        cancelled.set(false);
        List<TestCaseRecord> list = cases.enabledBySuite(suiteId).stream()
                .filter(tc -> opts.getTagFilter() == null || opts.getTagFilter().isBlank()
                        || (tc.tags() != null && tc.tags().contains(opts.getTagFilter())))
                .toList();
        Instant started = Instant.now();
        long runId = runs.startRegression(suiteId, suiteName, environment);
        log.info("Regression run #{} suite '{}' with {} cases", runId, suiteName, list.size());

        List<CaseResult> results = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger done = new AtomicInteger();
        AtomicBoolean stop = new AtomicBoolean();
        ExecutorService pool = Executors.newFixedThreadPool(opts.getConcurrency(), r -> {
            Thread t = new Thread(r, "regression-" + runId);
            t.setDaemon(true);
            return t;
        });
        Semaphore permits = new Semaphore(opts.getConcurrency());
        List<Future<?>> futures = new ArrayList<>();
        Map<Long, Integer> order = new HashMap<>();
        try {
            for (int i = 0; i < list.size(); i++) {
                TestCaseRecord tc = list.get(i);
                order.put(tc.id(), i);
                if (cancelled.get() || stop.get()) {
                    break;
                }
                permits.acquireUninterruptibly();
                if (cancelled.get() || stop.get()) {
                    permits.release();
                    break;
                }
                futures.add(pool.submit(() -> {
                    try {
                        CaseResult r = execute(tc, opts);
                        results.add(r);
                        persist(runId, r);
                        if (!r.passed() && opts.isStopOnFirstFailure()) {
                            stop.set(true);
                        }
                        if (listener != null) {
                            listener.onCaseFinished(r, done.incrementAndGet(), list.size());
                        }
                    } finally {
                        permits.release();
                    }
                }));
                if (opts.getDelayBetweenMs() > 0) {
                    sleep(opts.getDelayBetweenMs());
                }
            }
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (Exception e) {
                    log.warn("Regression task failed", e);
                }
            }
        } finally {
            pool.shutdownNow();
        }
        List<CaseResult> sorted = new ArrayList<>(results);
        sorted.sort((a, b) -> Integer.compare(order.getOrDefault(a.testCaseId(), 0), order.getOrDefault(b.testCaseId(), 0)));
        long passed = sorted.stream().filter(CaseResult::passed).count();
        String status = cancelled.get() ? "CANCELLED" : stop.get() ? "STOPPED" : passed == sorted.size() ? "PASSED" : "FAILED";
        runs.finishRegression(runId, sorted.size(), (int) passed, (int) (sorted.size() - passed), status);
        log.info("Regression run #{} finished: {} ({}/{} passed)", runId, status, passed, sorted.size());
        return new RegressionReport(runId, suiteName, environment, started, Instant.now(), status, sorted);
    }

    CaseResult execute(TestCaseRecord tc, RegressionOptions opts) {
        IsoMessage req;
        ValidationProfile profile;
        try {
            String json = acquirer.templateEngine().resolve(tc.requestJson(), Map.of());
            req = acquirer.messages().fromJson(json);
            if (req.getDialect() == null) {
                req.setDialect(tc.dialect());
            }
            if (opts.isRefreshDynamicFields()) {
                refresh(req);
            }
            profile = profile(tc, opts);
        } catch (RuntimeException e) {
            return new CaseResult(tc.id(), tc.name(), false, tc.expectedRc(), null, null, 0, List.of(), tc.requestJson(),
                    null, "Invalid test case: " + e.getMessage());
        }
        try {
            TransactionOutcome o = acquirer.send(req, profile, "REGRESSION").get(
                    Math.max(1, acquirer.channel().defaultTimeoutMs()) + 5_000L, TimeUnit.MILLISECONDS);
            List<ValidationIssue> issues = o.validation() != null ? o.validation().issues() : List.of();
            boolean passed = o.response() != null && (o.validation() == null || o.validation().passed());
            return new CaseResult(tc.id(), tc.name(), passed, profile.getExpectedResponseCode(), o.responseCode(),
                    o.status(), o.latencyMicros(), issues, acquirer.maskedJson(o.request()),
                    o.response() != null ? acquirer.maskedJson(o.response()) : null,
                    o.response() == null ? o.status() + (o.error() != null ? ": " + o.error() : "") : null);
        } catch (Exception e) {
            return new CaseResult(tc.id(), tc.name(), false, tc.expectedRc(), null, null, 0, List.of(),
                    acquirer.maskedJson(req), null, e.getMessage());
        }
    }

    private ValidationProfile profile(TestCaseRecord tc, RegressionOptions opts) {
        ValidationProfile p = ValidationProfile.expecting(tc.expectedRc() != null ? tc.expectedRc() : "00");
        Set<Integer> ignore = new java.util.HashSet<>(opts.getIgnoreFields());
        if (tc.ignoreFields() != null && !tc.ignoreFields().isBlank()) {
            Arrays.stream(tc.ignoreFields().split("[,; ]+")).filter(s -> !s.isBlank())
                    .map(s -> Integer.parseInt(s.replaceAll("\\D", ""))).forEach(ignore::add);
        }
        if (!ignore.isEmpty() && p.getEchoFields() != null) {
            p.setEchoFields(p.getEchoFields().stream().filter(f -> !ignore.contains(f)).collect(Collectors.toList()));
        }
        return p;
    }

    private void refresh(IsoMessage req) {
        String stan = acquirer.sequences().nextStan();
        if (req.has(11)) {
            req.set(11, stan);
        }
        if (req.has(37)) {
            req.set(37, acquirer.sequences().rrn(stan));
        }
        Map<String, String> none = Map.of();
        if (req.has(7)) {
            req.set(7, acquirer.transmissionDateTime());
        }
        if (req.has(12)) {
            req.set(12, acquirer.templateEngine().resolve("${LOCAL_TIME}", none));
        }
        if (req.has(13)) {
            req.set(13, acquirer.templateEngine().resolve("${LOCAL_DATE}", none));
        }
    }

    private void persist(long runId, CaseResult r) {
        try {
            runs.addRegressionResult(runId, r.testCaseId(), r.name(), r.passed(), r.expectedRc(), r.actualRc(),
                    r.latencyMicros(), Json.write(r.issues()), r.responseJson());
        } catch (RuntimeException e) {
            log.warn("Cannot persist regression result for {}: {}", r.name(), e.getMessage());
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SimulatorException("Interrupted", e);
        }
    }
}
