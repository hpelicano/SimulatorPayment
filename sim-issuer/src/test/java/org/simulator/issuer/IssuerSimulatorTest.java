package org.simulator.issuer;

import org.junit.jupiter.api.Test;
import org.simulator.common.event.EventBus;
import org.simulator.common.event.TransactionEvent;
import org.simulator.iso.IsoMessage;
import org.simulator.iso.MessageService;
import org.simulator.iso.dialect.DialectRegistry;
import org.simulator.transport.Exchange;
import org.simulator.transport.Framing;
import org.simulator.transport.IsoClient;
import org.simulator.transport.IsoServer;
import org.simulator.transport.TransportException;
import org.simulator.transport.TransportOptions;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IssuerSimulatorTest {

    private final MessageService messages = new MessageService(DialectRegistry.builtIn());

    private IsoMessage purchase(String pan, String amount, String stan) {
        return new IsoMessage("base24-pos", "0200")
                .set(2, pan).set(3, "000000").set(4, amount).set(7, "0926120000").set(11, stan)
                .set(35, pan + "=25121011000000000000").set(37, "626912" + stan).set(41, "TERM0001")
                .set(49, "032");
    }

    @Test
    void ruleEngineDecisions() {
        IssuerRuleEngine engine = new IssuerRuleEngine(messages, IssuerRuleEngine.defaultRules());

        IssuerRuleEngine.Decision ok = engine.decide(purchase("4761739001010010", "000000010000", "000001"));
        assertThat(ok.response().getMti()).isEqualTo("0210");
        assertThat(ok.response().get(39)).isEqualTo("00");
        assertThat(ok.response().get(38)).matches("\\d{6}");
        assertThat(ok.response().has(35)).isFalse();
        assertThat(ok.response().get(11)).isEqualTo("000001");
        assertThat(ok.response().getHeader()).isEqualTo("ISO026000055");

        assertThat(engine.decide(purchase("9999990000000001", "000000010000", "000002")).response().get(39))
                .isEqualTo("62");
        assertThat(engine.decide(purchase("4761739001010010", "000020000000", "000003")).response().get(39))
                .isEqualTo("51");
        IssuerRuleEngine.Decision to = engine.decide(purchase("4761739001010010", "000000099999", "000004"));
        assertThat(to.response()).isNull();
        assertThat(engine.decide(purchase("4761739001010010", "000000055555", "000005")).delayMs())
                .isEqualTo(3000);

        IsoMessage bal = purchase("4761739001010010", "000000000000", "000006").set(3, "311000");
        assertThat(engine.decide(bal).response().get(54)).isEqualTo("1001032C000000150000");
    }

    @Test
    void responseCodeSequenceRoundRobin() {
        IssuerRules rules = new IssuerRules();
        ResponseRule r = new ResponseRule();
        r.setName("seq");
        r.getAction().setResponseCodeSequence(List.of("00", "05", "91"));
        r.getAction().getSetFields().put("DE44", "${REQ:41}");
        rules.getRules().add(r);
        IssuerRuleEngine engine = new IssuerRuleEngine(messages, rules);
        List<String> rcs = new java.util.ArrayList<>();
        for (int i = 0; i < 4; i++) {
            IssuerRuleEngine.Decision d = engine.decide(purchase("4761739001010010", "000000000100", "00000" + i));
            rcs.add(d.response().get(39));
            assertThat(d.response().get(44)).isEqualTo("TERM0001");
        }
        assertThat(rcs).containsExactly("00", "05", "91", "00");
    }

    @Test
    void endToEndOverTcp() throws Exception {
        EventBus bus = new EventBus();
        List<TransactionEvent> events = new CopyOnWriteArrayList<>();
        bus.subscribe(TransactionEvent.class, events::add);
        List<IssuerSimulator.Traffic> traffic = new CopyOnWriteArrayList<>();
        TransportOptions opts = TransportOptions.of("127.0.0.1", 0, Framing.LEN2_BINARY, "base24-pos");
        try (IssuerSimulator issuer = new IssuerSimulator(messages, bus, IssuerRuleEngine.defaultRules())) {
            issuer.setTrafficListener(traffic::add);
            issuer.start(opts);
            try (IsoClient client = new IsoClient(opts.withPort(issuer.port()).withTimeout(400), messages).connect()) {
                Exchange ex = client.send(purchase("4761739001010010", "000000010000", "000010")).get(5, TimeUnit.SECONDS);
                assertThat(ex.response().get(39)).isEqualTo("00");
                assertThatThrownBy(() -> client.send(purchase("4761739001010010", "000000099999", "000011"))
                        .get(5, TimeUnit.SECONDS))
                        .isInstanceOf(ExecutionException.class)
                        .hasCauseInstanceOf(TransportException.class);
            }
            assertThat(issuer.handledCount()).isEqualTo(2);
        }
        assertThat(events).extracting(TransactionEvent::outcome)
                .containsExactly(TransactionEvent.Outcome.APPROVED, TransactionEvent.Outcome.TIMEOUT);
        assertThat(traffic).hasSize(2);
    }

    @Test
    void splitListenRoutesResponsesThroughOutputPort() throws Exception {
        TransportOptions in = TransportOptions.of("127.0.0.1", 0, Framing.LEN2_BINARY, "base24-pos");
        TransportOptions out = TransportOptions.of("127.0.0.1", 0, Framing.LEN2_BINARY, "base24-pos");
        BlockingQueue<IsoMessage> received = new LinkedBlockingQueue<>();
        try (IssuerSimulator issuer = new IssuerSimulator(messages, null, IssuerRuleEngine.defaultRules())) {
            issuer.start(in, IssuerSimulator.Routing.SPLIT_LISTEN, out);
            assertThat(issuer.outboundPort()).isNotEqualTo(issuer.port());
            try (IsoClient requests = new IsoClient(in.withPort(issuer.port()), messages).connect();
                 IsoClient responses = new IsoClient(out.withPort(issuer.outboundPort()), messages)) {
                responses.setUnsolicitedListener(received::add);
                responses.connect();
                waitFor(() -> issuer.outboundConnections() == 1);
                requests.setUnsolicitedListener(m -> received.add(new IsoMessage("base24-pos", "WRONG")));
                requests.sendOneWay(purchase("4761739001010010", "000000010000", "000020"));
                IsoMessage resp = received.poll(5, TimeUnit.SECONDS);
                assertThat(resp).isNotNull();
                assertThat(resp.getMti()).isEqualTo("0210");
                assertThat(resp.get(11)).isEqualTo("000020");
                assertThat(received.poll(200, TimeUnit.MILLISECONDS)).isNull();
            }
        }
    }

    @Test
    void splitConnectRoutesResponsesToOriginatorHost() throws Exception {
        BlockingQueue<IsoMessage> received = new LinkedBlockingQueue<>();
        TransportOptions base = TransportOptions.of("127.0.0.1", 0, Framing.LEN2_BINARY, "base24-pos");
        try (IsoServer originator = new IsoServer(base, messages, (m, raw) -> {
            received.add(m);
            return IsoServer.Reply.none();
        }).start();
             IssuerSimulator issuer = new IssuerSimulator(messages, null, IssuerRuleEngine.defaultRules())) {
            issuer.start(base, IssuerSimulator.Routing.SPLIT_CONNECT, base.withPort(originator.port()));
            assertThat(issuer.outboundConnections()).isEqualTo(1);
            try (IsoClient requests = new IsoClient(base.withPort(issuer.port()), messages).connect()) {
                requests.sendOneWay(purchase("9999990000000001", "000000010000", "000021"));
                IsoMessage resp = received.poll(5, TimeUnit.SECONDS);
                assertThat(resp).isNotNull();
                assertThat(resp.getMti()).isEqualTo("0210");
                assertThat(resp.get(39)).isEqualTo("62");
            }
        }
    }

    private static void waitFor(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long end = System.currentTimeMillis() + 5000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < end) {
            Thread.sleep(20);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }
}
