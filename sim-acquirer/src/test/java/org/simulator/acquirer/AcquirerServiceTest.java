package org.simulator.acquirer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simulator.common.event.EventBus;
import org.simulator.common.event.TransactionEvent;
import org.simulator.common.event.TransactionEvent.Outcome;
import org.simulator.iso.IsoMessage;
import org.simulator.iso.MessageService;
import org.simulator.iso.dialect.DialectRegistry;
import org.simulator.persistence.Database;
import org.simulator.persistence.Jdbc;
import org.simulator.persistence.repo.TransactionLogRepository;
import org.simulator.transport.Framing;
import org.simulator.transport.IsoServer;
import org.simulator.transport.TransportOptions;
import org.simulator.validator.MessageValidator;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class AcquirerServiceTest {

    private final MessageService messages = new MessageService(DialectRegistry.builtIn());
    private final EventBus bus = new EventBus();
    private final List<TransactionEvent> events = new CopyOnWriteArrayList<>();
    private Database db;
    private TransactionLogRepository txLog;
    private IsoServer server;
    private AcquirerService acquirer;
    private final List<IsoMessage> received = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        db = Database.inMemory("acq" + System.nanoTime());
        txLog = new TransactionLogRepository(new Jdbc(db));
        bus.subscribe(TransactionEvent.class, events::add);
        TransportOptions serverOpts = TransportOptions.of("127.0.0.1", 0, Framing.LEN2_BINARY, "base24-pos");
        server = new IsoServer(serverOpts, messages, (req, raw) -> {
            received.add(req);
            IsoMessage resp = req.copy();
            resp.setMti(IsoMessage.responseMti(req.getMti()));
            resp.setHeader(messages.responseHeader(req.getDialect(), req.getHeader()));
            resp.remove(35).remove(52);
            if ("000000999999".equals(req.get(4))) {
                return IsoServer.Reply.none();
            }
            resp.set(39, "000000050000".equals(req.get(4)) ? "51" : "00");
            return IsoServer.Reply.of(resp);
        }).start();
        acquirer = new AcquirerService(messages, new MessageValidator(), bus, txLog);
        TransportOptions clientOpts = serverOpts.withPort(server.port()).withTimeout(500);
        acquirer.useChannel(new IsoClientChannel(clientOpts, messages).connect());
    }

    @AfterEach
    void tearDown() {
        acquirer.close();
        server.close();
        txLog.close();
        db.close();
    }

    @Test
    void templatesResolvePlaceholders() {
        TemplateRepository repo = TemplateRepository.builtIn();
        assertThat(repo.all()).extracting(TransactionTemplate::getId)
                .contains("purchase", "withdrawal", "balance", "refund", "preauth", "echo");
        IsoMessage m = acquirer.build(repo.get("purchase"), Map.of("AMOUNT", "000000001234"));
        assertThat(m.getDialect()).isEqualTo("base24-pos");
        assertThat(m.get(4)).isEqualTo("000000001234");
        assertThat(m.get(11)).matches("\\d{6}");
        assertThat(m.get(37)).endsWith(m.get(11)).hasSize(12);
        assertThat(m.get(7)).matches("\\d{10}");
        assertThat(m.get(22)).isEqualTo("051");
        IsoMessage m2 = acquirer.build(repo.get("purchase"), null);
        assertThat(Integer.parseInt(m2.get(11))).isEqualTo(Integer.parseInt(m.get(11)) + 1);
        assertThat(messages.unpack("base24-pos", messages.pack(m)).fields()).isEqualTo(m.fields());
    }

    @Test
    void approvedDeclinedAndTimeout() throws Exception {
        TransactionTemplate purchase = TemplateRepository.builtIn().get("purchase");

        TransactionOutcome ok = acquirer.send(purchase, null, "ACQUIRER").get(5, TimeUnit.SECONDS);
        assertThat(ok.status()).isEqualTo(Outcome.APPROVED);
        assertThat(ok.validation().passed()).as(ok.validation().toString()).isTrue();
        assertThat(ok.response().getHeader()).isEqualTo("ISO026000055");

        TransactionOutcome nsf = acquirer.send(purchase, Map.of("AMOUNT", "000000050000"), "ACQUIRER")
                .get(5, TimeUnit.SECONDS);
        assertThat(nsf.status()).isEqualTo(Outcome.DECLINED);
        assertThat(nsf.responseCode()).isEqualTo("51");
        assertThat(nsf.validation().passed()).isFalse();

        acquirer.setAutoReversalOnTimeout(true);
        TransactionOutcome to = acquirer.send(purchase, Map.of("AMOUNT", "000000999999"), "ACQUIRER")
                .get(5, TimeUnit.SECONDS);
        assertThat(to.status()).isEqualTo(Outcome.TIMEOUT);
        assertThat(received).anySatisfy(r -> {
            assertThat(r.getMti()).isEqualTo("0420");
            assertThat(r.get(90)).startsWith("0200" + to.request().get(11));
            assertThat(r.get(39)).isEqualTo("68");
        });

        TransactionOutcome echo = acquirer.echoTest().get(5, TimeUnit.SECONDS);
        assertThat(echo.status()).isEqualTo(Outcome.APPROVED);

        assertThat(events).extracting(TransactionEvent::outcome)
                .contains(Outcome.APPROVED, Outcome.DECLINED, Outcome.TIMEOUT);
        txLog.flush();
        assertThat(txLog.count()).isGreaterThanOrEqualTo(4);
        assertThat(txLog.recent(10)).allSatisfy(r -> {
            if (r.panMasked() != null) {
                assertThat(r.panMasked()).isEqualTo("4761739001010010");
            }
            if (r.requestJson() != null && r.panMasked() != null) {
                assertThat(r.requestJson()).contains("4761739001010010");
            }
        });
    }

    @Test
    void persistedMessagesAndAuditKeepPlainPanButRedactPin() throws Exception {
        IsoMessage request = acquirer.build(TemplateRepository.builtIn().get("purchase"), null)
                .set(52, "ABCDEF0123456789").set(53, "2600000000000000")
                .set(55, "5A084761739001010010570A4761739001010010D251"
                        + "9F6B0A4761739001010010D251"
                        + "5613" + org.simulator.common.util.Hex.encode(
                                "%B4761739001010010^".getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        acquirer.send(request, null, "ACQUIRER").get(5, TimeUnit.SECONDS);
        txLog.flush();
        assertThat(txLog.recent(1)).singleElement().satisfies(r -> {
            assertThat(r.panMasked()).isEqualTo("4761739001010010");
            IsoMessage stored = messages.fromJson(r.requestJson());
            assertThat(stored.get(2)).isEqualTo(request.get(2));
            assertThat(stored.get(35)).startsWith(request.get(2));
            assertThat(stored.get(55)).isEqualTo(request.get(55));
            assertThat(stored.get(52)).isEqualTo("[REMOVED]");
            assertThat(stored.get(53)).isEqualTo("[REMOVED]");
            assertThat(r.requestJson()).doesNotContain("ABCDEF0123456789", "2600000000000000");
            assertThat(r.requestHex()).isNull();
        });
        assertThat(new Jdbc(db).query("SELECT request FROM audit_log ORDER BY id DESC LIMIT 1", rs -> rs.getString(1)))
                .singleElement().satisfies(json -> assertThat(json).contains("4761739001010010")
                        .doesNotContain("ABCDEF0123456789", "2600000000000000"));
        assertThat(received).singleElement().satisfies(r -> assertThat(r.get(52)).isEqualTo(request.get(52)));
    }

    @Test
    void notConnectedIsReported() {
        acquirer.disconnect();
        assertThat(acquirer.isConnected()).isFalse();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> acquirer.echoTest())
                .hasMessageContaining("not connected");
    }
}
