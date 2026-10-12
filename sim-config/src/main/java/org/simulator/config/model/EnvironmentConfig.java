package org.simulator.config.model;

/** One Base24 environment (DEV/QA/UAT...) and all its connection endpoints. */
public class EnvironmentConfig {

    private String name;
    private String description;
    private TcpEndpoint acquirer = new TcpEndpoint();
    private TcpEndpoint issuer = new TcpEndpoint();
    private TcpEndpoint issuerClient = endpoint("127.0.0.1", 9200);
    private TcpEndpoint issuerOutbound = endpoint("127.0.0.1", 9101);
    private String issuerRouting = "FULL_DUPLEX";
    private ApiEndpoint api = new ApiEndpoint();
    private SshEndpoint guardian = new SshEndpoint();
    private PathwayEndpoint pathway = new PathwayEndpoint();
    private DocBuilderConfig docBuilder = new DocBuilderConfig();
    private CryptoConfig crypto = new CryptoConfig();

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    /** Outbound connection used by the acquirer module (simulated POS/ATM/switch towards Base24). */
    public TcpEndpoint getAcquirer() { return acquirer; }
    public void setAcquirer(TcpEndpoint acquirer) { this.acquirer = acquirer; }
    /** Listening endpoint used by the issuer module (simulated host authorizer, Base24 connects here). */
    public TcpEndpoint getIssuer() { return issuer; }
    public void setIssuer(TcpEndpoint issuer) { this.issuer = issuer; }
    /** Outbound connection used by the issuer module in client mode (simulated brand such as Visa/Mastercard). */
    public TcpEndpoint getIssuerClient() { return issuerClient; }
    public void setIssuerClient(TcpEndpoint issuerClient) {
        this.issuerClient = issuerClient == null ? endpoint("127.0.0.1", 9200) : issuerClient;
    }
    /** Output port (port 2) used by the issuer server when responses are routed on a separate connection. */
    public TcpEndpoint getIssuerOutbound() { return issuerOutbound; }
    public void setIssuerOutbound(TcpEndpoint issuerOutbound) {
        this.issuerOutbound = issuerOutbound == null ? endpoint("127.0.0.1", 9101) : issuerOutbound;
    }
    /** Issuer server routing: FULL_DUPLEX, SPLIT_LISTEN or SPLIT_CONNECT. */
    public String getIssuerRouting() { return issuerRouting; }
    public void setIssuerRouting(String issuerRouting) {
        this.issuerRouting = issuerRouting == null || issuerRouting.isBlank() ? "FULL_DUPLEX" : issuerRouting;
    }
    public ApiEndpoint getApi() { return api; }
    public void setApi(ApiEndpoint api) { this.api = api; }
    public SshEndpoint getGuardian() { return guardian; }
    public void setGuardian(SshEndpoint guardian) { this.guardian = guardian; }
    public PathwayEndpoint getPathway() { return pathway; }
    public void setPathway(PathwayEndpoint pathway) { this.pathway = pathway; }
    public DocBuilderConfig getDocBuilder() { return docBuilder; }
    public void setDocBuilder(DocBuilderConfig docBuilder) {
        this.docBuilder = docBuilder == null ? new DocBuilderConfig() : docBuilder;
    }
    /** Software-HSM keys and options (PIN block, MAC, EMV cryptograms). */
    public CryptoConfig getCrypto() { return crypto; }
    public void setCrypto(CryptoConfig crypto) { this.crypto = crypto == null ? new CryptoConfig() : crypto; }

    private static TcpEndpoint endpoint(String host, int port) {
        TcpEndpoint ep = new TcpEndpoint();
        ep.setHost(host);
        ep.setPort(port);
        return ep;
    }
}
