package org.simulator.config;

import org.simulator.common.SimulatorException;
import org.simulator.common.json.Json;
import org.simulator.config.crypto.SecretCipher;
import org.simulator.config.model.AppConfig;
import org.simulator.config.model.EnvironmentConfig;
import org.simulator.config.model.TcpEndpoint;
import org.simulator.config.model.UserConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Loads, validates and saves {@code user.json} and {@code environments.json} from the configuration directory
 * (default {@code ~/.simulator/config}). Secrets are resolved lazily through {@link #secret(String)} once
 * the configuration is unlocked with the master password.
 */
public class ConfigService {

    private static final Logger LOG = LoggerFactory.getLogger(ConfigService.class);
    private static final String CHECK_TOKEN = "SIMULATOR-MASTER-OK";
    public static final String USER_FILE = "user.json";
    public static final String ENV_FILE = "environments.json";

    private final Path configDir;
    private UserConfig user;
    private AppConfig app;
    private SecretCipher cipher;

    public ConfigService(Path configDir) {
        this.configDir = configDir;
    }

    public static Path defaultDir() {
        return Path.of(System.getProperty("user.home"), ".simulator", "config");
    }

    public ConfigService load() {
        try {
            Files.createDirectories(configDir);
            copyDefaultIfMissing(USER_FILE);
            copyDefaultIfMissing(ENV_FILE);
        } catch (IOException e) {
            throw new SimulatorException("Cannot initialize config dir " + configDir, e);
        }
        user = Json.read(configDir.resolve(USER_FILE), UserConfig.class);
        app = Json.read(configDir.resolve(ENV_FILE), AppConfig.class);
        upgradeBase24Configuration();
        List<String> errors = validate();
        if (!errors.isEmpty()) {
            LOG.warn("Configuration has {} problem(s): {}", errors.size(), errors);
        }
        return this;
    }

    private void upgradeBase24Configuration() {
        var source = Json.read(configDir.resolve(ENV_FILE), com.fasterxml.jackson.databind.JsonNode.class);
        var environments = source.path("environments");
        boolean changed = false;
        for (int i = 0; i < app.getEnvironments().size(); i++) {
            var raw = environments.path(i);
            var env = app.getEnvironments().get(i);
            if (!raw.path("pathway").has("servers")) {
                var endpoint = env.getPathway();
                var guardian = env.getGuardian();
                String name = endpoint.getPathmon();
                if (name == null || name.isBlank()) name = "$PM";
                endpoint.setServerName(name);
                endpoint.setServers(new ArrayList<>(List.of(name)));
                endpoint.setMode("SSH");
                endpoint.setPort(22);
                if (endpoint.getUsername() == null) endpoint.setUsername(guardian.getUsername());
                if (endpoint.getPassword() == null) endpoint.setPassword(guardian.getPassword());
                if (endpoint.getPrivateKeyPath() == null) endpoint.setPrivateKeyPath(guardian.getPrivateKeyPath());
                if (endpoint.getPrivateKeyPassphrase() == null) endpoint.setPrivateKeyPassphrase(guardian.getPrivateKeyPassphrase());
                if (endpoint.getKnownHostsPath() == null) endpoint.setKnownHostsPath(guardian.getKnownHostsPath());
                endpoint.setStrictHostKeyChecking(guardian.isStrictHostKeyChecking());
                changed = true;
                LOG.info("Migrated legacy Pathway configuration in {} to independent SSH credentials and servers", env.getName());
            }
            changed |= raw.has("xpnet") || !raw.has("docBuilder");
        }
        if (changed) Json.write(configDir.resolve(ENV_FILE), app);
    }

    public void save() {
        Json.write(configDir.resolve(USER_FILE), user);
        Json.write(configDir.resolve(ENV_FILE), app);
    }

    /**
     * Unlocks secrets. On first use the master password is registered; afterwards it is verified.
     *
     * @return true when the password is valid
     */
    public boolean unlock(char[] masterPassword) {
        SecretCipher candidate = new SecretCipher(masterPassword);
        if (user.getMasterPasswordCheck() == null) {
            user.setMasterPasswordCheck(candidate.encrypt(CHECK_TOKEN));
            save();
            cipher = candidate;
            return true;
        }
        try {
            if (CHECK_TOKEN.equals(candidate.decrypt(user.getMasterPasswordCheck()))) {
                cipher = candidate;
                return true;
            }
        } catch (SimulatorException e) {
            LOG.debug("Master password rejected: {}", e.getMessage());
        }
        return false;
    }

    public boolean isUnlocked() {
        return cipher != null;
    }

    /** Resolves a possibly encrypted value to its plain text. */
    public String secret(String value) {
        if (!SecretCipher.isEncrypted(value)) {
            return value;
        }
        if (cipher == null) {
            throw new SimulatorException("Configuration is locked: master password required");
        }
        return cipher.decrypt(value);
    }

    public String encryptSecret(String plain) {
        if (cipher == null) {
            throw new SimulatorException("Configuration is locked: master password required");
        }
        return cipher.encrypt(plain);
    }

    public List<String> validate() {
        List<String> errors = new ArrayList<>();
        if (app.getEnvironments().isEmpty()) {
            errors.add("No environments defined");
        }
        Set<String> names = new HashSet<>();
        for (EnvironmentConfig env : app.getEnvironments()) {
            String n = env.getName();
            if (n == null || n.isBlank()) {
                errors.add("Environment without name");
                continue;
            }
            if (!names.add(n.toUpperCase())) {
                errors.add("Duplicated environment " + n);
            }
            checkPort(errors, n + ".acquirer", env.getAcquirer());
            checkPort(errors, n + ".issuer", env.getIssuer());
            checkPort(errors, n + ".issuerClient", env.getIssuerClient());
            checkPort(errors, n + ".issuerOutbound", env.getIssuerOutbound());
            if (!List.of("FULL_DUPLEX", "SPLIT_LISTEN", "SPLIT_CONNECT").contains(env.getIssuerRouting())) {
                errors.add(n + ".issuerRouting must be FULL_DUPLEX, SPLIT_LISTEN or SPLIT_CONNECT");
            }
            checkRange(errors, n + ".guardian.port", env.getGuardian().getPort());
            checkRange(errors, n + ".pathway.port", env.getPathway().getPort());
            if (!List.of("SOCKET", "SSH").contains(env.getPathway().getMode())) {
                errors.add(n + ".pathway.mode must be SOCKET or SSH");
            }
            var path = env.getPathway();
            if (path.getHost() == null || path.getHost().isBlank() || path.getPrompt() == null || path.getPrompt().isBlank()
                    || path.getTimeoutMs() <= 0) {
                errors.add(n + ".pathway requires host, prompt and positive timeoutMs");
            }
            Set<String> servers = new HashSet<>();
            if (path.serverNames().isEmpty()) errors.add(n + ".pathway.servers requires at least one server");
            for (String server : path.serverNames()) {
                if (server == null || !server.matches("\\$?[A-Za-z0-9_.-]{1,80}") || !servers.add(server)) {
                    errors.add(n + ".pathway.servers requires unique valid process names");
                }
            }
            if ("SSH".equals(path.getMode())) {
                try {
                    java.util.regex.Pattern.compile(path.getTaclPrompt());
                } catch (IllegalArgumentException | NullPointerException e) {
                    errors.add(n + ".pathway.taclPrompt must be a regular expression");
                }
            }
        }
        if (app.getEnvironments().stream().noneMatch(e -> e.getName() != null
                && e.getName().equalsIgnoreCase(app.getActiveEnvironment()))) {
            errors.add("activeEnvironment '" + app.getActiveEnvironment() + "' is not defined");
        }
        return errors;
    }

    private static final List<String> FRAMINGS = List.of("LEN2_BINARY", "LEN2_BINARY_INCLUSIVE", "LEN2_BINARY_HEADER",
            "LEN4_BINARY", "LEN4_ASCII", "LEN4_ASCII_INCLUSIVE", "NONE");

    private static void checkPort(List<String> errors, String path, TcpEndpoint ep) {
        checkRange(errors, path + ".port", ep.getPort());
        if (ep.getTimeoutMs() <= 0) {
            errors.add(path + ".timeoutMs must be > 0");
        }
        if (ep.getFraming() != null && !ep.getFraming().isBlank()
                && !FRAMINGS.contains(ep.getFraming().trim().toUpperCase())) {
            errors.add(path + ".framing must be one of " + FRAMINGS + ": " + ep.getFraming());
        }
    }

    private static void checkRange(List<String> errors, String path, int port) {
        if (port < 0 || port > 65535) {
            errors.add(path + " out of range: " + port);
        }
    }

    private void copyDefaultIfMissing(String file) throws IOException {
        Path target = configDir.resolve(file);
        if (Files.notExists(target)) {
            try (InputStream in = ConfigService.class.getResourceAsStream("/defaults/" + file)) {
                if (in == null) {
                    throw new SimulatorException("Missing default resource " + file);
                }
                Files.copy(in, target);
                LOG.info("Created default configuration {}", target);
            }
        }
    }

    public Path getConfigDir() { return configDir; }
    public UserConfig user() { return user; }
    public AppConfig app() { return app; }
    public EnvironmentConfig activeEnvironment() { return app.active(); }
}
