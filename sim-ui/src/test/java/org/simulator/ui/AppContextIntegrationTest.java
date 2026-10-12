package org.simulator.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.simulator.acquirer.TransactionOutcome;
import org.simulator.acquirer.TransactionTemplate;
import org.simulator.common.event.TransactionEvent.Outcome;
import org.simulator.config.ConfigService;
import org.simulator.persistence.Database;
import org.simulator.persistence.repo.TestCaseRepository.SuiteRecord;
import org.simulator.regression.RegressionOptions;
import org.simulator.regression.RegressionReport;
import org.simulator.regression.RegressionRunner;
import org.simulator.regression.TestCaseCapture;
import org.simulator.stress.LoadProfile;
import org.simulator.stress.SlaPolicy;
import org.simulator.stress.StressResult;

import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** End-to-end wiring: issuer simulator ⇄ acquirer over TCP, stats, regression, stress and H2 logging. */
class AppContextIntegrationTest {

    @TempDir
    Path dir;

    @Test
    void pathwaySshUsesSelectedServerAndIndependentCredentials() {
        ConfigService config = new ConfigService(dir.resolve("pathway-ssh")).load();
        config.activeEnvironment().getGuardian().setHost("guardian.example");
        var endpoint = config.activeEnvironment().getPathway();
        endpoint.setMode("SSH");
        endpoint.setHost("pathway.example");
        endpoint.setUsername("OPER.PATH");
        endpoint.setServers(java.util.List.of("$POS", "$ATM"));
        try (AppContext ctx = new AppContext(config, Database.inMemory("pathway-ssh-" + System.nanoTime()))) {
            assertThat(ctx.pathway("$ATM").port().describe()).contains("pathway.example", "PATHCOM $ATM")
                    .doesNotContain("guardian.example", "PATHCOM $POS");
            assertThat(ctx.pathway("$POS").port()).isNotSameAs(ctx.pathway("$ATM").port());
            assertThat(ctx.pathwayHistory("$ATM")).isEmpty();
        }
    }

    @Test
    void pathwayServersHaveIndependentHistoryAndAudit() throws Exception {
        ConfigService config = new ConfigService(dir.resolve("pathway-cfg")).load();
        try (var gateway = new ServerSocket(0);
             AppContext ctx = new AppContext(config, Database.inMemory("pathway-" + System.nanoTime()))) {
            var endpoint = config.activeEnvironment().getPathway();
            endpoint.setMode("SOCKET");
            endpoint.setHost("127.0.0.1");
            endpoint.setPort(gateway.getLocalPort());
            endpoint.setServers(java.util.List.of("$POS", "$ATM"));
            var peer = java.util.concurrent.CompletableFuture.runAsync(() -> {
                try (var socket = gateway.accept()) {
                    socket.setSoTimeout(5000);
                    var out = socket.getOutputStream();
                    out.write("PATHWAY\n=\n".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
                    out.flush();
                    var in = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(),
                            java.nio.charset.StandardCharsets.ISO_8859_1));
                    assertThat(in.readLine()).isEqualTo("STATUS");
                    out.write("STATUS\nPATHWAY RUNNING\n=\n".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
                    out.flush();
                } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
            var xp = ctx.pathway("$POS");
            var path = ctx.pathway("$ATM");
            assertThat(xp.port()).isNotSameAs(path.port());
            assertThat(xp.execute("STATUS", false, "tester").output()).isEqualTo("PATHWAY RUNNING");
            assertThat(xp.history()).hasSize(1);
            assertThat(path.history()).isEmpty();
            assertThat(xp.check("STOP NODE").verdict())
                    .isEqualTo(org.simulator.base24.pathway.CommandPolicy.Verdict.NEEDS_CONFIRMATION);
            assertThat(ctx.runs().auditTrail(10)).anySatisfy(row -> assertThat(row).contains("PATHWAY"));
            peer.get(5, java.util.concurrent.TimeUnit.SECONDS);
            ctx.environmentChanged();
            assertThat(ctx.pathwayHistory("$POS")).isEmpty();
            assertThat(ctx.pathway("$POS")).isNotSameAs(xp);
        }
    }

    @Test
    void loopbackAcquirerIssuerRegressionAndStress() throws Exception {
        ConfigService config = new ConfigService(dir.resolve("cfg")).load();
        assertThat(config.unlock("master-password-123".toCharArray())).isTrue();
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        config.activeEnvironment().getIssuer().setPort(port);
        config.activeEnvironment().getAcquirer().setPort(port);

        try (AppContext ctx = new AppContext(config, Database.inMemory("itest"))) {
            ctx.startIssuer(-1);
            ctx.connectAcquirerTcp();
            assertThat(ctx.acquirer().isConnected()).isTrue();

            TransactionTemplate purchase = ctx.templates().get("purchase");
            assertThat(purchase).isNotNull();
            TransactionOutcome ok = ctx.acquirer().send(purchase, purchase.getVariables(), "ACQUIRER").join();
            assertThat(ok.status()).isEqualTo(Outcome.APPROVED);
            assertThat(ok.responseCode()).isEqualTo("00");
            assertThat(ok.response().get(38)).hasSize(6);

            Map<String, String> vars = new HashMap<>(purchase.getVariables());
            vars.put("AMOUNT", "000099999999");
            TransactionOutcome nsf = ctx.acquirer().send(purchase, vars, "ACQUIRER").join();
            assertThat(nsf.responseCode()).isEqualTo("51");
            assertThat(nsf.status()).isEqualTo(Outcome.DECLINED);

            assertThat(ctx.acquirer().echoTest().join().status()).isEqualTo(Outcome.APPROVED);

            String suiteId = ctx.testCases().suiteIdOrCreate("ITEST");
            TestCaseCapture.capture(ctx.testCases(), ctx.messages(), suiteId, "compra ok", ok);
            TestCaseCapture.capture(ctx.testCases(), ctx.messages(), suiteId, "fondos insuficientes", nsf);
            SuiteRecord suite = ctx.testCases().suites().stream().filter(s -> s.id().equals(suiteId)).findFirst().orElseThrow();
            RegressionReport rep = new RegressionRunner(ctx.acquirer(), ctx.testCases(), ctx.runs())
                    .run(suite.id(), suite.name(), ctx.env().getName(), new RegressionOptions(), (r, d, t) -> { });
            assertThat(rep.failed()).as(rep.results().toString()).isZero();
            assertThat(rep.passed()).isEqualTo(2);

            StressResult sr = ctx.stressEngine().run(LoadProfile.constant(50, 2), SlaPolicy.none(),
                    seq -> ctx.acquirer().send(purchase, purchase.getVariables(), "STRESS").thenApply(TransactionOutcome::status),
                    null);
            assertThat(sr.status()).isEqualTo("COMPLETED");
            assertThat(sr.approved()).isGreaterThan(50);

            assertThat(ctx.stats().snapshot().total()).isGreaterThanOrEqualTo(sr.sent() + 3);
            ctx.txLog().flush();
            assertThat(ctx.txLog().recent(10)).allSatisfy(r -> {
                assertThat(r.requestHex()).isNull();
                if (r.requestJson() != null && r.panMasked() != null) {
                    assertThat(r.requestJson()).contains(r.panMasked());
                }
            });
            assertThat(ctx.dbAdmin().tables()).isNotEmpty();
            assertThat(ctx.dbAdmin().tables()).noneMatch(t -> t.equalsIgnoreCase("APP_USER") || t.equalsIgnoreCase("APPROVAL_REQUEST"));
        }
    }

    @Test
    void masterPasswordIsRequestedLazilyOnlyWhenASecretIsNeeded() throws Exception {
        ConfigService setup = new ConfigService(dir.resolve("cfg2")).load();
        assertThat(setup.unlock("master-password-123".toCharArray())).isTrue();
        String enc = setup.encryptSecret("s3cret");
        setup.save();

        ConfigService config = new ConfigService(dir.resolve("cfg2")).load();
        try (AppContext ctx = new AppContext(config, Database.inMemory("unlock"))) {
            java.util.concurrent.atomic.AtomicInteger prompts = new java.util.concurrent.atomic.AtomicInteger();
            ctx.setUnlockPrompt(() -> {
                prompts.incrementAndGet();
                return false;
            });
            assertThat(ctx.secret("plain")).isEqualTo("plain");
            assertThat(prompts).hasValue(0);

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> ctx.secret(enc))
                    .hasMessageContaining("locked");
            assertThat(prompts).hasValue(1);
            // declined: implicit requests are not re-prompted immediately, explicit actions are
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> ctx.secret(enc));
            assertThat(prompts).hasValue(1);
            assertThat(ctx.ensureUnlocked()).isFalse();
            assertThat(prompts).hasValue(2);

            ctx.setUnlockPrompt(() -> {
                prompts.incrementAndGet();
                return config.unlock("master-password-123".toCharArray());
            });
            assertThat(ctx.ensureUnlocked()).isTrue();
            assertThat(ctx.secret(enc)).isEqualTo("s3cret");
            assertThat(prompts).hasValue(3);
        }
    }
}
