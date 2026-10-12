package org.simulator.transport;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.simulator.iso.IsoMessage;
import org.simulator.iso.MessageService;
import org.simulator.iso.dialect.DialectRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransportTest {

    static final MessageService MS = new MessageService(DialectRegistry.builtIn());
    IsoServer server;
    IsoClient client;

    @AfterEach
    void tearDown() {
        if (client != null) client.close();
        if (server != null) server.close();
    }

    private void start(Framing framing, IsoServer.RequestHandler handler) {
        TransportOptions opts = TransportOptions.of("127.0.0.1", 0, framing, "base24-pos").withTimeout(2000);
        server = new IsoServer(opts, MS, handler).start();
        client = new IsoClient(opts.withPort(server.port()), MS).connect();
    }

    static IsoMessage req(int stan) {
        return new IsoMessage("base24-pos", "0200").set(3, "000000").set(4, "000000001000")
                .set(11, String.format("%06d", stan)).set(41, "TERM0001");
    }

    static IsoServer.Reply approve(IsoMessage r) {
        return IsoServer.Reply.of(r.copy().setMti(IsoMessage.responseMti(r.getMti())).set(39, "00"));
    }

    @ParameterizedTest
    @EnumSource(Framing.class)
    void roundTripEachFraming(Framing framing) throws Exception {
        start(framing, (r, raw) -> approve(r));
        Exchange ex = client.send(req(1)).get(3, TimeUnit.SECONDS);
        assertThat(ex.response().get(39)).isEqualTo("00");
        assertThat(ex.response().getMti()).isEqualTo("0210");
        assertThat(ex.latencyNanos()).isPositive();
    }

    @Test
    void lengthPrefixesPerFraming() {
        byte[] p = new byte[300];
        assertThat(java.util.Arrays.copyOf(Framing.LEN2_BINARY.frame(p), 2)).containsExactly(0x01, 0x2C);
        assertThat(java.util.Arrays.copyOf(Framing.LEN2_BINARY_INCLUSIVE.frame(p), 2)).containsExactly(0x01, 0x2E);
        assertThat(java.util.Arrays.copyOf(Framing.LEN2_BINARY_HEADER.frame(p), 4)).containsExactly(0x01, 0x2C, 0, 0);
        assertThat(java.util.Arrays.copyOf(Framing.LEN4_BINARY.frame(p), 4)).containsExactly(0, 0, 0x01, 0x2C);
        assertThat(new String(Framing.LEN4_ASCII.frame(p), 0, 4)).isEqualTo("0300");
        assertThat(new String(Framing.LEN4_ASCII_INCLUSIVE.frame(p), 0, 4)).isEqualTo("0304");
        assertThat(Framing.NONE.frame(p)).hasSize(300);
        assertThatThrownBy(() -> Framing.LEN4_ASCII.frame(new byte[10_000])).isInstanceOf(IllegalArgumentException.class);
        assertThat(Framing.parse(" ")).isEqualTo(Framing.LEN2_BINARY);
    }

    @Test
    void pipelinedRequestsAreCorrelatedOutOfOrder() throws Exception {
        start(Framing.LEN2_BINARY, (r, raw) -> new IsoServer.Reply(approve(r).response(),
                Integer.parseInt(r.get(11)) % 2 == 0 ? 150 : 0));
        List<CompletableFuture<Exchange>> futures = new ArrayList<>();
        for (int i = 1; i <= 50; i++) {
            futures.add(client.send(req(i)));
        }
        for (int i = 0; i < futures.size(); i++) {
            assertThat(futures.get(i).get(3, TimeUnit.SECONDS).response().get(11)).isEqualTo(String.format("%06d", i + 1));
        }
    }

    @Test
    void noResponseTimesOut() {
        start(Framing.LEN2_BINARY, (r, raw) -> IsoServer.Reply.none());
        CompletableFuture<Exchange> f = client.send(req(7), 300);
        assertThatThrownBy(() -> f.get(3, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                .cause().isInstanceOf(TransportException.class)
                .matches(t -> ((TransportException) t).isTimeout());
        assertThat(client.inFlight()).isZero();
    }
}
