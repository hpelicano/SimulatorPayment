package org.simulator.base24;

import org.simulator.common.SimulatorException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders remote command templates ({@code ${param}} placeholders) with strict parameter validation.
 */
public final class CommandRenderer {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z0-9_]+)}");

    private CommandRenderer() {
    }

    public static String render(String template, List<TemplateParameter> params, Map<String, String> values,
                                Map<String, String> context) {
        Map<String, TemplateParameter> defs = new HashMap<>();
        for (TemplateParameter p : params) {
            defs.put(p.getName(), p);
        }
        Map<String, String> resolved = new HashMap<>(context != null ? context : Map.of());
        for (TemplateParameter p : params) {
            String v = values != null ? values.get(p.getName()) : null;
            if (v == null || v.isEmpty()) {
                v = p.getDefaultValue();
            }
            if ((v == null || v.isEmpty()) && context != null) {
                v = context.get(p.getName());
            }
            if (v == null || v.isEmpty()) {
                if (p.isRequired()) {
                    throw new SimulatorException("Parameter '" + p.getLabel() + "' is required");
                }
                v = "";
            }
            if (!v.matches(p.getPattern())) {
                throw new SimulatorException("Invalid value for '" + p.getLabel() + "': does not match " + p.getPattern());
            }
            if (v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0) {
                throw new SimulatorException("Invalid value for '" + p.getLabel() + "': line breaks are not allowed");
            }
            resolved.put(p.getName(), p.isSqlString() ? v.replace("'", "''") : v);
        }
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String name = m.group(1);
            String v = resolved.get(name);
            if (v == null) {
                throw new SimulatorException("Template placeholder ${" + name + "} has no parameter definition");
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(v));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
