package org.simulator.base24.guardian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.simulator.common.SimulatorException;
import org.simulator.common.json.Json;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses remote command output into rows (column name -> value). */
public final class OutputParser {

    private OutputParser() {
    }

    public static List<Map<String, String>> parse(String output, QueryTemplate.Parser p) {
        return switch (p.getType()) {
            case JSON -> json(output, p);
            case DELIMITED -> delimited(output, p);
            case REGEX -> regex(output, p);
            case FIXED -> fixed(output, p);
        };
    }

    private static List<Map<String, String>> json(String output, QueryTemplate.Parser p) {
        String text = output.strip();
        int start = firstIndexOf(text, '[', '{');
        if (start < 0) {
            throw new SimulatorException("Output does not contain JSON");
        }
        JsonNode root;
        try {
            root = Json.mapper().readTree(text.substring(start));
        } catch (Exception e) {
            throw new SimulatorException("Invalid JSON output: " + e.getMessage(), e);
        }
        if (p.getJsonPointer() != null && !p.getJsonPointer().isBlank()) {
            root = root.at(p.getJsonPointer());
        }
        List<Map<String, String>> rows = new ArrayList<>();
        if (root.isArray()) {
            root.forEach(n -> rows.add(flatten(n)));
        } else if (root.isObject()) {
            rows.add(flatten(root));
        }
        return rows;
    }

    private static Map<String, String> flatten(JsonNode n) {
        Map<String, String> row = new LinkedHashMap<>();
        n.fields().forEachRemaining(e -> row.put(e.getKey(),
                e.getValue().isValueNode() ? e.getValue().asText() : e.getValue().toString()));
        return row;
    }

    private static List<String> dataLines(String output, QueryTemplate.Parser p) {
        Pattern skip = p.getSkipLines() == null || p.getSkipLines().isBlank() ? null : Pattern.compile(p.getSkipLines());
        List<String> lines = new ArrayList<>();
        for (String line : output.split("\\r?\\n")) {
            if (skip != null && skip.matcher(line).matches()) {
                continue;
            }
            lines.add(line);
        }
        return lines;
    }

    private static List<Map<String, String>> delimited(String output, QueryTemplate.Parser p) {
        List<String> lines = dataLines(output, p);
        List<String> cols = new ArrayList<>(p.getColumns());
        int first = 0;
        if (p.isHeaderLine() && !lines.isEmpty()) {
            if (cols.isEmpty()) {
                cols = Arrays.stream(lines.get(0).split(p.getDelimiter(), -1)).map(String::trim).toList();
            }
            first = 1;
        }
        List<Map<String, String>> rows = new ArrayList<>();
        for (int i = first; i < lines.size(); i++) {
            String[] parts = lines.get(i).split(p.getDelimiter(), -1);
            rows.add(toRow(cols, Arrays.asList(parts), p.isTrim()));
        }
        return rows;
    }

    private static List<Map<String, String>> fixed(String output, QueryTemplate.Parser p) {
        if (p.getWidths().isEmpty()) {
            throw new SimulatorException("FIXED parser requires 'widths'");
        }
        List<String> lines = dataLines(output, p);
        List<String> cols = new ArrayList<>(p.getColumns());
        int first = 0;
        if (p.isHeaderLine() && !lines.isEmpty()) {
            if (cols.isEmpty()) {
                cols = cut(lines.get(0), p.getWidths()).stream().map(String::trim).toList();
            }
            first = 1;
        }
        List<Map<String, String>> rows = new ArrayList<>();
        for (int i = first; i < lines.size(); i++) {
            rows.add(toRow(cols, cut(lines.get(i), p.getWidths()), p.isTrim()));
        }
        return rows;
    }

    private static List<String> cut(String line, List<Integer> widths) {
        List<String> out = new ArrayList<>();
        int pos = 0;
        for (int w : widths) {
            int end = Math.min(line.length(), pos + w);
            out.add(pos < line.length() ? line.substring(pos, end) : "");
            pos += w;
        }
        return out;
    }

    private static List<Map<String, String>> regex(String output, QueryTemplate.Parser p) {
        if (p.getRegex() == null) {
            throw new SimulatorException("REGEX parser requires 'regex'");
        }
        Pattern pattern = Pattern.compile(p.getRegex());
        List<String> groups = new ArrayList<>();
        Matcher gm = Pattern.compile("\\(\\?<([a-zA-Z][a-zA-Z0-9]*)>").matcher(p.getRegex());
        while (gm.find()) {
            groups.add(gm.group(1));
        }
        List<Map<String, String>> rows = new ArrayList<>();
        for (String line : dataLines(output, p)) {
            Matcher m = pattern.matcher(line);
            if (m.find()) {
                Map<String, String> row = new LinkedHashMap<>();
                for (String g : groups) {
                    String v = m.group(g);
                    row.put(g, v == null ? "" : p.isTrim() ? v.trim() : v);
                }
                rows.add(row);
            }
        }
        return rows;
    }

    private static Map<String, String> toRow(List<String> cols, List<String> values, boolean trim) {
        Map<String, String> row = new LinkedHashMap<>();
        for (int c = 0; c < values.size(); c++) {
            String name = c < cols.size() ? cols.get(c) : "COL" + (c + 1);
            String v = values.get(c);
            row.put(name, trim ? v.trim() : v);
        }
        return row;
    }

    public static String toJson(List<Map<String, String>> rows) {
        ArrayNode arr = Json.mapper().createArrayNode();
        for (Map<String, String> r : rows) {
            ObjectNode o = arr.addObject();
            r.forEach(o::put);
        }
        try {
            return Json.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(arr);
        } catch (Exception e) {
            throw new SimulatorException("Cannot serialize rows", e);
        }
    }

    private static int firstIndexOf(String s, char a, char b) {
        int i = s.indexOf(a);
        int j = s.indexOf(b);
        if (i < 0) {
            return j;
        }
        if (j < 0) {
            return i;
        }
        return Math.min(i, j);
    }
}
