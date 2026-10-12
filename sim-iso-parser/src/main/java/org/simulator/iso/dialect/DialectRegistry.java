package org.simulator.iso.dialect;

import org.simulator.common.SimulatorException;
import org.simulator.common.json.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Registry of message dialects. Built-in dialects are loaded from {@code /formats/*.json} (listed in
 * {@code /formats/index.json}); user dialects can be loaded from a directory and override built-ins by id.
 */
public class DialectRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(DialectRegistry.class);

    private final Map<String, MessageDialect> raw = new LinkedHashMap<>();
    private final Map<String, MessageDialect> resolved = new LinkedHashMap<>();

    public static DialectRegistry builtIn() {
        DialectRegistry r = new DialectRegistry();
        String[] ids = Json.read(resource("index.json"), String[].class);
        for (String id : ids) {
            r.register(Json.read(resource(id + ".json"), MessageDialect.class));
        }
        r.resolveAll();
        return r;
    }

    /** Loads every {@code *.json} dialect in {@code dir}, replacing built-ins with the same id. */
    public DialectRegistry loadDirectory(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return this;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.json")) {
            for (Path p : ds) {
                register(Json.read(p, MessageDialect.class));
                LOG.info("Loaded user dialect {}", p.getFileName());
            }
        } catch (IOException e) {
            throw new SimulatorException("Cannot read dialect directory " + dir, e);
        }
        resolveAll();
        return this;
    }

    public void register(MessageDialect dialect) {
        if (dialect.getId() == null || dialect.getId().isBlank()) {
            throw new SimulatorException("Dialect without id");
        }
        raw.put(dialect.getId(), dialect);
    }

    public MessageDialect get(String id) {
        MessageDialect d = resolved.get(id);
        if (d == null) {
            throw new SimulatorException("Unknown dialect '" + id + "'. Available: " + resolved.keySet());
        }
        return d;
    }

    public boolean contains(String id) {
        return resolved.containsKey(id);
    }

    public List<String> ids() {
        return new ArrayList<>(resolved.keySet());
    }

    public void resolveAll() {
        resolved.clear();
        for (String id : raw.keySet()) {
            resolve(id, new HashSet<>());
        }
    }

    private MessageDialect resolve(String id, Set<String> visiting) {
        MessageDialect done = resolved.get(id);
        if (done != null) {
            return done;
        }
        if (!visiting.add(id)) {
            throw new SimulatorException("Cyclic dialect inheritance at " + id);
        }
        MessageDialect src = raw.get(id);
        if (src == null) {
            throw new SimulatorException("Dialect not found: " + id);
        }
        // work on a fresh copy so re-resolution after overrides is idempotent
        MessageDialect copy = Json.read(Json.write(src), MessageDialect.class);
        MessageDialect parent = copy.getExtendsId() == null ? null : resolve(copy.getExtendsId(), visiting);
        copy.resolve(parent);
        resolved.put(id, copy);
        return copy;
    }

    private static InputStream resource(String name) {
        InputStream in = DialectRegistry.class.getResourceAsStream("/formats/" + name);
        if (in == null) {
            throw new SimulatorException("Missing built-in format resource " + name);
        }
        return in;
    }
}
