package org.simulator.acquirer;

import com.fasterxml.jackson.core.type.TypeReference;
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
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Holds transaction templates: built-in ones from {@code /templates/index.json} and user ones
 * stored as JSON files in a directory (each file holds one template or an array of templates).
 */
public class TemplateRepository {

    private static final Logger log = LoggerFactory.getLogger(TemplateRepository.class);

    private final Map<String, TransactionTemplate> templates = new LinkedHashMap<>();
    private final Path userDir;

    public TemplateRepository(Path userDir) {
        this.userDir = userDir;
    }

    public static TemplateRepository builtIn() {
        TemplateRepository r = new TemplateRepository(null);
        r.loadBuiltIn();
        return r;
    }

    public synchronized TemplateRepository loadBuiltIn() {
        try (InputStream idx = TemplateRepository.class.getResourceAsStream("/templates/index.json")) {
            if (idx == null) {
                return this;
            }
            List<String> files = Json.mapper().readValue(idx, new TypeReference<List<String>>() { });
            for (String f : files) {
                try (InputStream in = TemplateRepository.class.getResourceAsStream("/templates/" + f)) {
                    if (in == null) {
                        throw new SimulatorException("Missing built-in template " + f);
                    }
                    readInto(Json.mapper().readTree(in));
                }
            }
        } catch (IOException e) {
            throw new SimulatorException("Cannot load built-in templates", e);
        }
        return this;
    }

    public synchronized TemplateRepository loadUserTemplates() {
        if (userDir == null || !Files.isDirectory(userDir)) {
            return this;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(userDir, "*.json")) {
            for (Path p : ds) {
                try {
                    readInto(Json.mapper().readTree(p.toFile()));
                } catch (IOException e) {
                    log.warn("Skipping invalid template file {}: {}", p, e.getMessage());
                }
            }
        } catch (IOException e) {
            throw new SimulatorException("Cannot read templates from " + userDir, e);
        }
        return this;
    }

    private void readInto(com.fasterxml.jackson.databind.JsonNode node) throws IOException {
        if (node.isArray()) {
            for (var n : node) {
                put(Json.mapper().treeToValue(n, TransactionTemplate.class));
            }
        } else {
            put(Json.mapper().treeToValue(node, TransactionTemplate.class));
        }
    }

    public synchronized void put(TransactionTemplate t) {
        if (t.getId() == null || t.getId().isBlank()) {
            throw new SimulatorException("Template id is required");
        }
        if (t.getMti() == null || t.getMti().isBlank()) {
            throw new SimulatorException("Template " + t.getId() + " has no MTI");
        }
        templates.put(t.getId(), t);
    }

    public synchronized TransactionTemplate get(String id) {
        TransactionTemplate t = templates.get(id);
        if (t == null) {
            throw new SimulatorException("Unknown template: " + id);
        }
        return t;
    }

    public synchronized Collection<TransactionTemplate> all() {
        return new ArrayList<>(templates.values());
    }

    /** Persists a template in the user directory as {@code <id>.json}. */
    public synchronized void save(TransactionTemplate t) {
        put(t);
        if (userDir == null) {
            return;
        }
        try {
            Files.createDirectories(userDir);
            Json.mapper().writerWithDefaultPrettyPrinter().writeValue(userDir.resolve(t.getId() + ".json").toFile(), t);
        } catch (IOException e) {
            throw new SimulatorException("Cannot save template " + t.getId(), e);
        }
    }
}
