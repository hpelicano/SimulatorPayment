package org.simulator.regression;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simulator.acquirer.AcquirerService;
import org.simulator.acquirer.IsoClientChannel;
import org.simulator.acquirer.TemplateRepository;
import org.simulator.acquirer.TransactionOutcome;
import org.simulator.common.event.EventBus;
import org.simulator.iso.IsoMessage;
import org.simulator.iso.MessageService;
import org.simulator.iso.dialect.DialectRegistry;
import org.simulator.persistence.Database;
import org.simulator.persistence.Jdbc;
import org.simulator.persistence.repo.RunRepository;
import org.simulator.persistence.repo.TestCaseRecord;
import org.simulator.persistence.repo.TestCaseRepository;
import org.simulator.transport.Framing;
import org.simulator.transport.IsoServer;
import org.simulator.transport.TransportOptions;
import org.simulator.validator.MessageValidator;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class RegressionRunnerTest {

    private final MessageService messages = new MessageService(DialectRegistry.builtIn());
    private Database db;
    private IsoServer server;
    private AcquirerService acquirer;
    private TestCaseRepository cases;
    private RunRepository runs;
    private final List<String> seenStans = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        db = Database.inMemory("reg" + System.nanoTime());
        Jdbc jdbc = new Jdbc(db);
        cases = new TestCaseRepository(jdbc);
        runs = new RunRepository(jdbc);
        TransportOptions opts = TransportOptions.of("127.0.0.1", 0, Framing.LEN2_BINARY, "base24-pos");
        server = new IsoServer(opts, messages, (req, raw) -> {
            seenStans.add(req.get(11));
            IsoMessage resp = req.copy().setMti(IsoMessage.responseMti(req.getMti()));
            resp.setHeader(messages.responseHeader(req.getDialect(), req.getHeader()));
            resp.set(39, req.get(4) != null && req.get(4).endsWith("51") ? "51" : "00");
            return IsoServer.Reply.of(resp);
        }).start();
        acquirer = new AcquirerService(messages, new MessageValidator(), new EventBus(), null);
        acquirer.useChannel(new IsoClientChannel(opts.withPort(server.port()).withTimeout(1000), messages).connect());
    }

    @AfterEach
    void tearDown() {
        acquirer.close();
        server.close();
        db.close();
    }

    @Test
    void runsSuiteAndProducesReports() throws Exception {
        String suite = cases.suiteIdOrCreate("smoke");
        TransactionOutcome captured = acquirer.send(TemplateRepository.builtIn().get("purchase"), null, "ACQUIRER")
                .get(5, TimeUnit.SECONDS);
        TestCaseCapture.capture(cases, messages, suite, "compra capturada", captured);
        String json = "{\"dialect\":\"base24-pos\",\"mti\":\"0200\",\"fields\":{\"2\":\"4761739001010010\","
                + "\"3\":\"000000\",\"4\":\"000000000051\",\"7\":\"${TRANSMISSION_DATETIME}\",\"11\":\"000001\","
                + "\"37\":\"000000000001\",\"41\":\"TERM0001\",\"49\":\"032\"}}";
        cases.save(TestCaseRecord.of(suite, "fondos insuficientes esperado 51", "base24-pos", json, "51"));
        cases.save(TestCaseRecord.of(suite, "espera 00 pero recibe 51", "base24-pos", json, "00"));
        cases.save(TestCaseRecord.of(suite, "json invalido", "base24-pos", "{not json", "00"));

        List<CaseResult> progress = new CopyOnWriteArrayList<>();
        RegressionRunner runner = new RegressionRunner(acquirer, cases, runs);
        RegressionReport report = runner.run(suite, "smoke", "DEV",
                new RegressionOptions().setIgnoreFields(Set.of(37)), (r, done, total) -> progress.add(r));

        assertThat(report.results()).hasSize(4);
        assertThat(progress).hasSize(4);
        assertThat(report.results()).extracting(CaseResult::passed).containsExactly(true, true, false, false);
        assertThat(report.results().get(2).summary()).contains("39");
        assertThat(report.results().get(3).error()).startsWith("Invalid test case");
        assertThat(report.status()).isEqualTo("FAILED");
        assertThat(seenStans).doesNotHaveDuplicates();
        assertThat(report.results().get(0).requestJson()).contains("4761739001010010");

        assertThat(report.toJUnitXml()).contains("tests=\"4\"").contains("failures=\"2\"");
        assertThat(report.toHtml()).contains("fondos insuficientes").doesNotContain("4761739001010010");
        assertThat(runs.regressionRuns(10)).hasSize(1).first()
                .satisfies(r -> {
                    assertThat(r.passed()).isEqualTo(2);
                    assertThat(r.failed()).isEqualTo(2);
                });
    }

    @Test
    void concurrentAndStopOnFailure() {
        String suite = cases.suiteIdOrCreate("par");
        String json = "{\"dialect\":\"base24-pos\",\"mti\":\"0200\",\"fields\":{\"3\":\"000000\",\"4\":\"%s\","
                + "\"11\":\"000001\",\"41\":\"TERM0001\"}}";
        for (int i = 0; i < 20; i++) {
            cases.save(TestCaseRecord.of(suite, "tc" + i, "base24-pos", String.format(json, "000000000100"), "00"));
        }
        RegressionReport par = new RegressionRunner(acquirer, cases, runs)
                .run(suite, "par", "DEV", new RegressionOptions().setConcurrency(4), null);
        assertThat(par.passed()).isEqualTo(20);
        assertThat(par.results()).extracting(CaseResult::name).startsWith("tc0", "tc1", "tc2");

        String suite2 = cases.suiteIdOrCreate("stop");
        cases.save(TestCaseRecord.of(suite2, "fail", "base24-pos", String.format(json, "000000000051"), "00"));
        cases.save(TestCaseRecord.of(suite2, "never", "base24-pos", String.format(json, "000000000100"), "00"));
        RegressionReport stop = new RegressionRunner(acquirer, cases, runs)
                .run(suite2, "stop", "DEV", new RegressionOptions().setStopOnFirstFailure(true), null);
        assertThat(stop.status()).isEqualTo("STOPPED");
        assertThat(stop.results()).hasSize(1);
        Map<String, String> unused = Map.of();
        assertThat(unused).isEmpty();
    }
}
