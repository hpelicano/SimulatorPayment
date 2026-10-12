package org.simulator.api;

import com.fasterxml.jackson.databind.JsonNode;
import org.simulator.common.SimulatorException;
import org.simulator.common.json.Json;
import org.simulator.config.crypto.SslContexts;
import org.simulator.config.model.ApiEndpoint;
import org.simulator.iso.IsoMessage;
import org.simulator.iso.MessageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLParameters;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;

/**
 * Transactional client for the Base24 REST/JSON API over HTTP/1.1 with optional (mutual) TLS.
 * <p>
 * Requests are the simulator JSON representation of an ISO message
 * ({@code {"dialect","mti","fields":{...}}}); responses in the same format are parsed back.
 */
public class Base24ApiClient {

    private static final Logger log = LoggerFactory.getLogger(Base24ApiClient.class);

    private final ApiEndpoint ep;
    private final UnaryOperator<String> secrets;
    private final MessageService messages;
    private final HttpClient http;

    public Base24ApiClient(ApiEndpoint ep, UnaryOperator<String> secrets, MessageService messages) {
        this.ep = ep;
        this.secrets = secrets != null ? secrets : UnaryOperator.identity();
        this.messages = messages;
        HttpClient.Builder b = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(Math.max(1000, ep.getTimeoutMs())))
                .followRedirects(HttpClient.Redirect.NEVER);
        if (ep.getBaseUrl().toLowerCase(Locale.ROOT).startsWith("https")) {
            b.sslContext(SslContexts.create(ep.getTls(), this.secrets));
            SSLParameters params = new SSLParameters();
            if (ep.getTls().getProtocols() != null && !ep.getTls().getProtocols().isEmpty()) {
                params.setProtocols(ep.getTls().getProtocols().toArray(String[]::new));
            }
            if (ep.getTls().getCipherSuites() != null && !ep.getTls().getCipherSuites().isEmpty()) {
                params.setCipherSuites(ep.getTls().getCipherSuites().toArray(String[]::new));
            }
            b.sslParameters(params);
            if (!ep.getTls().isVerifyHostname()) {
                // The JDK HttpClient only supports disabling hostname verification globally.
                System.setProperty("jdk.internal.httpclient.disableHostnameVerification", "true");
                log.warn("TLS hostname verification disabled for the API client (lab use only)");
            }
        }
        this.http = b.build();
    }

    /** Sends an ISO message as JSON to the transaction endpoint. */
    public CompletableFuture<ApiResponse> send(IsoMessage request) {
        String body = messages.toJson(request);
        return post(ep.getTransactionPath(), body, request);
    }

    /** Raw call (any path/method) for API exploration. */
    public CompletableFuture<ApiResponse> call(String method, String path, String body) {
        return execute(method, path, body, null, 1, System.nanoTime());
    }

    private CompletableFuture<ApiResponse> post(String path, String body, IsoMessage request) {
        return execute("POST", path, body, request, 1, System.nanoTime());
    }

    private CompletableFuture<ApiResponse> execute(String method, String path, String body, IsoMessage request,
                                                   int attempt, long start) {
        HttpRequest req = build(method, path, body);
        return http.sendAsync(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .handle((resp, err) -> {
                    boolean retryable = err != null ? isRetryable(err) : resp.statusCode() >= 500 || resp.statusCode() == 429;
                    if (retryable && attempt <= ep.getMaxRetries()) {
                        long backoff = 200L * (1L << (attempt - 1));
                        log.info("API {} {} attempt {} failed ({}), retrying in {} ms", method, path, attempt,
                                err != null ? err.getClass().getSimpleName() : resp.statusCode(), backoff);
                        return CompletableFuture.supplyAsync(() -> null,
                                        CompletableFuture.delayedExecutor(backoff, TimeUnit.MILLISECONDS))
                                .thenCompose(x -> execute(method, path, body, request, attempt + 1, start));
                    }
                    if (err != null) {
                        return CompletableFuture.<ApiResponse>failedFuture(unwrap(err));
                    }
                    return CompletableFuture.completedFuture(toResponse(resp, body, request, attempt, start));
                })
                .thenCompose(f -> f);
    }

    private ApiResponse toResponse(HttpResponse<String> resp, String body, IsoMessage request, int attempts, long start) {
        IsoMessage parsed = null;
        String text = resp.body();
        if (text != null && !text.isBlank()) {
            try {
                JsonNode node = Json.mapper().readTree(text);
                if (node.has("mti") || node.has("fields")) {
                    if (!node.has("dialect") && request != null) {
                        ((com.fasterxml.jackson.databind.node.ObjectNode) node).put("dialect", request.getDialect());
                    }
                    parsed = messages.fromJsonNode(node);
                }
            } catch (Exception e) {
                log.debug("API response is not an ISO JSON message: {}", e.getMessage());
            }
        }
        return new ApiResponse(resp.statusCode(), resp.headers().map(), body, text, request, parsed,
                System.nanoTime() - start, attempts, resp.version().name());
    }

    private HttpRequest build(String method, String path, String body) {
        String url = ep.getBaseUrl().replaceAll("/+$", "") + (path.startsWith("/") ? path : "/" + path);
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(ep.getTimeoutMs()))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json");
        switch (ep.getAuthType() == null ? "NONE" : ep.getAuthType().toUpperCase(Locale.ROOT)) {
            case "API_KEY" -> b.header(ep.getApiKeyHeader(), nonNull(secrets.apply(ep.getApiKey()), "apiKey"));
            case "BEARER" -> b.header("Authorization", "Bearer " + nonNull(secrets.apply(ep.getApiKey()), "apiKey"));
            case "BASIC" -> {
                String user = nonNull(ep.getUsername(), "username");
                String pass = nonNull(secrets.apply(ep.getPassword()), "password");
                b.header("Authorization", "Basic " + Base64.getEncoder()
                        .encodeToString((user + ":" + pass).getBytes(StandardCharsets.UTF_8)));
            }
            default -> { }
        }
        ep.getHeaders().forEach(b::header);
        HttpRequest.BodyPublisher pub = body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8);
        return b.method(method.toUpperCase(Locale.ROOT), pub).build();
    }

    private static String nonNull(String v, String name) {
        if (v == null || v.isEmpty()) {
            throw new SimulatorException("API credential '" + name + "' is not configured");
        }
        return v;
    }

    private static boolean isRetryable(Throwable err) {
        Throwable t = unwrap(err);
        return t instanceof IOException && !(t instanceof HttpTimeoutException);
    }

    private static Throwable unwrap(Throwable t) {
        while (t instanceof CompletionException && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }

    public static boolean isTimeout(Throwable t) {
        return unwrap(t) instanceof HttpTimeoutException;
    }

    public ApiEndpoint endpoint() {
        return ep;
    }

    public String describe() {
        return ep.getBaseUrl() + ep.getTransactionPath() + " (HTTP/1.1" + (ep.getBaseUrl().startsWith("https") ? ", TLS" : "") + ")";
    }
}
