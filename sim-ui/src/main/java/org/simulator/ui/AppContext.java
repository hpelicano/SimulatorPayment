package org.simulator.ui;

import org.simulator.acquirer.AcquirerService;
import org.simulator.acquirer.IsoClientChannel;
import org.simulator.acquirer.TemplateRepository;
import org.simulator.api.Base24ApiClient;
import org.simulator.base24.guardian.GuardianQueryService;
import org.simulator.base24.pathway.CommandPolicy;
import org.simulator.base24.pathway.PathwayCommandPort;
import org.simulator.base24.pathway.PathwayService;
import org.simulator.base24.pathway.SocketPathwayClient;
import org.simulator.base24.pathway.SshPathwayClient;
import org.simulator.common.event.EventBus;
import org.simulator.config.ConfigService;
import org.simulator.config.model.EnvironmentConfig;
import org.simulator.config.model.PathwayEndpoint;
import org.simulator.config.model.TcpEndpoint;
import org.simulator.crypto.CryptoService;
import org.simulator.config.crypto.SecretCipher;
import org.simulator.regression.replay.TrafficRecorder;
import org.simulator.stats.PrometheusExporter;
import org.simulator.iso.MessageService;
import org.simulator.iso.dialect.DialectRegistry;
import org.simulator.issuer.IssuerRuleEngine;
import org.simulator.issuer.IssuerSimulator;
import org.simulator.persistence.Database;
import org.simulator.persistence.DbAdminService;
import org.simulator.persistence.Jdbc;
import org.simulator.persistence.repo.RunRepository;
import org.simulator.persistence.repo.TestCaseRepository;
import org.simulator.persistence.repo.TransactionLogRepository;
import org.simulator.ssh.SshClient;
import org.simulator.stats.StatisticsCollector;
import org.simulator.stress.StressEngine;
import org.simulator.transport.Framing;
import org.simulator.transport.TransportConfigs;
import org.simulator.transport.TransportOptions;
import org.simulator.validator.MessageValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Manual dependency wiring for the desktop application. Remote clients (SSH, Pathway, API)
 * are created lazily from the active environment and discarded when the configuration changes.
 */
public class AppContext implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AppContext.class);
    private static final long UNLOCK_RETRY_MS = 15_000;

    private final ConfigService config;
    private final Database database;
    private final Jdbc jdbc;
    private final DbAdminService dbAdmin;
    private final TestCaseRepository testCases;
    private final RunRepository runs;
    private final TransactionLogRepository txLog;
    private final MessageService messages;
    private final MessageValidator validator = new MessageValidator();
    private final EventBus bus = new EventBus();
    private final StatisticsCollector stats;
    private final AcquirerService acquirer;
    private final IssuerSimulator issuer;
    private final AcquirerService issuerClient;
    private final java.util.List<java.util.function.Consumer<org.simulator.iso.IsoMessage>> issuerClientUnsolicited =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    private final TemplateRepository templates;
    private final StressEngine stressEngine = new StressEngine();
    private final CryptoService crypto;
    private final TrafficRecorder recorder;
    private final PrometheusExporter exporter;
    private volatile BooleanSupplier unlockPrompt;
    private final java.util.concurrent.locks.ReentrantLock unlockLock = new java.util.concurrent.locks.ReentrantLock();
    private volatile long unlockDeclinedAt;

    private SshClient ssh;
    private final Map<String, PathwayService> pathwayServers = new HashMap<>();
    private SshClient pathwaySsh;
    private Base24ApiClient api;
    private GuardianQueryService guardianQueries;

    public AppContext(ConfigService config, Database database) {
        this.config = config;
        this.database = database;
        this.jdbc = new Jdbc(database);
        this.dbAdmin = new DbAdminService(database);
        this.testCases = new TestCaseRepository(jdbc);
        this.runs = new RunRepository(jdbc);
        this.txLog = new TransactionLogRepository(jdbc);
        DialectRegistry registry = DialectRegistry.builtIn();
        Path userFormats = config.getConfigDir().resolve("formats");
        if (Files.isDirectory(userFormats)) {
            registry.loadDirectory(userFormats);
        }
        this.messages = new MessageService(registry);
        this.stats = new StatisticsCollector().attach(bus);
        this.acquirer = new AcquirerService(messages, validator, bus, txLog);
        this.acquirer.setEnvironment(env().getName());
        this.issuer = new IssuerSimulator(messages, bus, loadIssuerRules());
        this.issuerClient = new AcquirerService(messages, validator, bus, txLog);
        this.issuerClient.setEnvironment(env().getName());
        this.crypto = new CryptoService(() -> env().getCrypto(), this::secret, messages);
        this.acquirer.setRequestInterceptor(crypto::prepareRequest);
        this.issuerClient.setRequestInterceptor(crypto::prepareRequest);
        this.issuer.setResponseProcessor(crypto::processResponse);
        this.recorder = new TrafficRecorder(messages);
        this.acquirer.addOutcomeListener(recorder::onOutcome);
        this.issuerClient.addOutcomeListener(recorder::onOutcome);
        this.issuer.addTrafficObserver(t -> recorder.record("ISSUER", t.request(), t.response() == null ? null : t.response().get(39),
                t.response() == null ? "TIMEOUT" : org.simulator.common.event.TransactionEvent.outcomeFor(t.response().get(39)).name(), t.delayMs() * 1000));
        this.exporter = new PrometheusExporter(stats::snapshot).attach(bus);
        exporter.registerGauge("simulator_acquirer_connected", "1 if the acquirer channel is connected", () -> acquirer.isConnected() ? 1 : 0);
        exporter.registerGauge("simulator_issuer_connections", "Open connections to the issuer simulator", issuer::connections);
        exporter.registerGauge("simulator_stress_running", "1 while a stress run is active", () -> stressEngine.isRunning() ? 1 : 0);
        this.templates = new TemplateRepository(config.getConfigDir().resolve("templates")).loadBuiltIn().loadUserTemplates();
    }

    private org.simulator.issuer.IssuerRules loadIssuerRules() {
        Path f = config.getConfigDir().resolve("issuer-rules.json");
        return Files.exists(f) ? IssuerRuleEngine.load(f) : IssuerRuleEngine.defaultRules();
    }

    public EnvironmentConfig env() {
        return config.activeEnvironment();
    }

    /** Resolves a configuration value, asking for the master password the first time an ENC(...) secret is needed. */
    public String secret(String v) {
        if (SecretCipher.isEncrypted(v) && !config.isUnlocked()) {
            unlock(false);
        }
        return config.secret(v);
    }

    /**
     * Installs the interactive unlock (master password dialog). It is invoked lazily, never at startup.
     * The supplier must be callable from any thread.
     */
    public void setUnlockPrompt(BooleanSupplier prompt) {
        this.unlockPrompt = prompt;
    }

    /** Explicit user action (encrypt, save/open recording...): always shows the dialog if still locked. */
    public boolean ensureUnlocked() {
        return unlock(true);
    }

    /**
     * Only one dialog at a time. Implicit requests (secret resolution from background tasks, e.g. stress)
     * are not re-prompted for {@link #UNLOCK_RETRY_MS} after the user declines, to avoid a dialog storm.
     * The FX thread never blocks waiting for the lock: a background task may be waiting for the FX thread
     * to show the very same dialog.
     */
    private boolean unlock(boolean explicit) {
        if (config.isUnlocked()) {
            return true;
        }
        BooleanSupplier prompt = unlockPrompt;
        if (prompt == null) {
            return false;
        }
        boolean fx = javafx.application.Platform.isFxApplicationThread();
        if (fx) {
            if (!unlockLock.tryLock()) {
                return config.isUnlocked();
            }
        } else {
            unlockLock.lock();
        }
        try {
            if (config.isUnlocked()) {
                return true;
            }
            if (!explicit && System.currentTimeMillis() - unlockDeclinedAt < UNLOCK_RETRY_MS) {
                return false;
            }
            boolean ok = prompt.getAsBoolean();
            unlockDeclinedAt = ok ? 0 : System.currentTimeMillis();
            return ok;
        } finally {
            unlockLock.unlock();
        }
    }
    // ------------------------------------------------------------------ acquirer / issuer

    public void connectAcquirerTcp() {
        connectAcquirerTcp(null);
    }

    /** @param framing overrides the endpoint framing for this connection (null = use environments.json). */
    public void connectAcquirerTcp(Framing framing) {
        TransportOptions opts = TransportConfigs.from(env().getAcquirer(), this::secret).withFraming(framing);
        acquirer.useChannel(new IsoClientChannel(opts, messages).connect());
    }

    public void connectAcquirerApi() {
        acquirer.useChannel(new ApiChannel(api(), env().getAcquirer().getDialect()));
    }

    public void startIssuer(int port) {
        EnvironmentConfig e = env();
        TcpEndpoint out = e.getIssuerOutbound();
        startIssuer(null, port, IssuerSimulator.Routing.parse(e.getIssuerRouting()), out.getHost(), out.getPort());
    }

    public void startIssuer(String host, int port, IssuerSimulator.Routing routing, String outHost, int outPort) {
        startIssuer(host, port, null, routing, outHost, outPort, null);
    }

    /**
     * Starts the issuer server on {@code host:port} (input port 1). With a split routing the responses leave
     * through {@code outHost:outPort} (port 2): listening on it (SPLIT_LISTEN) or connecting to it (SPLIT_CONNECT).
     * A null framing keeps the one configured in the endpoint.
     */
    public void startIssuer(String host, int port, Framing framing, IssuerSimulator.Routing routing,
                            String outHost, int outPort, Framing outFraming) {
        TransportOptions in = TransportConfigs.from(env().getIssuer(), this::secret).withFraming(framing);
        if (host != null && !host.isBlank()) {
            in = in.withHost(host.trim());
        }
        if (port >= 0) {
            in = in.withPort(port);
        }
        TransportOptions out = null;
        if (routing != null && routing != IssuerSimulator.Routing.FULL_DUPLEX) {
            out = TransportConfigs.from(env().getIssuerOutbound(), this::secret).withFraming(outFraming);
            if (outHost != null && !outHost.isBlank()) {
                out = out.withHost(outHost.trim());
            }
            if (outPort >= 0) {
                out = out.withPort(outPort);
            }
        }
        issuer.start(in, routing, out);
    }

    public void connectIssuerClient(String host, int port) {
        connectIssuerClient(host, port, null);
    }

    /** Issuer client mode: independent outgoing connection simulating a brand (Visa, Mastercard...). */
    public void connectIssuerClient(String host, int port, Framing framing) {
        TransportOptions opts = TransportConfigs.from(env().getIssuerClient(), this::secret).withFraming(framing);
        if (host != null && !host.isBlank()) {
            opts = opts.withHost(host.trim());
        }
        if (port >= 0) {
            opts = opts.withPort(port);
        }
        IsoClientChannel channel = new IsoClientChannel(opts, messages);
        channel.client().setUnsolicitedListener(m -> issuerClientUnsolicited.forEach(l -> l.accept(m)));
        issuerClient.useChannel(channel.connect());
    }

    public void addIssuerClientUnsolicitedListener(java.util.function.Consumer<org.simulator.iso.IsoMessage> listener) {
        issuerClientUnsolicited.add(listener);
    }

    public void removeIssuerClientUnsolicitedListener(java.util.function.Consumer<org.simulator.iso.IsoMessage> listener) {
        issuerClientUnsolicited.remove(listener);
    }

    // ------------------------------------------------------------------ remote Base24

    public synchronized SshClient ssh() {
        if (ssh == null) {
            ssh = new SshClient(env().getGuardian(), this::secret);
        }
        return ssh;
    }

    private Map<String, String> guardianContext() {
        Map<String, String> ctx = new HashMap<>();
        String sub = env().getGuardian().getDefaultSubvolume();
        if (sub != null) {
            ctx.put("subvol", sub);
        }
        return ctx;
    }

    public synchronized GuardianQueryService guardianQueries() {
        if (guardianQueries == null) {
            guardianQueries = new GuardianQueryService(cmd -> ssh().exec(cmd),
                    GuardianQueryService.defaultCatalog(config.getConfigDir().resolve("guardian-queries.json")),
                    runs, guardianContext());
        }
        return guardianQueries;
    }

    public synchronized PathwayService pathway() {
        PathwayEndpoint ep = env().getPathway();
        if (ep.serverNames().isEmpty()) {
            throw new org.simulator.common.SimulatorException("Configure al menos un server en pathway.servers.");
        }
        String server = ep.serverNames().contains(ep.getServerName()) ? ep.getServerName() : ep.serverNames().get(0);
        return pathway(server);
    }

    public synchronized PathwayService pathway(String server) {
        PathwayEndpoint ep = env().getPathway();
        if (server == null || !ep.serverNames().contains(server) || !server.matches("\\$?[A-Za-z0-9_.-]{1,80}")) {
            throw new org.simulator.common.SimulatorException("Seleccione un server configurado en pathway.servers.");
        }
        return pathwayServers.computeIfAbsent(server, name -> {
            PathwayCommandPort port;
            if ("SSH".equalsIgnoreCase(ep.getMode())) {
                if (ep.getUsername() == null || ep.getUsername().isBlank()) {
                    throw new org.simulator.common.SimulatorException("Configure pathway.username y credenciales SSH propias.");
                }
                if (pathwaySsh == null) pathwaySsh = new SshClient(ep.sshEndpoint(), this::secret);
                port = new SshPathwayClient(pathwaySsh, ep, ep.getTaclPrompt(), "PATHCOM " + name);
            } else if ("SOCKET".equalsIgnoreCase(ep.getMode())) {
                port = new SocketPathwayClient(ep);
            } else {
                throw new org.simulator.common.SimulatorException("pathway.mode debe ser SSH o SOCKET");
            }
            return new PathwayService(port, new CommandPolicy(ep.getAllowedVerbs(), ep.getConfirmVerbs()), runs);
        });
    }

    public synchronized Base24ApiClient api() {
        if (api == null) {
            api = new Base24ApiClient(env().getApi(), this::secret, messages);
        }

        return api;
    }

    public synchronized java.util.List<PathwayService.CommandResult> pathwayHistory(String server) {
        PathwayService service = pathwayServers.get(server);
        return service == null ? java.util.List.of() : service.history();
    }

    /** Drops remote clients so they are rebuilt with the new configuration. */
    public synchronized void environmentChanged() {
        closeRemote();
        acquirer.setEnvironment(env().getName());
        issuerClient.setEnvironment(env().getName());
        log.info("Active environment: {}", env().getName());
    }

    private synchronized void closeRemote() {
        pathwayServers.values().forEach(PathwayService::close);
        pathwayServers.clear();
        if (pathwaySsh != null) {
            pathwaySsh.close();
            pathwaySsh = null;
        }
        if (ssh != null) {
            ssh.close();
            ssh = null;
        }
        api = null;
        guardianQueries = null;
    }

    /** Operator name from user.json, used in the audit trail. */
    public String user() {
        String u = config.user().getUsername();
        return u == null || u.isBlank() ? System.getProperty("user.name", "operator") : u;
    }

    /** Starts the Prometheus endpoint on the configured port. */
    public void startMetrics() throws java.io.IOException {
        exporter.start(config.user().getMetricsPort());
    }

    public void stopMetrics() {
        exporter.stop();
    }

    public ConfigService config() { return config; }
    public Database database() { return database; }
    public DbAdminService dbAdmin() { return dbAdmin; }
    public TestCaseRepository testCases() { return testCases; }
    public RunRepository runs() { return runs; }
    public TransactionLogRepository txLog() { return txLog; }
    public MessageService messages() { return messages; }
    public MessageValidator validator() { return validator; }
    public EventBus bus() { return bus; }
    public StatisticsCollector stats() { return stats; }
    public AcquirerService acquirer() { return acquirer; }
    public IssuerSimulator issuer() { return issuer; }
    public AcquirerService issuerClient() { return issuerClient; }
    public TemplateRepository templates() { return templates; }
    public StressEngine stressEngine() { return stressEngine; }
    public CryptoService crypto() { return crypto; }
    public TrafficRecorder recorder() { return recorder; }
    public PrometheusExporter exporter() { return exporter; }
    public Jdbc jdbc() { return jdbc; }

    @Override
    public void close() {
        stressEngine.stop();
        exporter.close();
        closeRemote();
        acquirer.close();
        issuerClient.close();
        issuer.close();
        stats.close();
        txLog.close();
        database.close();
    }
}
