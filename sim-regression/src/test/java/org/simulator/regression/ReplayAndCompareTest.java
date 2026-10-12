package org.simulator.regression;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simulator.acquirer.AcquirerService;
import org.simulator.acquirer.IsoClientChannel;
import org.simulator.acquirer.TemplateRepository;
import org.simulator.common.event.EventBus;
import org.simulator.common.json.Json;
import org.simulator.iso.IsoMessage;
import org.simulator.iso.MessageService;
import org.simulator.iso.dialect.DialectRegistry;
import org.simulator.persistence.Database;
import org.simulator.persistence.Jdbc;
import org.simulator.persistence.repo.RunRepository;
import org.simulator.persistence.repo.TestCaseRecord;
import org.simulator.persistence.repo.TestCaseRepository;
import org.simulator.regression.compare.ComparisonReport;
import org.simulator.regression.compare.RegressionComparator;
import org.simulator.regression.replay.Recording;
import org.simulator.regression.replay.RecordedTransaction;
import org.simulator.regression.replay.RecordingStore;
import org.simulator.regression.replay.ReplayEngine;
import org.simulator.regression.replay.ReplayReport;
import org.simulator.regression.replay.TrafficRecorder;
import org.simulator.transport.Framing;
import org.simulator.transport.IsoServer;
import org.simulator.transport.TransportOptions;
import org.simulator.validator.MessageValidator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReplayAndCompareTest {

    private final MessageService messages = new MessageService(DialectRegistry.builtIn());
    private Database db;
    private IsoServer server;
    private AcquirerService acquirer;
    private volatile String forcedRc;
    private final List<String> stans = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        db = Database.inMemory("rpl" + System.nanoTime());
        TransportOptions opts = TransportOptions.of("127.0.0.1", 0, Framing.LEN2_BINARY, "base24-pos");
        server = new IsoServer(opts, messages, (req, raw) -> {
            stans.add(req.get(11));
            IsoMessage resp = req.copy().setMti(IsoMessage.responseMti(req.getMti()));
            resp.setHeader(messages.responseHeader(req.getDialect(), req.getHeader()));
            resp.set(39, forcedRc != null ? forcedRc : req.get(4) != null && req.get(4).endsWith("51") ? "51" : "00");
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
    void recordSaveEncryptedAndReplay() throws Exception {
        TrafficRecorder recorder = new TrafficRecorder(messages);
        acquirer.addOutcomeListener(recorder::onOutcome);
        recorder.start("captura", "DEV");
        var purchase = TemplateRepository.builtIn().get("purchase");
        acquirer.send(purchase, Map.of(), "ACQUIRER").get(5, TimeUnit.SECONDS);
        Thread.sleep(120);
        acquirer.send(purchase, Map.of("AMOUNT", "000000000051"), "ACQUIRER").get(5, TimeUnit.SECONDS);
        Recording rec = recorder.stop();
        assertThat(rec.getItems()).hasSize(2);
        assertThat(rec.getItems().get(1).offsetMs()).isGreaterThanOrEqualTo(100);

        Files.createDirectories(Path.of("target"));
        Path file = Files.createTempFile(Path.of("target"), "rec", ".json");
        Recording loaded;
        try {
            RecordingStore.save(file, rec, s -> "ENC(" + Base64.getEncoder().encodeToString(
                    s.getBytes(java.nio.charset.StandardCharsets.UTF_8)) + ")");
            assertThat(Files.readString(file)).doesNotContain("4761739001010010").contains("ENC(");
            assertThatThrownBy(() -> RecordingStore.load(file, null)).hasMessageContaining("cifrada");
            loaded = RecordingStore.load(file, s -> new String(
                    Base64.getDecoder().decode(s.substring(4, s.length() - 1)), java.nio.charset.StandardCharsets.UTF_8));
            assertThat(loaded.getItems()).allSatisfy(item -> assertThat(item.requestJson()).contains("4761739001010010"));
        } finally {
            Files.deleteIfExists(file);
        }

        long t0 = System.nanoTime();
        ReplayReport r = new ReplayEngine(acquirer).replay(loaded, 1.0, true, null, null);
        assertThat((System.nanoTime() - t0) / 1_000_000).isGreaterThanOrEqualTo(100);
        assertThat(r.items()).hasSize(2);
        assertThat(r.matches()).isEqualTo(2);
        assertThat(stans).doesNotHaveDuplicates();

        forcedRc = "05";
        ReplayReport changed = new ReplayEngine(acquirer).replay(loaded, 0, true, null, null);
        assertThat(changed.mismatches()).isEqualTo(2);
        assertThat(changed.toHtml()).contains("class='ko'");
    }

    @Test
    void recordingPreservesPanAndReplayTracksWithoutPin() throws Exception {
        IsoMessage request = new IsoMessage("base24-pos", "0200")
                .set(2, "4761739001010010").set(35, "4761739001010010D2512101")
                .set(55, "5A084761739001010010570A4761739001010010D251"
                        + "9F6B0A4761739001010010D251"
                        + "5613" + org.simulator.common.util.Hex.encode(
                                "%B4761739001010010^".getBytes(java.nio.charset.StandardCharsets.US_ASCII)))
                .set(52, "ABCDEF0123456789").set(53, "2600000000000000");
        TrafficRecorder recorder = new TrafficRecorder(messages);
        recorder.start("plain-pan", "DEV");
        recorder.record("ACQUIRER", request, "00", "APPROVED", 100);
        Recording recording = recorder.stop();
        IsoMessage recorded = messages.fromJson(recording.getItems().get(0).requestJson());
        assertThat(recorded.get(2)).isEqualTo(request.get(2));
        assertThat(recorded.get(35)).isEqualTo(request.get(35));
        assertThat(recorded.get(55)).isEqualTo(request.get(55));
        assertThat(recorded.has(52)).isFalse();
        assertThat(recorded.has(53)).isFalse();
        assertThat(request.has(52)).isTrue();

        // Also sanitize older or externally constructed recordings when saving them.
        recording.getItems().add(new RecordedTransaction(1, "ACQUIRER", "0200", messages.toJson(request),
                "00", "APPROVED", 100));
        Files.createDirectories(Path.of("target"));
        Path file = Files.createTempFile(Path.of("target"), "plain-pan", ".json");
        try {
            RecordingStore.save(file, recording, null);
            assertThat(Files.readString(file)).contains("4761739001010010")
                    .doesNotContain("ABCDEF0123456789", "2600000000000000");
            assertThat(RecordingStore.load(file, null).getItems()).allSatisfy(item -> {
                IsoMessage saved = messages.fromJson(item.requestJson());
                assertThat(saved.get(35)).isEqualTo(request.get(35));
                assertThat(saved.get(55)).isEqualTo(request.get(55));
                assertThat(saved.has(52)).isFalse();
                assertThat(saved.has(53)).isFalse();
            });
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void historicalEncryptedRecordingsStillLoad() throws Exception {
        Recording recording = new Recording("legacy", "DEV");
        String body = Json.write(recording);
        String encrypted = "ENC(" + Base64.getEncoder().encodeToString(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)) + ")";
        Files.createDirectories(Path.of("target"));
        Path file = Files.createTempFile(Path.of("target"), "legacy-rec", ".json");
        try {
            Files.writeString(file, Json.write(Map.of("encrypted", true, "data", encrypted)));
            assertThatThrownBy(() -> RecordingStore.load(file, null)).hasMessageContaining("cifrada");
            Recording loaded = RecordingStore.load(file, s -> new String(
                    Base64.getDecoder().decode(s.substring(4, s.length() - 1)), java.nio.charset.StandardCharsets.UTF_8));
            assertThat(loaded.getName()).isEqualTo("legacy");
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void compareRuns() {
        Jdbc jdbc = new Jdbc(db);
        TestCaseRepository cases = new TestCaseRepository(jdbc);
        RunRepository runs = new RunRepository(jdbc);
        String suite = cases.suiteIdOrCreate("cmp");
        String json = "{\"dialect\":\"base24-pos\",\"mti\":\"0200\",\"fields\":{\"3\":\"000000\",\"4\":\"%s\","
                + "\"11\":\"000001\",\"41\":\"TERM0001\"}}";
        cases.save(TestCaseRecord.of(suite, "ok", "base24-pos", String.format(json, "000000000100"), "00"));
        cases.save(TestCaseRecord.of(suite, "declined", "base24-pos", String.format(json, "000000000051"), "51"));

        RegressionReport first = new RegressionRunner(acquirer, cases, runs).run(suite, "cmp", "QA", new RegressionOptions(), null);
        forcedRc = "05";
        RegressionReport second = new RegressionRunner(acquirer, cases, runs).run(suite, "cmp", "UAT", new RegressionOptions(), null);

        ComparisonReport cmp = new RegressionComparator(runs).compare(first.runId(), second.runId());
        assertThat(cmp.entries()).hasSize(2);
        assertThat(cmp.count(ComparisonReport.Change.REGRESSION)).isEqualTo(2);
        assertThat(cmp.hasRegressions()).isTrue();
        assertThat(cmp.entries().get(0).fieldDiffs()).anyMatch(s -> s.startsWith("DE39"));
        assertThat(cmp.toHtml()).contains("QA").contains("UAT").contains("REGRESSION");

        ComparisonReport same = new RegressionComparator(runs).compare(first.runId(), first.runId());
        assertThat(same.count(ComparisonReport.Change.SAME)).isEqualTo(2);
    }
}
