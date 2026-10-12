package org.simulator.regression.replay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.simulator.common.SimulatorException;
import org.simulator.common.json.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.UnaryOperator;

/**
 * Saves recordings as JSON. With an encryptor (e.g. {@code ConfigService::encryptSecret}) the whole
 * body is stored as {@code {"encrypted":true,"data":"ENC(...)"}} because it contains clear PAN/track data.
 */
public final class RecordingStore {

    private RecordingStore() {}

    public static void save(Path file, Recording recording, UnaryOperator<String> encryptor) {
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            ObjectNode document = Json.mapper().valueToTree(recording);
            for (JsonNode item : document.path("items")) {
                JsonNode request = Json.mapper().readTree(item.path("requestJson").asText());
                if (request.path("fields") instanceof ObjectNode fields) {
                    java.util.List<String> removed = new java.util.ArrayList<>();
                    fields.fieldNames().forEachRemaining(key -> {
                        if (key.matches("(?i)(?:DE)?0*(52|53)")) removed.add(key);
                    });
                    fields.remove(removed);
                }
                ((ObjectNode) item).put("requestJson", Json.write(request));
            }
            String body = Json.write(document);
            if (encryptor != null) {
                ObjectNode n = Json.mapper().createObjectNode();
                n.put("encrypted", true);
                n.put("name", recording.getName());
                n.put("items", recording.getItems().size());
                n.put("data", encryptor.apply(body));
                body = Json.write(n);
            }
            Files.writeString(file, body, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new SimulatorException("Cannot save recording " + file + ": " + e.getMessage(), e);
        }
    }

    public static Recording load(Path file, UnaryOperator<String> decryptor) {
        try {
            String body = Files.readString(file, StandardCharsets.UTF_8);
            JsonNode n = Json.mapper().readTree(body);
            if (n.path("encrypted").asBoolean(false)) {
                if (decryptor == null) throw new SimulatorException("La grabación está cifrada: desbloquee la contraseña maestra");
                body = decryptor.apply(n.path("data").asText());
            }
            return Json.read(body, Recording.class);
        } catch (IOException e) {
            throw new SimulatorException("Cannot read recording " + file + ": " + e.getMessage(), e);
        }
    }
}