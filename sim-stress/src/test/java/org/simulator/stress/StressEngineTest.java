package org.simulator.stress;

import org.junit.jupiter.api.Test;
import org.simulator.common.event.TransactionEvent.Outcome;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class StressEngineTest {

    private final ScheduledExecutorService ses = Executors.newScheduledThreadPool(4);

    private StressTarget delayed(long delayMs, Outcome outcome) {
        return seq -> {
            CompletableFuture<Outcome> f = new CompletableFuture<>();
            ses.schedule(() -> f.complete(outcome), delayMs, TimeUnit.MILLISECONDS);
            return f;
        };
    }

    @Test
    void profiles() {
        assertThat(LoadProfile.constant(50, 10).tpsAt(3)).isEqualTo(50);
        LoadProfile ramp = LoadProfile.ramp(10, 110, 10, 20);
        assertThat(ramp.tpsAt(0)).isEqualTo(10);
        assertThat(ramp.tpsAt(5)).isEqualTo(60);
        assertThat(ramp.tpsAt(15)).isEqualTo(110);
        LoadProfile step = new LoadProfile().setType(LoadProfile.Type.STEP).setStartTps(10).setStepTps(10)
                .setStepEverySec(5).setTargetTps(35);
        assertThat(step.tpsAt(4)).isEqualTo(10);
        assertThat(step.tpsAt(12)).isEqualTo(30);
        assertThat(step.tpsAt(100)).isEqualTo(35);
        LoadProfile spike = new LoadProfile().setType(LoadProfile.Type.SPIKE).setTargetTps(10).setSpikeTps(100)
                .setSpikeAtSec(5).setSpikeDurationSec(2);
        assertThat(spike.tpsAt(6)).isEqualTo(100);
        assertThat(spike.tpsAt(7)).isEqualTo(10);
    }

    @Test
    void constantRateIsHonoured() {
        List<StressSample> samples = new CopyOnWriteArrayList<>();
        StressResult r = new StressEngine().run(LoadProfile.constant(200, 3), SlaPolicy.none(),
                delayed(5, Outcome.APPROVED), new StressEngine.Listener() {
                    @Override
                    public void onSample(StressSample s) {
                        samples.add(s);
                    }
                });
        assertThat(r.status()).isEqualTo("COMPLETED");
        assertThat(r.sent()).isBetween(540L, 620L);
        assertThat(r.approved()).isEqualTo(r.sent());
        assertThat(r.p50Ms()).isBetween(4.0, 50.0);
        assertThat(samples).hasSizeBetween(2, 3);
        assertThat(r.toCsv()).startsWith("second,");
    }

    @Test
    void backPressureAndSlaAbort() {
        StressResult r = new StressEngine().run(
                LoadProfile.constant(500, 10).setMaxInFlight(10),
                new SlaPolicy().setMaxP99Ms(50).setGraceSec(0).setConsecutiveSeconds(2),
                delayed(200, Outcome.APPROVED), null);
        assertThat(r.status()).isEqualTo("ABORTED_SLA");
        assertThat(r.throttled()).isGreaterThan(0);
        assertThat(r.durationSec()).isLessThan(6);
        assertThat(r.abortReason()).contains("SLA");
    }

    @Test
    void errorsAndStop() throws Exception {
        StressEngine engine = new StressEngine();
        CompletableFuture<StressResult> f = CompletableFuture.supplyAsync(() -> engine.run(
                LoadProfile.constant(100, 30), SlaPolicy.none(),
                seq -> seq % 2 == 0 ? CompletableFuture.completedFuture(Outcome.TIMEOUT)
                        : CompletableFuture.failedFuture(new RuntimeException("boom")), null));
        Thread.sleep(1200);
        engine.stop();
        StressResult r = f.get(10, TimeUnit.SECONDS);
        assertThat(r.status()).isEqualTo("CANCELLED");
        assertThat(r.timeouts()).isGreaterThan(0);
        assertThat(r.errors()).isGreaterThan(0);
        assertThat(r.approved()).isZero();
    }
}
