package org.simulator.base24;

import com.fasterxml.jackson.databind.JavaType;
import org.simulator.common.SimulatorException;
import org.simulator.common.json.Json;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Loads a JSON array of templates from the classpath and, when present, a user file with the same
 * name in the configuration directory (user entries override built-ins with the same id).
 */
public final class TemplateCatalog<T> {

    private final Map<String, T> items = new LinkedHashMap<>();
    private final Function<T, String> idOf;

    private TemplateCatalog(Function<T, String> idOf) {
        this.idOf = idOf;
    }

    public static <T> TemplateCatalog<T> load(Class<T> type, String resource, Path userFile, Function<T, String> idOf) {
        TemplateCatalog<T> c = new TemplateCatalog<>(idOf);
        JavaType listType = Json.mapper().getTypeFactory().constructCollectionType(List.class, type);
        try (InputStream in = TemplateCatalog.class.getResourceAsStream(resource)) {
            if (in != null) {
                List<T> list = Json.mapper().readValue(in, listType);
                list.forEach(c::put);
            }
            if (userFile != null && Files.exists(userFile)) {
                List<T> list = Json.mapper().readValue(userFile.toFile(), listType);
                list.forEach(c::put);
            }
        } catch (IOException e) {
            throw new SimulatorException("Cannot load templates " + resource + ": " + e.getMessage(), e);
        }
        return c;
    }

    public void put(T item) {
        items.put(idOf.apply(item), item);
    }

    public T get(String id) {
        T t = items.get(id);
        if (t == null) {
            throw new SimulatorException("Unknown template: " + id);
        }
        return t;
    }

    public List<T> all() {
        return new ArrayList<>(items.values());
    }
}
