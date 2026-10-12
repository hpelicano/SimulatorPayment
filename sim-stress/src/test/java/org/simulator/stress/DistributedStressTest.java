package org.simulator.stress;

import org.junit.jupiter.api.Test;
import org.simulator.common.event.TransactionEvent.Outcome;
import org.simulator.stress.distributed.AgentRequest;
import org.simulator.stress.distributed.StressAgentServer;
import org.simulator.stress.distributed.StressCoordinator;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class DistributedStressTest {

    @Test
    void coordinatorSplitsLoadAcrossAgents() throws Exception {
        AtomicLong fired = new AtomicLong();
        List<String> templates = new CopyOnWriteArrayList<>();
        java.util.function.Function<AgentRequest, StressTarget> factory = req -> {
            templates.add(req.getTemplateId());
            return seq -> {
                fired.incrementAndGet();
                return CompletableFuture.completedFuture(Outcome.APPROVED);
            };
        };
        try (StressAgentServer a1 = new StressAgentServer("a1", "secret-token", factory).start("127.0.0.1", 0);
             StressAgentServer a2 = new StressAgentServer("a2", "secret-token", factory).start("127.0.0.1", 0)) {

            HttpResponse<String> unauth = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + a1.port() + "/status")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(unauth.statusCode()).isEqualTo(401);

            StressCoordinator coord = new StressCoordinator(StressCoordinator.parse(
                    "http://127.0.0.1:" + a1.port() + "@secret-token\nhttp://127.0.0.1:" + a2.port() + "@secret-token"));
            assertThat(coord.health()).containsValues(true, true);

            List<StressSample> samples = new CopyOnWriteArrayList<>();
            StressResult r = coord.run(new AgentRequest().setTemplateId("purchase")
                    .setProfile(LoadProfile.constant(40, 3).setName("dist")), samples::add);

            assertThat(templates).containsExactly("purchase", "purchase");
            assertThat(r.name()).contains("x2");
            assertThat(r.approved()).isBetween(90L, 130L);
            assertThat(r.sent()).isEqualTo(fired.get());
            assertThat(samples).isNotEmpty();
            assertThat(samples.get(0).targetTps()).isEqualTo(40.0);
        }
    }

    @Test
    void scaledProfile() {
        LoadProfile p = LoadProfile.ramp(10, 100, 5, 30).setMaxInFlight(100).scaled(0.25);
        assertThat(p.getTargetTps()).isEqualTo(25);
        assertThat(p.getStartTps()).isEqualTo(2.5);
        assertThat(p.getMaxInFlight()).isEqualTo(25);
        assertThat(p.getType()).isEqualTo(LoadProfile.Type.RAMP);
    }
}
