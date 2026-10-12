package org.simulator.config.model;

import java.util.LinkedHashMap;
import java.util.Map;

public class ApiEndpoint {

    private String baseUrl = "https://127.0.0.1:8443";
    private String transactionPath = "/api/v1/transactions";
    /** NONE, API_KEY, BEARER, BASIC. */
    private String authType = "NONE";
    private String apiKeyHeader = "X-API-Key";
    private String apiKey;
    private String username;
    private String password;
    private int timeoutMs = 30_000;
    private int maxRetries = 0;
    private Map<String, String> headers = new LinkedHashMap<>();
    private TlsConfig tls = new TlsConfig();

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getTransactionPath() { return transactionPath; }
    public void setTransactionPath(String transactionPath) { this.transactionPath = transactionPath; }
    public String getAuthType() { return authType; }
    public void setAuthType(String authType) { this.authType = authType; }
    public String getApiKeyHeader() { return apiKeyHeader; }
    public void setApiKeyHeader(String apiKeyHeader) { this.apiKeyHeader = apiKeyHeader; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public int getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
    public Map<String, String> getHeaders() { return headers; }
    public void setHeaders(Map<String, String> headers) { this.headers = headers; }
    public TlsConfig getTls() { return tls; }
    public void setTls(TlsConfig tls) { this.tls = tls; }
}
