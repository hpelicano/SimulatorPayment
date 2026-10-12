package org.simulator.base24.guardian;

import java.util.List;
import java.util.Map;

/** Result of a Guardian query. */
public record QueryResult(
        String templateId,
        String command,
        int exitCode,
        List<String> columns,
        List<Map<String, String>> rows,
        String rawOutput,
        String stderr,
        long durationMs) {

    public String toJson() {
        return OutputParser.toJson(rows);
    }
}
