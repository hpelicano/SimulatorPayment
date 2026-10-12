package org.simulator.config.model;

public class TcpEndpoint {

    private String host = "127.0.0.1";
    private int port = 0;
    /**
     * Message framing: LEN2_BINARY (default), LEN2_BINARY_INCLUSIVE, LEN2_BINARY_HEADER, LEN4_BINARY,
     * LEN4_ASCII, LEN4_ASCII_INCLUSIVE, NONE (see sim-transport Framing).
     */
    private String framing = "LEN2_BINARY";
    /** Dialect id from the parser registry (iso87-ascii, base24-pos, visa-base1...). */
    private String dialect = "base24-pos";
    private int timeoutMs = 30_000;
    private int connectTimeoutMs = 5_000;
    /** Fields used to correlate request/response, e.g. [11, 41]. */
    private int[] correlationFields = {11, 41};
    private TlsConfig tls = new TlsConfig();

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }
    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }
    public String getFraming() { return framing; }
    public void setFraming(String framing) { this.framing = framing; }
    public String getDialect() { return dialect; }
    public void setDialect(String dialect) { this.dialect = dialect; }
    public int getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
    public int getConnectTimeoutMs() { return connectTimeoutMs; }
    public void setConnectTimeoutMs(int connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }
    public int[] getCorrelationFields() { return correlationFields; }
    public void setCorrelationFields(int[] correlationFields) { this.correlationFields = correlationFields; }
    public TlsConfig getTls() { return tls; }
    public void setTls(TlsConfig tls) { this.tls = tls; }
}
