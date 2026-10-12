package org.simulator.api;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.simulator.common.json.Json;
import org.simulator.config.crypto.SslContexts;
import org.simulator.config.model.ApiEndpoint;
import org.simulator.config.model.TlsConfig;
import org.simulator.iso.IsoMessage;
import org.simulator.iso.MessageService;
import org.simulator.iso.dialect.DialectRegistry;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Base24ApiClientTest {

    @TempDir
    Path dir;
    private final MessageService messages = new MessageService(DialectRegistry.builtIn());
    private final AtomicInteger flaky = new AtomicInteger();

    private void handle(HttpExchange ex) throws IOException {
        byte[] out;
        int status;
        String path = ex.getRequestURI().getPath();
        if (!"k1".equals(ex.getRequestHeaders().getFirst("X-API-Key"))) {
            status = 401;
            out = "{\"error\":\"unauthorized\"}".getBytes(StandardCharsets.UTF_8);
        } else if (path.equals("/flaky") && flaky.getAndIncrement() == 0) {
            status = 503;
            out = new byte[0];
        } else if (path.equals("/slow")) {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException ignored) {
                // test
            }
            status = 200;
            out = "{}".getBytes(StandardCharsets.UTF_8);
        } else {
            ObjectNode req = (ObjectNode) Json.mapper().readTree(ex.getRequestBody());
            ObjectNode resp = req.deepCopy();
            resp.remove("dialect");
            resp.put("mti", IsoMessage.responseMti(req.get("mti").asText()));
            ((ObjectNode) resp.get("fields")).put("39", "00");
            status = 200;
            out = resp.toString().getBytes(StandardCharsets.UTF_8);
        }
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, out.length == 0 ? -1 : out.length);
        if (out.length > 0) {
            ex.getResponseBody().write(out);
        }
        ex.close();
    }

    private ApiEndpoint endpoint(String baseUrl, String path) {
        ApiEndpoint ep = new ApiEndpoint();
        ep.setBaseUrl(baseUrl);
        ep.setTransactionPath(path);
        ep.setAuthType("API_KEY");
        ep.setApiKey("k1");
        ep.setTimeoutMs(1000);
        ep.setMaxRetries(1);
        return ep;
    }

    private IsoMessage purchase() {
        return new IsoMessage("base24-pos", "0200").set(3, "000000").set(4, "000000001000").set(11, "000321");
    }

    @Test
    void plainHttpWithRetriesAndErrors() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            Base24ApiClient client = new Base24ApiClient(endpoint(base, "/api/v1/transactions"), s -> s, messages);
            ApiResponse r = client.send(purchase()).get(5, TimeUnit.SECONDS);
            assertThat(r.status()).isEqualTo(200);
            assertThat(r.protocol()).isEqualTo("HTTP_1_1");
            assertThat(r.response().getMti()).isEqualTo("0210");
            assertThat(r.response().get(39)).isEqualTo("00");
            assertThat(r.response().getDialect()).isEqualTo("base24-pos");

            ApiResponse retried = new Base24ApiClient(endpoint(base, "/flaky"), s -> s, messages)
                    .send(purchase()).get(5, TimeUnit.SECONDS);
            assertThat(retried.status()).isEqualTo(200);
            assertThat(retried.attempts()).isEqualTo(2);

            ApiEndpoint bad = endpoint(base, "/x");
            bad.setApiKey("wrong");
            assertThat(new Base24ApiClient(bad, s -> s, messages).send(purchase()).get(5, TimeUnit.SECONDS).status())
                    .isEqualTo(401);

            assertThatThrownBy(() -> new Base24ApiClient(endpoint(base, "/slow"), s -> s, messages)
                    .send(purchase()).get(5, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .satisfies(e -> assertThat(Base24ApiClient.isTimeout(e.getCause())).isTrue());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void tlsWithTrustStore() throws Exception {
        Path ks = dir.resolve("server.p12");
        Process p = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-alias", "srv", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
                "-dname", "CN=localhost", "-ext", "SAN=ip:127.0.0.1,dns:localhost",
                "-storetype", "PKCS12", "-keystore", ks.toString(), "-storepass", "changeit", "-keypass", "changeit")
                .redirectErrorStream(true).start();
        assertThat(p.waitFor(60, TimeUnit.SECONDS)).isTrue();
        assertThat(p.exitValue()).as(new String(p.getInputStream().readAllBytes())).isZero();

        TlsConfig serverTls = new TlsConfig();
        serverTls.setEnabled(true);
        serverTls.setKeyStorePath(ks.toString());
        serverTls.setKeyStorePassword("changeit");
        serverTls.setTrustAll(true);
        HttpsServer server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(SslContexts.create(serverTls, s -> s)));
        server.createContext("/", this::handle);
        server.start();
        try {
            ApiEndpoint ep = endpoint("https://127.0.0.1:" + server.getAddress().getPort(), "/api/v1/transactions");
            TlsConfig tls = new TlsConfig();
            tls.setEnabled(true);
            tls.setProtocols(List.of("TLSv1.2", "TLSv1.3"));
            tls.setTrustStorePath(ks.toString());
            tls.setTrustStorePassword("changeit");
            ep.setTls(tls);
            // the first TLS handshake in a cold JVM (RSA/SecureRandom init) can exceed the 1 s used elsewhere
            ep.setTimeoutMs(8000);
            ApiResponse r = new Base24ApiClient(ep, s -> s, messages).send(purchase()).get(10, TimeUnit.SECONDS);
            assertThat(r.status()).isEqualTo(200);
            assertThat(r.response().get(39)).isEqualTo("00");
        } finally {
            server.stop(0);
        }
    }
}
