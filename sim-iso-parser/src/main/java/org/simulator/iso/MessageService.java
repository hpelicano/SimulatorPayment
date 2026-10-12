package org.simulator.iso;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.simulator.common.SimulatorException;
import org.simulator.common.json.Json;
import org.simulator.common.util.Hex;
import org.simulator.iso.codec.FieldTrace;
import org.simulator.iso.codec.IsoCodec;
import org.simulator.iso.dialect.DialectRegistry;
import org.simulator.iso.dialect.HeaderDef;
import org.simulator.iso.dialect.MessageDialect;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Facade of the parser module: JSON &lt;-&gt; canonical &lt;-&gt; wire (ISO8583 / Base24 / VISA / MC / HPDH / BNET...)
 * and conversion between dialects.
 *
 * <p>JSON representation:</p>
 * <pre>{"dialect":"base24-pos","header":"ISO026000050","mti":"0200","fields":{"3":"000000","4":"000000001000"}}</pre>
 */
public class MessageService {

    private final DialectRegistry registry;
    private final Map<String, IsoCodec> codecs = new ConcurrentHashMap<>();

    public MessageService(DialectRegistry registry) {
        this.registry = registry;
    }

    public DialectRegistry registry() {
        return registry;
    }

    public MessageDialect dialect(String id) {
        return registry.get(id);
    }

    public IsoCodec codec(String dialectId) {
        return codecs.computeIfAbsent(dialectId, id -> new IsoCodec(registry.get(id)));
    }

    /** Invalidate cached codecs after dialects are reloaded. */
    public void clearCache() {
        codecs.clear();
    }

    public byte[] pack(IsoMessage msg) {
        if (msg.getDialect() == null) {
            throw new SimulatorException("Message has no dialect");
        }
        return codec(msg.getDialect()).pack(msg);
    }

    public IsoMessage unpack(String dialectId, byte[] data) {
        return codec(dialectId).unpack(data);
    }

    public List<FieldTrace> trace(String dialectId, byte[] data) {
        return codec(dialectId).trace(data);
    }

    // ------------------------------------------------------------------ JSON

    public String toJson(IsoMessage msg) {
        return Json.write(toJsonNode(msg));
    }

    public ObjectNode toJsonNode(IsoMessage msg) {
        ObjectNode root = Json.mapper().createObjectNode();
        root.put("dialect", msg.getDialect());
        if (msg.getHeader() != null) {
            root.put("header", msg.getHeader());
        }
        root.put("mti", msg.getMti());
        ObjectNode fields = root.putObject("fields");
        msg.fields().forEach((k, v) -> fields.put(String.valueOf(k), v));
        return root;
    }

    public IsoMessage fromJson(String json) {
        try {
            return fromJsonNode(Json.mapper().readTree(json));
        } catch (java.io.IOException e) {
            throw new SimulatorException("Invalid message JSON: " + e.getMessage(), e);
        }
    }

    public IsoMessage fromJsonNode(JsonNode root) {
        IsoMessage msg = new IsoMessage();
        msg.setDialect(text(root, "dialect"));
        msg.setHeader(text(root, "header"));
        msg.setMti(text(root, "mti"));
        JsonNode fields = root.get("fields");
        if (fields != null) {
            Iterator<Map.Entry<String, JsonNode>> it = fields.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                String key = e.getKey().replaceAll("(?i)^(de|f|field)", "");
                if (!e.getValue().isNull()) {
                    msg.set(Integer.parseInt(key), e.getValue().asText());
                }
            }
        }
        return msg;
    }

    public byte[] jsonToWire(String json) {
        return pack(fromJson(json));
    }

    public String wireToJson(String dialectId, byte[] data) {
        return toJson(unpack(dialectId, data));
    }

    public String hexToJson(String dialectId, String hex) {
        return wireToJson(dialectId, Hex.decode(hex));
    }

    // ------------------------------------------------------------------ conversion

    /**
     * Converts a message to another dialect. Field values are canonical so they are kept; the header is replaced by
     * the target default when the source header is not compatible.
     */
    public IsoMessage convert(IsoMessage source, String targetDialect) {
        MessageDialect target = registry.get(targetDialect);
        IsoMessage out = source.copy().setDialect(targetDialect);
        HeaderDef th = target.getHeader();
        out.setHeader(th.getLength() > 0 ? th.getDefaultValue() : null);
        out.fields().keySet().stream().filter(f -> target.field(f) == null).toList().forEach(out::remove);
        return out;
    }

    /** Builds the response header according to the dialect {@code responseRule}. */
    public String responseHeader(String dialectId, String requestHeader) {
        HeaderDef h = registry.get(dialectId).getHeader();
        if (h.getLength() == 0) {
            return null;
        }
        String rule = h.getResponseRule() == null ? "ECHO" : h.getResponseRule().toUpperCase();
        if (requestHeader == null) {
            requestHeader = h.getDefaultValue();
            if (requestHeader == null) {
                return null;
            }
        }
        return switch (rule) {
            case "TPDU_SWAP" -> requestHeader.length() >= 10
                    ? requestHeader.substring(0, 2) + requestHeader.substring(6, 10) + requestHeader.substring(2, 6) + requestHeader.substring(10)
                    : requestHeader;
            case "BASE24" -> requestHeader.length() == 12 ? requestHeader.substring(0, 11) + "5" : requestHeader;
            case "DEFAULT" -> h.getDefaultValue();
            default -> requestHeader;
        };
    }

    private static String text(JsonNode node, String name) {
        JsonNode v = node.get(name);
        return v == null || v.isNull() ? null : v.asText();
    }
}
