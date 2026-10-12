package org.simulator.stats;

import org.junit.jupiter.api.Test;
import org.simulator.common.event.EventBus;
import org.simulator.common.event.TransactionEvent;
import org.simulator.common.event.TransactionEvent.Outcome;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class PrometheusExporterTest {

    @Test
    void exposesCountersHistogramAndGauges() throws Exception {
        EventBus bus = new EventBus();
        StatisticsCollector stats = new StatisticsCollector().attach(bus);
        try (PrometheusExporter exp = new PrometheusExporter(stats::snapshot).attach(bus).start(0)) {
            exp.registerGauge("simulator_issuer_connections", "Issuer connections", () -> 3);
            bus.publish(new TransactionEvent("ACQUIRER", "0200", "000000", "476173", "00", Outcome.APPROVED, 20_000, Instant.now()));
            bus.publish(new TransactionEvent("ACQUIRER", "0200", "000000", "476173", "51", Outcome.DECLINED, 300_000, Instant.now()));
            bus.publish(new TransactionEvent("ISSUER", "0100", "000000", "476173", null, Outcome.TIMEOUT, 0, Instant.now()));
            Thread.sleep(200);

            HttpResponse<String> r = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + exp.port() + "/metrics")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(r.statusCode()).isEqualTo(200);
            assertThat(r.headers().firstValue("Content-Type")).hasValueSatisfying(v -> assertThat(v).contains("0.0.4"));
            String m = r.body();
            assertThat(m).contains("simulator_transactions_total{source=\"ACQUIRER\",outcome=\"APPROVED\"} 1")
                    .contains("simulator_transactions_total{source=\"ISSUER\",outcome=\"TIMEOUT\"} 1")
                    .contains("simulator_response_codes_total{source=\"ACQUIRER\",rc=\"51\"} 1")
                    .contains("simulator_latency_seconds_bucket{source=\"ACQUIRER\",le=\"0.025\"} 1")
                    .contains("simulator_latency_seconds_bucket{source=\"ACQUIRER\",le=\"0.5\"} 2")
                    .contains("simulator_latency_seconds_count{source=\"ACQUIRER\"} 2")
                    .contains("simulator_issuer_connections 3")
                    .contains("# TYPE simulator_tps_current gauge")
                    .doesNotContain("476173");
        }
        stats.close();
    }
}