package org.simulator.app;

import org.simulator.config.ConfigService;
import org.simulator.persistence.Database;
import org.simulator.regression.RegressionOptions;
import org.simulator.regression.RegressionReport;
import org.simulator.regression.RegressionRunner;
import org.simulator.acquirer.TransactionOutcome;
import org.simulator.acquirer.TransactionTemplate;
import org.simulator.stress.StressTarget;
import org.simulator.stress.distributed.AgentRequest;
import org.simulator.stress.distributed.StressAgentServer;
import org.simulator.ui.AppContext;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.function.Function;

/**
 * Headless commands:
 * <pre>
 *   --headless regression --suite SMOKE [--env QA] [--concurrency 4] [--junit out.xml] [--html out.html]
 *   --headless issuer [--port 7001] [--env DEV]
 *   --headless agent --token SECRET [--port 7700] [--bind 0.0.0.0] [--name agent1] [--env QA]
 *   (any command) [--metrics 9464]  exposes Prometheus metrics while it runs
 * </pre>
 * The master password is read from the {@code SIM_MASTER_PASSWORD} environment variable.
 */
final class Headless {

    private Headless() {
    }

    static int run(String[] args) throws Exception {
        if (args.length == 0) {
            usage();
            return 2;
        }
        Map<String, String> opts = new HashMap<>();
        for (int i = 1; i < args.length; i++) {
            if (args[i].startsWith("--") && i + 1 < args.length) {
                opts.put(args[i].substring(2), args[++i]);
            }
        }
        String cfg = System.getProperty("simulator.config");
        ConfigService config = new ConfigService(cfg != null ? Path.of(cfg) : ConfigService.defaultDir()).load();
        if (opts.containsKey("env")) {
            config.app().setActiveEnvironment(opts.get("env"));
        }
        String pwd = System.getenv("SIM_MASTER_PASSWORD");
        if (pwd != null && !config.unlock(pwd.toCharArray())) {
            System.err.println("Invalid SIM_MASTER_PASSWORD");
            return 3;
        }
        String data = System.getProperty("simulator.data");
        try (AppContext ctx = new AppContext(config, Database.file(data != null ? Path.of(data) : Database.defaultDir()))) {
            if (opts.containsKey("metrics")) {
                ctx.exporter().start(Integer.parseInt(opts.get("metrics")));
                System.out.println("Prometheus metrics on http://0.0.0.0:" + ctx.exporter().port() + "/metrics");
            }
            return switch (args[0]) {
                case "regression" -> regression(ctx, opts);
                case "issuer" -> issuer(ctx, opts);
                case "agent" -> agent(ctx, opts);
                default -> {
                    usage();
                    yield 2;
                }
            };
        }
    }

    private static int regression(AppContext ctx, Map<String, String> opts) throws Exception {
        String suite = opts.get("suite");
        if (suite == null) {
            System.err.println("--suite is required");
            return 2;
        }
        String suiteId = ctx.testCases().findSuiteId(suite)
                .orElseThrow(() -> new IllegalArgumentException("Suite not found: " + suite));
        ctx.connectAcquirerTcp();
        RegressionOptions o = new RegressionOptions()
                .setConcurrency(Integer.parseInt(opts.getOrDefault("concurrency", "1")))
                .setRefreshDynamicFields(true);
        RegressionReport rep = new RegressionRunner(ctx.acquirer(), ctx.testCases(), ctx.runs())
                .run(suiteId, suite, ctx.env().getName(), o, (r, done, total) ->
                        System.out.printf("[%d/%d] %-6s %s%n", done, total, r.passed() ? "PASS" : "FAIL", r.summary()));
        if (opts.containsKey("junit")) {
            Files.writeString(Path.of(opts.get("junit")), rep.toJUnitXml(), StandardCharsets.UTF_8);
        }
        if (opts.containsKey("html")) {
            Files.writeString(Path.of(opts.get("html")), rep.toHtml(), StandardCharsets.UTF_8);
        }
        System.out.printf("%s: %d passed, %d failed%n", rep.status(), rep.passed(), rep.failed());
        return rep.failed() == 0 ? 0 : 1;
    }

    private static int issuer(AppContext ctx, Map<String, String> opts) throws InterruptedException {
        int port = Integer.parseInt(opts.getOrDefault("port", "-1"));
        ctx.startIssuer(port);
        System.out.println("Issuer simulator listening on port " + ctx.issuer().port() + " (Ctrl+C to stop)");
        CountDownLatch latch = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(latch::countDown));
        latch.await();
        return 0;
    }

    /** Remote load generator controlled by a StressCoordinator (distributed stress). */
    private static int agent(AppContext ctx, Map<String, String> opts) throws Exception {
        String token = opts.getOrDefault("token", System.getenv("SIM_AGENT_TOKEN"));
        if (token == null || token.length() < 8) {
            System.err.println("--token (or SIM_AGENT_TOKEN) with at least 8 characters is required");
            return 2;
        }
        int port = Integer.parseInt(opts.getOrDefault("port", "7700"));
        String bind = opts.getOrDefault("bind", "0.0.0.0");
        String name = opts.getOrDefault("name", java.net.InetAddress.getLocalHost().getHostName());
        Function<AgentRequest, StressTarget> factory = req -> {
            TransactionTemplate tpl = ctx.templates().get(req.getTemplateId());
            if (tpl == null) {
                throw new IllegalArgumentException("Unknown template: " + req.getTemplateId());
            }
            if (!ctx.acquirer().isConnected()) {
                ctx.connectAcquirerTcp();
            }
            ctx.acquirer().setLogPayloads(false);
            Map<String, String> vars = req.getVariables().isEmpty() ? tpl.getVariables() : req.getVariables();
            return seq -> ctx.acquirer().send(tpl, vars, "STRESS").thenApply(TransactionOutcome::status);
        };
        try (StressAgentServer server = new StressAgentServer(name, token, factory).start(bind, port)) {
            System.out.println("Stress agent '" + name + "' listening on " + bind + ":" + server.port()
                    + " (coordinator spec: http://<host>:" + server.port() + "@<token>) - Ctrl+C to stop");
            CountDownLatch latch = new CountDownLatch(1);
            Runtime.getRuntime().addShutdownHook(new Thread(latch::countDown));
            latch.await();
        }
        return 0;
    }

    private static void usage() {
        System.err.println("""
                Usage:
                  java -jar simulator-payment.jar --headless regression --suite <name> [--env <env>] [--concurrency n] [--junit file] [--html file]
                  java -jar simulator-payment.jar --headless issuer [--port n] [--env <env>]
                  java -jar simulator-payment.jar --headless agent --token <secret> [--port 7700] [--bind 0.0.0.0] [--name n] [--env <env>]
                Common options: --metrics <port>  (Prometheus endpoint /metrics)
                Environment: SIM_MASTER_PASSWORD (to decrypt ENC(...) secrets), SIM_AGENT_TOKEN
                System properties: -Dsimulator.config=<dir> -Dsimulator.data=<dir> -Dsimulator.logs=<dir>""");
    }
}
