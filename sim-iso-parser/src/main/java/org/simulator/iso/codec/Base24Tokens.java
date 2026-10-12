package org.simulator.iso.codec;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * BASE24 token format used in private fields (e.g. POS P-63 / ATM S-126):
 * <pre>&amp; nnnnnLLLLL! IDlllll data! IDlllll data...</pre>
 * Header token: "&amp; " + token count (5, including header) + total length (5).
 * Each token: "! " + id (2) + data length (5) + " " + data.
 */
public final class Base24Tokens {

    private Base24Tokens() {
    }

    public static Map<String, String> parse(String value) {
        Map<String, String> tokens = new LinkedHashMap<>();
        if (value == null || !value.startsWith("& ")) {
            throw new IsoParseException("Not a BASE24 token field (missing '& ' header)", 0, 0);
        }
        int pos = 12;
        while (pos < value.length()) {
            if (!value.startsWith("! ", pos) || pos + 10 > value.length()) {
                throw new IsoParseException("Malformed token at position " + pos, 0, pos);
            }
            String id = value.substring(pos + 2, pos + 4);
            int len = Integer.parseInt(value.substring(pos + 4, pos + 9));
            int dataStart = pos + 10;
            if (dataStart + len > value.length()) {
                throw new IsoParseException("Token " + id + " truncated", 0, pos);
            }
            tokens.put(id, value.substring(dataStart, dataStart + len));
            pos = dataStart + len;
        }
        return tokens;
    }

    public static String build(Map<String, String> tokens) {
        StringBuilder body = new StringBuilder();
        tokens.forEach((id, data) -> {
            if (id.length() != 2) {
                throw new IllegalArgumentException("Token id must be 2 chars: " + id);
            }
            body.append("! ").append(id).append(String.format("%05d", data.length())).append(' ').append(data);
        });
        int total = 12 + body.length();
        return "& " + String.format("%05d", tokens.size() + 1) + String.format("%05d", total) + body;
    }
}
