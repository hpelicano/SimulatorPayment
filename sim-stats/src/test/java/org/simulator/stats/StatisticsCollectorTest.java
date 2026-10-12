package org.simulator.stats;

import org.junit.jupiter.api.Test;
import org.simulator.common.event.EventBus;
import org.simulator.common.event.TransactionEvent;
import org.simulator.common.event.TransactionEvent.Outcome;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class StatisticsCollectorTest {

    private static TransactionEvent ev(String source, String rc, Outcome o, long micros) {
        return new TransactionEvent(source, "0200", "000000", "476173", rc, o, micros, Instant.now());
    }

    @Test
    void aggregatesEvents() {
        EventBus bus = new EventBus();
        try (StatisticsCollector stats = new StatisticsCollector().attach(bus)) {
            for (int i = 1; i <= 100; i++) {
                bus.publish(ev("ACQUIRER", "00", Outcome.APPROVED, i * 1000L));
            }
            bus.publish(ev("ACQUIRER", "51", Outcome.DECLINED, 5000));
            bus.publish(ev("STRESS", null, Outcome.TIMEOUT, 30_000_000));
            bus.publish(ev("STRESS", null, Outcome.ERROR, 100));

            StatsSnapshot s = stats.snapshot();
            assertThat(s.total()).isEqualTo(103);
            assertThat(s.approved()).isEqualTo(100);
            assertThat(s.declined()).isEqualTo(1);
            assertThat(s.timeouts()).isEqualTo(1);
            assertThat(s.errors()).isEqualTo(1);
            assertThat(s.byResponseCode()).containsEntry("00", 100L).containsEntry("51", 1L).containsEntry("TIMEOUT", 1L);
            assertThat(s.bySource()).containsEntry("STRESS", 2L);
            assertThat(s.p50Ms()).isBetween(45.0, 55.0);
            assertThat(s.p99Ms()).isBetween(95.0, 101.0);
            assertThat(s.maxMs()).isLessThan(200.0);
            assertThat(s.tpsHistory()).hasSize(300);
            assertThat(s.tpsHistory().stream().mapToLong(Long::longValue).sum()).isEqualTo(103L);
            assertThat(s.errorRate()).isBetween(1.9, 2.0);
            assertThat(stats.toCsv()).contains("rc_51,1");

            stats.reset();
            assertThat(stats.snapshot().total()).isZero();
            assertThat(stats.snapshot().p99Ms()).isZero();
        }
    }

    @Test
    void filterBySource() {
        StatisticsCollector stats = new StatisticsCollector(60, e -> "ISSUER".equals(e.source()));
        stats.record(ev("ACQUIRER", "00", Outcome.APPROVED, 1000));
        stats.record(ev("ISSUER", "00", Outcome.APPROVED, 1000));
        assertThat(stats.snapshot().total()).isEqualTo(1);
    }
}
