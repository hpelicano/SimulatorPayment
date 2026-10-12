package org.simulator.common.json;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.simulator.common.SimulatorException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** Shared Jackson configuration and convenience helpers. */
public final class Json {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.INDENT_OUTPUT);

    private Json() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (IOException e) {
            throw new SimulatorException("Cannot serialize to JSON", e);
        }
    }

    public static void write(Path file, Object value) {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            MAPPER.writeValue(file.toFile(), value);
        } catch (IOException e) {
            throw new SimulatorException("Cannot write JSON file " + file, e);
        }
    }

    public static <T> T read(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (IOException e) {
            throw new SimulatorException("Invalid JSON for " + type.getSimpleName() + ": " + e.getMessage(), e);
        }
    }

    public static <T> T read(String json, TypeReference<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (IOException e) {
            throw new SimulatorException("Invalid JSON: " + e.getMessage(), e);
        }
    }

    public static <T> T read(Path file, Class<T> type) {
        try {
            return MAPPER.readValue(file.toFile(), type);
        } catch (IOException e) {
            throw new SimulatorException("Cannot read JSON file " + file + ": " + e.getMessage(), e);
        }
    }

    public static <T> T read(InputStream in, Class<T> type) {
        try (in) {
            return MAPPER.readValue(in, type);
        } catch (IOException e) {
            throw new SimulatorException("Cannot read JSON stream: " + e.getMessage(), e);
        }
    }
}
